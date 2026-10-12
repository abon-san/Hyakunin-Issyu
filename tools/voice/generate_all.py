#!/usr/bin/env python3
"""KarutaRules（karuta_rules.py の PACE_REF）で 百人一首 100 首 + 序歌 の読み上げ音声を一括生成する。

    tools/voice-src/venv/bin/python -I tools/voice/generate_all.py OUT_DIR [options]

出力（OUT_DIR）:
    wav/{no:03d}_kami.wav, {no:03d}_shimo.wav, joka_kami.wav, joka_shimo.wav  （24 kHz mono、検査用）
    wav/{no:03d}_{part}_alt.wav                                                （公式の読みの別版）
    ogg/ 同名の .ogg（libvorbis, mono, 24 kHz。--codec opus なら Opus）
    timings.json   各クリップの durationMs（エンコード後の OGG）と kanaStartMs、統計
    report.txt     検査の表・フォールバック・注意点
音声ファイルはリポジトリにコミットしない（OUT_DIR はスクラッチパッドなどに置く）。

オプション:
    --trial-dir DIR   試聴用 WAV（日本語名）も作る
    --poems 1,2,17    指定した歌だけ生成（既定は全部。序歌は常に含める。--no-joka で除く）
    --no-alt          別読みを作らない
    --codec vorbis|opus   --quality Q（vorbis の既定 3）
    --yoin-mode world|short|voicevox   下の句の余韻の作り方（既定 world。voicevox は v1 の再現）
    --version-label v2    timings.json の version（既定: voicevox なら v1、それ以外は v2）
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import karuta_rules as kr  # noqa: E402
import voicevox_karuta as vk  # noqa: E402

PRINT_POEMS = (1, 2, 17, 21, "joka")
TRIAL_POEMS = (1, 2, 17, 21)
TRIAL_ALT = (8, 22, 45, 63, 76)


# ---------------------------------------------------------------- 生成対象


class Job:
    def __init__(self, key, part, kanji, reading, marks, oyama, alt=False):
        self.key, self.part, self.kanji, self.reading = key, part, kanji, reading
        self.marks, self.oyama, self.alt = marks, oyama, alt

    @property
    def stem(self) -> str:
        base = "joka" if self.key == "joka" else f"{self.key:03d}"
        return f"{base}_{self.part}" + ("_alt" if self.alt else "")


def build_jobs(poems, nobashi, only, with_joka, with_alt) -> list[Job]:
    jobs: list[Job] = []
    if with_joka:
        nb = nobashi["joka"]
        for i, part in enumerate(("kami", "shimo")):
            jobs.append(Job("joka", part, kr.JOKA_KANJI[i], kr.JOKA_YOMI[i], nb[part], False))
    for n in only:
        p, nb = poems[n], nobashi[str(n)]
        for part in ("kami", "shimo"):
            jobs.append(Job(n, part, p[part], p[part + "Yomi"], nb[part], n in kr.OYAMA_FUDA))
    if with_alt:
        for (n, part), (old, new) in kr.ALT_REPLACE.items():
            if n not in only:
                continue
            p, nb = poems[n], nobashi[str(n)]
            rd = p[part + "Yomi"]
            assert rd.count(old) == 1, f"{n} {part}: 『{old}』が読み {rd} に 1 回だけ出てこない"
            alt = rd.replace(old, new)
            assert len("".join(vk.split_reading(alt))) == len("".join(vk.split_reading(rd)))
            jobs.append(Job(n, part, p[part], alt, nb[part], n in kr.OYAMA_FUDA, alt=True))
    return jobs


# ---------------------------------------------------------------- ffmpeg / ffprobe


def encode(wav: Path, out: Path, codec: str, quality: float) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    enc = (["-c:a", "libvorbis", "-q:a", str(quality)] if codec == "vorbis"
           else ["-c:a", "libopus", "-b:a", "32k", "-vbr", "on"])
    cmd = ["ffmpeg", "-y", "-v", "error", "-i", str(wav), *enc, "-ac", "1", "-ar", "24000", str(out)]
    subprocess.run(cmd, check=True)


def probe_ms(path: Path) -> float:
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                        "-of", "csv=p=0", str(path)], check=True, capture_output=True, text=True)
    return float(r.stdout.strip()) * 1000


# ---------------------------------------------------------------- 表示用


def describe(clip: kr.Clip, job: Job) -> str:
    qr, plan = clip.qr, clip.plan
    chars = kr.reading_chars(job.reading)
    ku_txt, lv_txt, pos = [], [], 0
    for k, n in enumerate(plan.ku_sizes):
        idx = [i for i, u in enumerate(qr.units) if u.ku == k]
        ku_txt.append("".join(qr.units[i].text for i in idx))
        lv_txt.append(plan.levels[pos:pos + n])
        pos += n
    lines = [f"[{job.stem}] {job.reading}" + ("  (大山札)" if job.oyama else ""),
             f"    モーラ: {' | '.join(ku_txt)}  ({sum(plan.ku_sizes)} モーラ, {len(chars)} 字)",
             f"    高さ  : {' | '.join(lv_txt)}   pause={['有' if p else '無' for p in plan.pause_after[:-1]]}"]
    for mk in plan.marks:
        m = qr.units[mk.mora]
        lines.append(f"    印 k={mk.k:2d} 『{mk.ch}』→ モーラ#{mk.mora:2d}『{m.text}』(句{mk.ku + 1}) "
                     f"{mk.kind:16s} {'' if mk.length is None else f'{mk.length:.2f}'} {mk.note}")
    return "\n".join(lines)


# ---------------------------------------------------------------- 試聴用


def silence(sec: float) -> np.ndarray:
    return np.zeros(int(round(sec * vk.SAMPLE_RATE)))


def first_ku(reading: str) -> str:
    return vk.split_reading(reading)[0]


def build_trials(clips: dict, poems, rules: kr.KarutaRules, trial_dir: Path) -> list[str]:
    trial_dir.mkdir(parents=True, exist_ok=True)
    for old in trial_dir.glob("*.wav"):
        old.unlink()
    gap = silence(rules.trial_gap_s)
    ma = silence(1.0)
    X = lambda stem: clips[stem].samples
    written = []

    def save(name, x):
        vk.save_wav(trial_dir / name, x)
        written.append(f"{name}  {len(x) / vk.SAMPLE_RATE:.2f}s")

    joka = np.concatenate([X("joka_kami"), gap, X("joka_shimo"), X("joka_shimo"), ma])
    save("01_序歌.wav", joka)
    for n in TRIAL_POEMS:
        x = np.concatenate([X(f"{n:03d}_kami"), gap, X(f"{n:03d}_shimo")])
        save(f"{n:02d}_{first_ku(poems[n]['kamiYomi'])}.wav", x)
    for n in TRIAL_ALT:
        if f"{n:03d}_kami_alt" not in clips and f"{n:03d}_shimo_alt" not in clips:
            continue
        name = first_ku(poems[n]["kamiYomi"])
        for label, alt in (("いう" if n != 76 else "まがう", False), ("ゆう" if n != 76 else "まごう", True)):
            parts = {}
            for part in ("kami", "shimo"):
                alt_key = f"{n:03d}_{part}_alt"
                parts[part] = X(alt_key if alt and alt_key in clips else f"{n:03d}_{part}")
            save(f"{n:02d}_{name}_{label}.wav", np.concatenate([parts["kami"], gap, parts["shimo"]]))
    if "002_kami" in clips:
        flow = np.concatenate([joka, X("001_kami"), silence(3.0), X("001_shimo"), ma, X("002_kami")])
        save("00_競技の流れ.wav", flow)
    return written


# ---------------------------------------------------------------- メイン


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    ap.add_argument("--trial-dir", type=Path)
    ap.add_argument("--poems", help="例: 1,2,17（既定は 1〜100 全部）")
    ap.add_argument("--no-joka", action="store_true")
    ap.add_argument("--no-alt", action="store_true")
    ap.add_argument("--no-ogg", action="store_true")
    ap.add_argument("--codec", choices=["vorbis", "opus"], default="vorbis")
    ap.add_argument("--quality", type=float, default=3)
    ap.add_argument("--yoin-mode", choices=["world", "short", "voicevox"], default="world")
    ap.add_argument("--version-label")
    ap.add_argument("--character", default="冥鳴ひまり")
    ap.add_argument("--style", default="ノーマル")
    args = ap.parse_args()
    t0 = time.time()
    rules = dataclasses.replace(kr.PACE_REF, yoin_mode=args.yoin_mode)
    version = args.version_label or ("v1" if args.yoin_mode == "voicevox" else "v2")
    poems, nobashi = kr.load_poems(), kr.load_nobashi()
    only = [int(x) for x in args.poems.split(",")] if args.poems else list(range(1, 101))
    jobs = build_jobs(poems, nobashi, only, not args.no_joka, not args.no_alt)
    eng = vk.Engine()
    st = eng.find_style(args.character, args.style)
    if args.character == "冥鳴ひまり" and args.style == "ノーマル":
        assert st.id == 14, st
    print(f"voice: {st.character} / {st.style} (id {st.id}); jobs: {len(jobs)}; rules: {rules}")
    wav_dir, ogg_dir = args.out / "wav", args.out / "ogg"
    for d in (wav_dir, ogg_dir):
        d.mkdir(parents=True, exist_ok=True)
        for old in d.glob("*"):
            old.unlink()

    clips: dict[str, kr.Clip] = {}
    entries: list[dict] = []
    report: list[str] = []
    fallbacks, anomalies, others = [], [], []
    qcache: dict[tuple, vk.QueryResult] = {}
    yoin_checks: dict[str, dict] = {}
    for jb in jobs:
        key = (jb.kanji, jb.reading)
        if key not in qcache:
            qcache[key] = eng.build_query(jb.kanji, jb.reading, st.id)
        qr = qcache[key]
        plan = kr.plan_verse(qr, jb.part, jb.reading, jb.marks, jb.oyama, rules)
        clip = kr.render_clip(eng, qr, plan, st.id, rules)
        kr.check_plan_vs_query(clip, qr.query, rules)
        yoin_chk = kr.check_yoin(clip, rules)
        if yoin_chk:
            yoin_checks[jb.stem] = yoin_chk
        clips[jb.stem] = clip
        wav = wav_dir / f"{jb.stem}.wav"
        vk.save_wav(wav, clip.samples)
        ent = {"file": f"{jb.stem}.ogg", "poem": jb.key, "part": jb.part, "alt": jb.alt,
               "reading": jb.reading, "verseMs": round(clip.verse_s * 1000),
               "yoinMs": round(clip.yoin_s * 1000), "kanaStartMs": clip.kana_ms,
               "wavMs": round(len(clip.samples) / vk.SAMPLE_RATE * 1000, 1),
               "gainDb": round(clip.gain_db, 2)}
        if not args.no_ogg:
            ogg = ogg_dir / f"{jb.stem}.ogg"
            encode(wav, ogg, args.codec, args.quality)
            dur = probe_ms(ogg)
            assert abs(dur - ent["wavMs"]) <= 30, f"{jb.stem}: OGG {dur:.1f} ms vs WAV {ent['wavMs']} ms"
            ent["durationMs"] = round(dur)
            ent["bytes"] = ogg.stat().st_size
        else:
            ent["durationMs"] = round(ent["wavMs"])
        entries.append(ent)
        fb = [(i + 1, k.reading, k.aquestalk, k.ojt_kana) for i, k in enumerate(qr.ku) if k.fallback]
        if fb:
            fallbacks.append((jb.stem, fb))
        anomalies += [(jb.stem, a) for a in plan.anomalies]
        others += [(jb.stem, mk) for mk in plan.marks if mk.kind == "other"]
        if jb.key in PRINT_POEMS and not jb.alt:
            report.append(describe(clip, jb))
        print(f"  {jb.stem:16s} verse {clip.verse_s:5.2f}s yoin {clip.yoin_s:4.2f}s "
              f"total {len(clip.samples) / vk.SAMPLE_RATE:5.2f}s fallback={bool(fb)}", flush=True)

    # ---- 統計（余韻を除く）
    def stat(sel):
        v = np.array([e["verseMs"] for e in entries if sel(e)]) / 1000
        return {"n": int(len(v)), "meanS": round(float(v.mean()), 3), "minS": round(float(v.min()), 3),
                "maxS": round(float(v.max()), 3)} if len(v) else None

    stats = {"kami": stat(lambda e: e["part"] == "kami" and e["poem"] != "joka" and not e["alt"]),
             "shimo": stat(lambda e: e["part"] == "shimo" and e["poem"] != "joka" and not e["alt"]),
             "kamiAll": stat(lambda e: e["part"] == "kami" and not e["alt"]),
             "shimoAll": stat(lambda e: e["part"] == "shimo" and not e["alt"])}
    total_bytes = sum(e.get("bytes", 0) for e in entries)
    main_bytes = sum(e.get("bytes", 0) for e in entries if not e["alt"])
    timings = {"version": version, "voice": {"character": st.character, "style": st.style, "id": st.id},
               "codec": ("vorbis q%g" % args.quality if args.codec == "vorbis" else "opus 32k") + ", mono, 24 kHz",
               "rules": {k: getattr(rules, k) for k in rules.__dataclass_fields__},
               "stats": stats, "totalOggBytes": total_bytes, "mainOggBytes": main_bytes, "clips": entries}
    (args.out / "timings.json").write_text(json.dumps(timings, ensure_ascii=False, indent=1), "utf-8")

    # ---- レポート
    rep = ["# 表（高さ・印→モーラ）", *report, "", "# フォールバックを使った句"]
    for stem, fb in fallbacks:
        for ku, rd, aq, ojt in fb:
            rep.append(f"{stem} 句{ku} 読み {rd}  OpenJTalk: {ojt}  使用: {aq}")
    rep += ["", "# 句の途中のその他の印 (0.25)"]
    rep += [f"{s} k={m.k} 『{m.ch}』 モーラ#{m.mora} 句{m.ku + 1} {m.note}" for s, m in others]
    rep += ["", "# 注意点"] + [f"{s}: {a}" for s, a in anomalies]
    rep += ["", "# 統計", json.dumps(stats, ensure_ascii=False, indent=1)]
    if yoin_checks:
        rep += ["", f"# 余韻の検査（yoin_mode={rules.yoin_mode}）: 測った値"]
        for key in sorted({k for v in yoin_checks.values() for k in v}):
            vals = np.array([v[key] for v in yoin_checks.values() if key in v])
            rep.append(f"{key}: n={len(vals)} min={vals.min():.3f} mean={vals.mean():.3f} max={vals.max():.3f}")
    (args.out / "report.txt").write_text("\n".join(rep), "utf-8")
    print("\n".join(rep))

    if args.trial_dir:
        need = [f"{n:03d}_{p}" for n in TRIAL_POEMS for p in ("kami", "shimo")]
        missing = [s for s in ["joka_kami", "joka_shimo", "001_kami", "001_shimo", "002_kami"] + need
                   if s not in clips]
        assert not missing, f"試聴用に必要なクリップが無い: {missing}"
        for line in build_trials(clips, poems, rules, args.trial_dir):
            print("trial:", line)
    print(f"done in {time.time() - t0:.1f}s; clips={len(entries)}; ogg total {total_bytes / 1e6:.2f} MB "
          f"(main {main_bytes / 1e6:.2f} MB)")


if __name__ == "__main__":
    main()
