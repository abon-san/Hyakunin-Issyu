#!/usr/bin/env python3
"""新2（短い母音が自然に消える余韻）の「聞こえている長さ」を変えた試聴用サンプルを作り、測って確かめる。

    tools/voice-src/venv/bin/python -I tools/voice/make_tail_samples.py OUT_DIR [--tails 0.45,0.6,0.8] [--poems 1,2,17]

OUT_DIR に 01_序歌_新2.wav, 01_序歌_長さ1.wav, _長さ2.wav, _長さ3.wav（と 01_あきのたの, 02_はるすぎて, 17_ちはやぶる）と
README.txt, metrics.json を作る。各ファイルは 上の句 + 0.86 秒 + 下の句（余韻つき）。序歌は 下の句を 2 回、最後に 1 秒の間。
長さ N の値は KarutaRules.yoin_short_tail_s（generate_all.py の --yoin-short-tail-s）にそのまま渡せる。

書き出した WAV を読み戻して測る（作り方に頼らない）: 聞こえている長さ、減衰の単調さ、HNR、クリック、クリッピング、
そして 新2 と比べて 切り替えより前のサンプル・総尺・kanaStartMs が完全に同じこと。音声はコミットしない。
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import sys
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate_all as ga  # noqa: E402
import karuta_rules as kr  # noqa: E402
import voicevox_karuta as vk  # noqa: E402
import yoin  # noqa: E402

SR = vk.SAMPLE_RATE
LABELS = ["長さ1", "長さ2", "長さ3"]


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32767


def compose(kami: np.ndarray, shimo: np.ndarray, gap: float, joka: bool) -> tuple[np.ndarray, list[int]]:
    """試聴用の 1 ファイル。戻り値: (波形, 下の句の開始サンプルのリスト)。"""
    g = np.zeros(int(round(gap * SR)))
    starts = [len(kami) + len(g)]
    parts = [kami, g, shimo]
    if joka:
        starts.append(starts[0] + len(shimo))
        parts += [shimo, np.zeros(SR)]
    return np.concatenate(parts), starts


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    ap.add_argument("--tails", default="0.45,0.6,0.8")
    ap.add_argument("--poems", default="1,2,17")
    ap.add_argument("--tol", type=float, default=0.08)
    args = ap.parse_args()
    tails = [float(t) for t in args.tails.split(",")]
    only = [int(n) for n in args.poems.split(",")]
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    for old in list(out.glob("*.wav")) + [out / "README.txt", out / "metrics.json"]:
        old.unlink(missing_ok=True)

    poems, nobashi = kr.load_poems(), kr.load_nobashi()
    jobs = ga.build_jobs(poems, nobashi, only, True, False)
    eng = vk.Engine()
    st = eng.find_style("冥鳴ひまり", "ノーマル")
    assert st.id == 14
    base = dataclasses.replace(kr.PACE_REF, yoin_mode="short")
    variants = [("新2", 0.0)] + [(LABELS[i], t) for i, t in enumerate(tails)]
    clips: dict[tuple[str, str], kr.Clip] = {}
    for jb in jobs:
        qr = eng.build_query(jb.kanji, jb.reading, st.id)
        for label, tail in variants:
            if jb.part == "kami" and label != "新2":
                clips[(label, jb.stem)] = clips[("新2", jb.stem)]
                continue
            rules = dataclasses.replace(base, yoin_short_tail_s=tail)
            plan = kr.plan_verse(qr, jb.part, jb.reading, jb.marks, jb.oyama, rules)
            clip = kr.render_clip(eng, qr, plan, st.id, rules)
            kr.check_plan_vs_query(clip, qr.query, rules)
            if jb.part == "shimo":
                kr.check_yoin(clip, rules)
            clips[(label, jb.stem)] = clip
        print("rendered", jb.stem, flush=True)

    names = {"joka": "01_序歌"}
    for n in only:
        names[f"{n:03d}"] = f"{n:02d}_{ga.first_ku(poems[n]['kamiYomi'])}"
    metrics: dict = {}
    problems: list[str] = []
    for key, name in names.items():
        stem = "joka" if key == "joka" else key
        ref_x = None
        for label, tail in variants:
            kami, shimo = clips[(label, f"{stem}_kami")], clips[(label, f"{stem}_shimo")]
            x, starts = compose(kami.samples, shimo.samples, base.trial_gap_s, key == "joka")
            path = out / f"{name}_{label}.wav"
            vk.save_wav(path, x)
            y = read_wav(path)                                  # 以降は書き出した PCM を測る
            ref = clips[("新2", f"{stem}_shimo")]
            info = shimo.yoin_info
            v0 = shimo.spans[-1][1]
            rec: dict = dict(tail_param=tail, file=path.name, seconds=len(y) / SR)
            # --- 新2 との同一性（総尺・kanaStartMs・切り替えより前のサンプル）
            if label != "新2":
                assert len(shimo.samples) == len(ref.samples), "総尺が新2と違う"
                assert shimo.kana_ms == ref.kana_ms, "kanaStartMs が新2と違う"
                assert abs(shimo.verse_s - ref.verse_s) < 1e-12 and abs(shimo.yoin_s - ref.yoin_s) < 1e-12
                sp = int(info["splice_s"] * SR)
                a, b = starts[0], starts[0] + sp
                same = bool(np.array_equal(y[:b], read_wav(out / f"{name}_新2.wav")[:b]))
                rec["identical_before_splice"] = same
                rec["splice_start_s_from_v0"] = info["splice_s"] - v0
                rec["splice_end_s_from_v0"] = info["splice_end_s"] - v0
                rec["tau40_s"] = info["tau40_s"]
                rec["gain_db_same"] = bool(abs(shimo.gain_db - ref.gain_db) < 1e-9)
                if not (same and rec["gain_db_same"]):
                    problems.append(f"{path.name}: 新2と同一でない部分がある")
            # --- 余韻の測定（書き出した PCM から）
            runs = []
            for s0 in starts:
                i0 = s0 + int(round(v0 * SR))
                seg = y[: s0 + len(shimo.samples)]
                f0 = info.get("f0") or float(np.exp({"L": base.level_L, "M": base.level_M, "H": base.level_H}[shimo.plan.levels[-1]]))
                m = yoin.tail_metrics(seg, i0, f0, info["splice_s"] - v0, info["splice_end_s"] - v0) if tail else None
                if tail == 0:   # 新2: 簡易測定（HNR・クリックは切り替えが無いので省く）
                    L, tpk, pk = yoin.audible_len_s(seg, i0)
                    Lf, _, _ = yoin.audible_len_s(seg, i0, win_s=0.01, hop_s=0.0025)
                    e = yoin.level_track_db(seg[i0:])
                    ip, ie = int(round((tpk - 0.01) / 0.005)), int(round((L - 0.01) / 0.005))
                    d = e[ip + 4: ie + 1] - e[ip: ie + 1 - 4]
                    m = dict(audible_s=L, audible_fine_s=Lf, peak_s=tpk, peak_db=pk,
                             max_rise_db=float(d.max()) if len(d) else 0.0,
                             peak_dbfs=float(20 * np.log10(np.abs(seg).max())),
                             n_clip=int((np.abs(np.round(seg * 32767)) >= 32767).sum()))
                # 減衰曲線: ピークからの dB を代表時刻で
                e = yoin.level_track_db(y[i0: i0 + int(1.8 * SR)])
                t_c = np.arange(len(e)) * 0.005 + 0.01
                pk = e.max()
                m["curve_db"] = {f"{t:.2f}": float(np.interp(t, t_c, e) - pk) for t in (0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 1.0)}
                m["tail_s_abs_from_v0"] = None
                runs.append(m)
            rec["runs"] = runs
            f0n = info.get("f0") or ref.yoin_info.get("f0") or float(np.exp({"L": base.level_L, "M": base.level_M, "H": base.level_H}[shimo.plan.levels[-1]]))
            i0n = starts[0] + int(round(v0 * SR))
            rec["hnr_natural"] = float(yoin.hnr_local_db(read_wav(out / f"{name}_新2.wav")[i0n: i0n + int(0.2 * SR)], f0n).mean()) if label != "新2" else float("nan")
            m = runs[0]
            if tail:
                assert (abs(m["audible_s"] - tail) <= args.tol), f"{path.name}: {m['audible_s']:.3f}"
                if not (abs(m["audible_s"] - tail) <= args.tol and abs(m["audible_fine_s"] - tail) <= args.tol):
                    problems.append(f"{path.name}: 目標 {tail} に対し {m['audible_s']:.3f}/{m['audible_fine_s']:.3f}")
                if m["max_rise_db"] > 0.5:
                    problems.append(f"{path.name}: 単調でない +{m['max_rise_db']:.2f}")
                if m["hnr_deep_min"] < 12:
                    problems.append(f"{path.name}: HNR {m['hnr_deep_min']:.1f}")
            if m["n_clip"] or m["peak_dbfs"] > -0.9:
                problems.append(f"{path.name}: クリッピング/ピーク {m['peak_dbfs']:.2f}")
            metrics[path.name] = rec
            tail_txt = f"audible {m['audible_s']:.3f}/{m['audible_fine_s']:.3f}s rise {m['max_rise_db']:+.2f}dB"
            if tail:
                tail_txt += (f" HNR tail min {m['hnr_tail_min']:.1f} mean {m['hnr_tail_mean']:.1f} deep {m['hnr_deep_min']:.1f} "
                             f"(natural vowel {rec['hnr_natural']:.1f}) click {m['click_splice']:.1f}/{m['click_body']:.1f}")
            print(f"{path.name:28s} {len(y) / SR:6.2f}s {tail_txt}", flush=True)

    # 序歌の 2 回の下の句が同じ測定になること
    for label, _ in variants:
        r = metrics[f"01_序歌_{label}.wav"]["runs"]
        assert abs(r[0]["audible_s"] - r[1]["audible_s"]) < 1e-9
    (out / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=1), "utf-8")
    write_readme(out, variants, metrics, base)
    if problems:
        print("PROBLEMS:\n" + "\n".join(problems))
        sys.exit(1)
    print("all checks passed")


def write_readme(out: Path, variants, metrics, base: kr.KarutaRules) -> None:
    def runs(label):
        return [r["runs"][0] for f, r in metrics.items() if f.endswith(f"_{label}.wav")]

    def avg(label, key):
        return float(np.mean([m[key] for m in runs(label)]))

    def curve(label, t):
        return float(np.mean([m["curve_db"][t] for m in runs(label)]))

    lines = ["新2 の最後の長さの聴き比べ（v3）", "",
             "どのファイルも 上の句 + 0.86 秒の無音 + 下の句（余韻つき）です。序歌だけは下の句を 2 回読み、最後に 1 秒の間があります。",
             "違うのは、下の句の最後の母音（余韻）の「聞こえている長さ」だけです。上の句、句の途中の伸ばし、",
             "最後の母音の途中までの音、総尺（最後の母音の開始から 3.0 秒）、アプリ用のタイミング（kanaStartMs）は",
             "4 つとも完全に同じです（新2 とビット単位で同じ部分を確かめてあります）。",
             "「聞こえている長さ」= 最後の母音の開始から、その母音のピークより 40 dB 小さくなるまでの秒数（4 ファイルの実測平均）。",
             "「減衰」= 母音のピークからのレベル（20 ms の RMS）。母音のピークは母音の頭から 0.01〜0.04 秒の所です。", ""]
    for label, tail in variants:
        pts = [t for t in ("0.10", "0.20", "0.30", "0.40", "0.60", "0.80") if float(t) <= avg(label, "audible_s") + 0.01]
        cv = "、".join(f"{t} 秒で {curve(label, t):.0f} dB" for t in pts)
        if label == "新2":
            lines += [f"_新2    聞こえている長さ 約 {avg(label, 'audible_s'):.2f} 秒（yoin_short_tail_s = 0）。",
                      "        短い母音（vowel_length 0.25）がそのまま自然に消えます。",
                      f"        減衰: {cv}。"]
        else:
            lines += [f"_{label}   聞こえている長さ 約 {avg(label, 'audible_s'):.2f} 秒（yoin_short_tail_s = {tail:g}）。",
                      "        新2 と同じ母音を、0.05〜0.1 秒のところで WORLD の持続音につなぎ、平らな部分なしで、すぐに穏やかに落ち始めます。",
                      f"        減衰: {cv}。だんだん速く落ちる曲線（時間の {base.yoin_short_power:g} 乗に比例して dB が下がる）。",
                      f"        声の質は保たれます（持続部分の HNR は最小 {np.min([m['hnr_tail_min'] for m in runs(label)]):.0f} dB、"
                      "雑音のかたまりは出ません）。"]
        lines.append("")
    lines += ["聴いてほしい点: 新2 より少し長く、でも「伸ばしている」感じにならない長さはどれか。",
              "選んでいただいた番号に対応する yoin_short_tail_s の値で、100 首すべてを作り直します。",
              "", "ファイル: 01_序歌 / 01_あきのたの / 02_はるすぎて / 17_ちはやぶる（それぞれ _新2 _長さ1 _長さ2 _長さ3）"]
    (out / "README.txt").write_text("\n".join(lines) + "\n", "utf-8")


if __name__ == "__main__":
    main()
