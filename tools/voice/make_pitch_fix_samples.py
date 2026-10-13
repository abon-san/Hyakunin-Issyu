#!/usr/bin/env python3
"""競技ルール版（v4）の「最後が少しピッチが上がっていくように聞こえる」への対処（v5）の試聴サンプルを作り、測って確かめる。

    tools/voice-src/venv/bin/python -I tools/voice/make_pitch_fix_samples.py OUT_DIR [--poem 1] [--others joka,17]

OUT_DIR に次を作る（音声はコミットしない）:
    01_あきのたの_競技ルール_修正.wav        修正（pitch_mode=anchored、余韻は平ら → -60 cent）。上の句 → 間合い 1.0 秒 → 下の句 → 余韻
    01_あきのたの_競技ルール_流れ_修正.wav    下の句 + 余韻 → 間合い 1.0 秒 → 上の句
    01_あきのたの_競技ルール_修正_平ら.wav    同じで、余韻の F0 は下げない（-0 cent）
    01_あきのたの_競技ルール_修正_強め.wav    同じで、余韻の F0 を -100 cent まで下げる
    序歌_競技ルール_修正.wav, 17_ちはやぶる_競技ルール_修正.wav     他の母音で聞く用
    README.txt, metrics.json
修正前（v4 と同じ設定 = pitch_mode=legacy）も同じ方法で作り直して、書き出した WAV を読み戻して測り、前後を比べる。
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import sys
import warnings
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate_all as ga  # noqa: E402
import karuta_rules as kr  # noqa: E402
import make_dim_rule_samples as mdr  # noqa: E402
import make_tail_samples as mts  # noqa: E402
import pitch_check as pc  # noqa: E402
import rule_timing as rt  # noqa: E402
import voicevox_karuta as vk  # noqa: E402
import yoin  # noqa: E402

warnings.filterwarnings("ignore")
SR = vk.SAMPLE_RATE
VARIANTS = {"before": dict(pitch_mode="legacy"),
            "after": dict(pitch_mode="anchored", yoin_fall_cents=-60.0),
            "flat": dict(pitch_mode="anchored", yoin_fall_cents=0.0),
            "strong": dict(pitch_mode="anchored", yoin_fall_cents=-100.0)}


def q(x: np.ndarray) -> np.ndarray:
    """書き出す WAV と同じ 16 bit の値。"""
    return np.round(np.clip(x, -1, 1) * 32767) / 32767


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    ap.add_argument("--poem", type=int, default=1)
    ap.add_argument("--others", default="joka,17")
    args = ap.parse_args()
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    for old in list(out.glob("*.wav")) + [out / "README.txt", out / "metrics.json"]:
        old.unlink(missing_ok=True)
    poems, nobashi = kr.load_poems(), kr.load_nobashi()
    keys = [args.poem] + [("joka" if k == "joka" else int(k)) for k in args.others.split(",") if k]
    eng = vk.Engine()
    st = eng.find_style("冥鳴ひまり", "ノーマル")
    assert st.id == 14
    base = dataclasses.replace(kr.PACE_REF, yoin_mode="short", timing="rule", **mdr.RULE_TAIL)
    ma_s = base.rule_ma_s
    metrics: dict = {}
    problems: list[str] = []

    for key in keys:
        n = key if key != "joka" else None
        jl = ga.build_jobs(poems, nobashi, [key] if key != "joka" else [], key == "joka", False)
        jobs = {j.part: j for j in jl}
        label = "序歌" if key == "joka" else f"{key:02d}_{ga.first_ku(poems[key]['kamiYomi'])}"
        qrs = {p: eng.build_query(j.kanji, j.reading, st.id) for p, j in jobs.items()}
        variants = ["before", "after"] + (["flat", "strong"] if key == args.poem else [])
        recs: dict = {}
        for vn in variants:
            rules = dataclasses.replace(base, **VARIANTS[vn])
            clips = {}
            for part, jb in jobs.items():
                plan = kr.plan_verse(qrs[part], part, jb.reading, jb.marks, jb.oyama, rules)
                clip = kr.render_clip(eng, qrs[part], plan, st.id, rules, jb.short)
                rt.check_rule_clip(clip, rules)
                if part == "shimo":
                    kr.check_yoin(clip, rules)
                clips[part] = clip
            rk, rs = clips["kami"], clips["shimo"]
            sv0 = rs.spans[-1][1]
            s0_ = rs.spans[0][0]
            kb = int(round(rk.spans[-1][2] * SR))
            ka = int(round(rk.spans[0][0] * SR))
            sa = int(round(s0_ * SR))
            ma = np.zeros(int(round(ma_s * SR)))
            kami_part, shimo_part = rk.samples[:kb], rs.samples[sa:]
            fileA = np.concatenate([kami_part, ma, shimo_part])
            lead = np.zeros(int(round(0.2 * SR)))
            fileB = np.concatenate([lead, shimo_part, ma, rk.samples[ka:kb]])
            suffix = {"before": "修正前", "after": "修正", "flat": "修正_平ら", "strong": "修正_強め"}[vn]
            pa = out / f"{label}_競技ルール_{suffix}.wav"
            if vn == "before":
                pa = out / "_before" / pa.name
            vk.save_wav(pa, fileA)
            pb = None
            if key == args.poem:
                pb = pa.with_name(pa.name.replace("競技ルール_", "競技ルール_流れ_"))
                vk.save_wav(pb, fileB)
            ya = mts.read_wav(pa)
            ia0 = len(kami_part) + len(ma)
            i0a = ia0 + int(round(sv0 * SR)) - sa
            off_a = ia0 / SR - s0_
            tm = mdr.measure_tail(ya, i0a, rs.yoin_info, sv0, len(ya))
            tp = pc.tail_pitch(ya, i0a, tm["audible_s"], base.yoin_short_dim_start_s)
            holds = []
            hm = {"kami": rt.measure_holds(ya, rk, 0.0), "shimo": rt.measure_holds(ya, rs, off_a)}
            for part, clip, off in (("kami", rk, 0.0), ("shimo", rs, off_a)):
                for h, m in zip([h for h in clip.holds if "ext_start_s" in h], hm[part]):
                    hp = pc.hold_pitch(ya, h["ext_start_s"] + off, h["ext_end_s"] + off)
                    holds.append(dict(part=part, text=h["text"], vowel_s=h["vowel_s"], f0_front=h["f0_front"],
                                      f0_back=h["f0_back"], hnr_min=m["hnr_min"], click_splice=m["click_splice"],
                                      click_body=m["click_body"], edge_step_db=m["edge_step_db"],
                                      f0_max_step_cents=m["f0_max_step_cents"], **hp))
            rec = dict(file=pa.name, total_s=len(ya) / SR, kami_s=rk.verse_s, shimo_s=rs.verse_s, yoin_s=rs.yoin_s,
                       kana_ms=dict(kami=rk.kana_ms, shimo=rs.kana_ms), tail=tm, tail_pitch=tp, holds=holds,
                       peak_dbfs=float(20 * np.log10(np.abs(ya).max())), n_clip=int((np.abs(np.round(ya * 32767)) >= 32767).sum()))
            if pb is not None:
                yb = mts.read_wav(pb)
                ib0 = len(lead)
                i0b = ib0 + int(round(sv0 * SR)) - sa
                tmb = mdr.measure_tail(yb, i0b, rs.yoin_info, sv0, ib0 + len(shimo_part))
                rec["B_total_s"] = len(yb) / SR
                rec["B_tail"] = tmb
                rec["B_tail_pitch"] = pc.tail_pitch(yb, i0b, tmb["audible_s"], base.yoin_short_dim_start_s)
                rec["B_file"] = pb.name
                rec["B_n_clip"] = int((np.abs(np.round(yb * 32767)) >= 32767).sum())
            recs[vn] = rec
            print(f"{label} {vn:7s} total {rec['total_s']:.3f}s tail audible {tm['audible_s']:.3f} "
                  f"slope {tp['slope_cents_per_s_harvest']:+.1f}/{tp['slope_cents_per_s_acf']:+.1f} cent/s "
                  f"last0.5s {tp['last0.5s_cents_harvest']:+.1f}/{tp['last0.5s_cents_acf']:+.1f} "
                  f"above {tp['max_above_hold_cents_harvest']:+.1f}/{tp['max_above_hold_cents_acf']:+.1f} "
                  f"HNR {tm['hnr_deep_min']:.1f}", flush=True)
            for h in holds:
                print(f"    {h['part']} {h['text']}: end {h['end_cents_harvest']:+.1f}/{h['end_cents_acf']:+.1f} "
                      f"slope {h['slope_cents_per_s_harvest']:+.1f} above {h['max_above_start_cents_harvest']:+.1f} "
                      f"flick {h['flick_cents']:+.1f}", flush=True)
        metrics[label] = recs
        # ---- 判定（修正版 3 種）
        b = recs["before"]
        for vn in variants[1:]:
            r = recs[vn]
            tag = f"{label}/{vn}"
            tp, tm = r["tail_pitch"], r["tail"]
            for nm in ("harvest", "acf"):
                if tp[f"slope_cents_per_s_{nm}"] > (0.0 if vn != "flat" else 1.5):   # 平らは測定のゆらぎ程度を許す
                    problems.append(f"{tag}: 余韻の F0 の傾き {tp[f'slope_cents_per_s_{nm}']:+.2f} cent/s ({nm})")
                if tp[f"max_above_hold_cents_{nm}"] > 5.0:
                    problems.append(f"{tag}: 平らな部分より {tp[f'max_above_hold_cents_{nm}']:+.1f} cent 高い窓 ({nm})")
                if tp[f"last0.5s_cents_{nm}"] > 2.0 or tp[f"last1s_cents_{nm}"] > 2.0:
                    problems.append(f"{tag}: 終わりの F0 が頭より高い ({nm})")
            if tp["centroid_slope_hz_per_s"] > 5.0 or tp["hi_db_slope_per_s"] > 0.5:
                problems.append(f"{tag}: 音色が明るくなる 重心 {tp['centroid_slope_hz_per_s']:+.1f} Hz/s 高域 {tp['hi_db_slope_per_s']:+.2f} dB/s")
            if tm["hnr_deep_min"] < 12 or tm["click_splice"] > 1.5 * tm["click_body"] + 1.0 or tm["n_clip"] or r["n_clip"]:
                problems.append(f"{tag}: HNR/クリック/クリッピング {tm['hnr_deep_min']:.1f} {tm['click_splice']:.1f}/{tm['click_body']:.1f}")
            if abs(r["total_s"] - b["total_s"]) > 0.05 or abs(r["yoin_s"] - b["yoin_s"]) > 0.001:
                problems.append(f"{tag}: 総尺が変わった {r['total_s']:.3f} vs {b['total_s']:.3f}")
            dk = max(abs(a - c) for part in ("kami", "shimo") for a, c in zip(r["kana_ms"][part], b["kana_ms"][part]))
            r["kana_ms_max_diff"] = dk
            if dk > 50:
                problems.append(f"{tag}: kanaStartMs が最大 {dk} ms 動いた")
            for h in r["holds"]:
                for nm in ("harvest", "acf"):
                    if h[f"end_cents_{nm}"] > 8.0:
                        problems.append(f"{tag} 『{h['text']}』の伸ばしで F0 が上がっている {h[f'end_cents_{nm}']:+.1f} cent ({nm})")
                if h["flick_cents"] > 30.0:
                    problems.append(f"{tag} 『{h['text']}』の伸ばしの終わりで上向きにはねる {h['flick_cents']:+.1f} cent")
                if h["hnr_min"] < 10 or h["click_splice"] > 1.5 * h["click_body"] + 1.0 or h["edge_step_db"] > 3.0:
                    problems.append(f"{tag} 『{h['text']}』の伸ばしの HNR/クリック/端 {h['hnr_min']:.1f} {h['click_splice']:.1f} {h['edge_step_db']:.1f}")
            if "B_tail_pitch" in r:
                for nm in ("harvest", "acf"):
                    if r["B_tail_pitch"][f"slope_cents_per_s_{nm}"] > (0.0 if vn != "flat" else 1.5):
                        problems.append(f"{tag}: 流れ版の余韻の傾き")
                if abs(r["B_total_s"] - b["B_total_s"]) > 0.05:
                    problems.append(f"{tag}: 流れ版の総尺が変わった")
    (out / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=1, default=float), "utf-8")
    write_readme(out, metrics, keys, args.poem)
    if problems:
        print("PROBLEMS:\n" + "\n".join(problems))
        sys.exit(1)
    print("all checks passed")


def write_readme(out: Path, metrics: dict, keys: list, poem: int) -> None:
    lab = next(k for k in metrics if k.startswith(f"{poem:02d}_"))
    L = [f"競技ルール版のピッチの修正（v5 の試聴サンプル）", "",
         "「最後が少しピッチが上がっていくように聞こえる」への対処です。書き出した WAV を読み戻し、F0 を harvest + stonemask（5 ms 刻み）と",
         "自己相関法の 2 通りで測っています（50 ms 窓の中央値、cent は基準の F0 に対する 1200 log2(F0/基準)）。", "",
         "測ってわかったこと（修正前 = v4）:",
         "  * 余韻そのものの F0 は上がっていない。むしろ全体で -85 cent 下がる（-40 cent/秒）。切り替えの段差もなく（切り替えの F0 は自然な母音と 1 cent 以内）、",
         "    ドリフトの符号・単位も正しい。音色（重心・1 kHz 以上の割合）も明るくなっていない。ただし平らなはずの 1 秒の間に既に -22 cent 下がり、",
         "    ビブラート（±8 cent）とジッタで、平らな部分より最大 +8 cent 高い窓がある。",
         "  * 上がっていたのは、句の途中の伸ばし。front の窓から back の窓へ F0 をなだらかに結んでいたため、back が高いと伸ばしの間ずっと上昇する:",
         "    上の句『の』+37 cent、『の』+42 cent、下の句『は』+25 cent（傾き +30〜+140 cent/秒）。17番歌の『ず』『た』『わ』は +21〜+31 cent、序歌の『に』『な』『と』は +18〜+25 cent。",
         "    下の句の中の『は』などの上昇が、ほかの伸ばしの上昇とともに「だんだん上がっていく」印象を作り、",
         "    余韻でもそれが続いて聞こえた可能性が高い（余韻の F0 自体は下降だったので、耳で確かめてほしい）。余韻は平ら → ゆるやかな下降に作り直した。", ""]
    for lb, recs in metrics.items():
        b = recs["before"]
        L.append(f"【{lb}】")
        for vn, name in (("before", "修正前（v4）"), ("after", "修正（平ら → -60 cent）"), ("flat", "修正_平ら（下げない）"),
                         ("strong", "修正_強め（-100 cent）")):
            if vn not in recs:
                continue
            r = recs[vn]
            tp = r["tail_pitch"]
            L.append(f"  {name}: {r['file']}（全体 {r['total_s']:.2f} 秒）")
            L.append(f"    余韻: 傾き {tp['slope_cents_per_s_harvest']:+.1f} / {tp['slope_cents_per_s_acf']:+.1f} cent/秒（harvest / 自己相関）、"
                     f"終わり 0.5 秒の F0 {tp['last0.5s_cents_harvest']:+.0f} / {tp['last0.5s_cents_acf']:+.0f} cent、"
                     f"終わり 1 秒 {tp['last1s_cents_harvest']:+.0f} / {tp['last1s_cents_acf']:+.0f} cent（母音の頭 0.1〜0.2 秒に対して）、")
            L.append(f"          平らな部分より高い窓の最大 {tp['max_above_hold_cents_harvest']:+.1f} / {tp['max_above_hold_cents_acf']:+.1f} cent、"
                     f"どこかで上がった量の最大 {tp['max_upmove_cents_harvest']:.1f} / {tp['max_upmove_cents_acf']:.1f} cent、"
                     f"音色: 重心 {tp['centroid_hz_first']:.0f} → {tp['centroid_hz_last']:.0f} Hz（傾き {tp['centroid_slope_hz_per_s']:+.1f} Hz/秒）、"
                     f"1 kHz 以上の割合 {tp['hi_db_first']:.1f} → {tp['hi_db_last']:.1f} dB")
            L.append("          F0 の道筋（cent、母音の頭に対して）: " + "、".join(f"{k} 秒 {v:+.0f}" for k, v in tp["curve_cents"].items()))
            tm = r["tail"]
            L.append(f"          聞こえている終わり {tm['audible_s']:.2f} 秒、HNR 最小 {tm['hnr_deep_min']:.0f} dB、クリック（接ぎ目 {tm['click_splice']:.1f} / 母音の中 {tm['click_body']:.1f}）、"
                     f"ピーク {r['peak_dbfs']:.1f} dBFS、クリッピング {r['n_clip']}")
            for h in r["holds"]:
                L.append(f"    伸ばし {'上の句' if h['part'] == 'kami' else '下の句'}『{h['text']}』({h['vowel_s']:.2f} 秒): "
                         f"範囲の終わりの F0 {h['end_cents_harvest']:+.0f} / {h['end_cents_acf']:+.0f} cent、傾き {h['slope_cents_per_s_harvest']:+.0f} cent/秒、"
                         f"頭より高い窓の最大 {h['max_above_start_cents_harvest']:+.0f} cent、終わりの 120 ms の上向きのはね {h['flick_cents']:+.0f} cent、"
                         f"HNR 最小 {h['hnr_min']:.0f} dB、接ぎ目のクリック {h['click_splice']:.1f}")
        L.append("")
    L += ["作り方の変更（pitch_mode=anchored）:",
          "  余韻: 切り替え窓の終わり 40 ms の、自然な母音の F0 に合わせて持続音を始め（段差なし）、減衰の開始（母音の開始から 1.0 秒）までは完全に平ら、",
          "        そこから総尺（3.0 秒）まで、コサインのなめらかな形で -60 cent（_平ら は 0、_強め は -100）だけ下げる。上がる所はない。",
          "        ビブラートは ±3 cent の平均ゼロで、下降の間に 0 へ。ジッタは 1.5 cent で、ゆっくりした上昇を含まない（以前は ±8 cent と、累積する ±3 cent のゆらぎ）。",
          "        音色は固定（スペクトル包絡は切り替え窓のまま。高域が明るくなっていく変化はない）。",
          "  伸ばした母音の中: F0 は front の窓の高さで平らに保つ（back のほうが低ければ、そこへゆるやかに下げる）。back のほうが高くても上げない。",
          "        後ろの窓のあとも、元の尾の音量の形に合わせた平らな F0 の合成音で母音の終わりまで続け、元の波形（F0 が上がっていることがある）には戻らない",
          "        （v5 では最後の約 30 ms で元へ戻し、最大 +179 cent 上向きにはねていた）。",
          "時間配分（kanaStartMs・総尺・上の句 / 下の句 / 余韻）・音量は修正前と同じです。",
          "再現: generate_all.py --yoin-mode short --timing rule --yoin-short-tail-s 2.8 --yoin-short-dim-start-s 1.0 --yoin-short-end-s 3.0"
          " --pitch-mode anchored --yoin-fall-cents -60",
          "このフォルダの作成: tools/voice/make_pitch_fix_samples.py"]
    (out / "README.txt").write_text("\n".join(L) + "\n", "utf-8")


if __name__ == "__main__":
    main()
