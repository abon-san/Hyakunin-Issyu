#!/usr/bin/env python3
"""1 番歌の試聴サンプル（v4）: 「遅めの Dim」2 種と「競技ルール」の時間配分を作り、測って確かめる。

    tools/voice-src/venv/bin/python -I tools/voice/make_dim_rule_samples.py OUT_DIR [--poem 1]

OUT_DIR に次を作る（音声はコミットしない）:
    01_あきのたの_長さ3.wav        長さ3（--yoin-short-tail-s 0.8、Dim は余韻の頭から）= v3 と同じ。比較用
    01_あきのたの_Dim遅め1.wav     同じ長さ（聞こえている終わり 0.8 秒）で、Dim を 0.25 秒から
    01_あきのたの_Dim遅め2.wav     同じ長さで、Dim を 0.40 秒から
    01_あきのたの_競技ルール.wav    上の句 6.0 秒 → 間合い 1.0 秒 → 下の句 5.0 秒 → 余韻 3.0 秒
    01_あきのたの_競技ルール_流れ.wav  下の句 + 余韻 3.0 秒 → 間合い 1.0 秒 → 上の句 6.0 秒（試合で聞こえる並び）
    README.txt, metrics.json
上の 3 つは 上の句 + 0.86 秒 + 下の句（余韻つき）。書き出した WAV を読み戻して測る。
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
import make_tail_samples as mts  # noqa: E402
import rule_timing as rt  # noqa: E402
import voicevox_karuta as vk  # noqa: E402
import yoin  # noqa: E402

warnings.filterwarnings("ignore")
SR = vk.SAMPLE_RATE

# 遅めの Dim（長さ3 と同じ聞こえている終わり 0.8 秒で、減衰の開始だけ遅らせる）
DIM_VARIANTS = [("長さ3", 0.0), ("Dim遅め1", 0.25), ("Dim遅め2", 0.40)]
TAIL_S = 0.8
# 競技ルール版の余韻: 1.0 秒まで平ら → 2.8 秒で -40 dB → 3.0 秒で 0（母音の開始から）
RULE_TAIL = dict(yoin_short_tail_s=2.8, yoin_short_dim_start_s=1.0, yoin_short_end_s=3.0, yoin_drift_ln_short=-0.07)


def env_db(x: np.ndarray, win_s: float = 0.01) -> np.ndarray:
    n = int(win_s * SR)
    k = len(x) // n
    return 10 * np.log10((x[: k * n].reshape(k, n) ** 2).mean(1) + 1e-12)


def active_span(x: np.ndarray, lo_s: float, hi_s: float, thr_db: float = -50.0) -> tuple[float, float]:
    """x の [lo_s, hi_s) で 10 ms RMS が thr_db(dBFS) を超える最初と最後の窓の端（秒）。"""
    e = env_db(x)
    a, b = int(lo_s * 100), int(hi_s * 100)
    idx = np.nonzero(e[a:b] > thr_db)[0]
    return (a + idx[0]) / 100, (a + idx[-1] + 1) / 100


def measure_tail(y: np.ndarray, i0: int, info: dict, v0: float, seg_end: int) -> dict:
    m = yoin.tail_metrics(y[:seg_end], i0, info["f0"], info["splice_s"] - v0, info["splice_end_s"] - v0)
    m.update(yoin.dim_metrics(y[:seg_end], i0, info["splice_end_s"] - v0, info["dim_start_s"]))
    e = yoin.level_track_db(y[i0:seg_end], 0.02, 0.005)
    tc = np.arange(len(e)) * 0.005 + 0.01
    pk = e.max()
    m["curve_db"] = {f"{t:.2f}": float(np.interp(t, tc, e) - pk) for t in (0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.5, 2.0, 2.5, 2.8)
                     if t <= tc[-1]}
    return m


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    ap.add_argument("--poem", type=int, default=1)
    ap.add_argument("--v3", type=Path, help="v3 の 長さ3 の WAV（あれば、ビット単位で同じか確かめる）")
    args = ap.parse_args()
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    for old in list(out.glob("*.wav")) + [out / "README.txt", out / "metrics.json"]:
        old.unlink(missing_ok=True)
    n = args.poem
    poems, nobashi = kr.load_poems(), kr.load_nobashi()
    jobs = {j.part: j for j in ga.build_jobs(poems, nobashi, [n], False, False)}
    name = f"{n:02d}_{ga.first_ku(poems[n]['kamiYomi'])}"
    eng = vk.Engine()
    st = eng.find_style("冥鳴ひまり", "ノーマル")
    assert st.id == 14
    qrs = {p: eng.build_query(j.kanji, j.reading, st.id) for p, j in jobs.items()}

    def render(part: str, rules: kr.KarutaRules) -> kr.Clip:
        jb, qr = jobs[part], qrs[part]
        plan = kr.plan_verse(qr, part, jb.reading, jb.marks, jb.oyama, rules)
        clip = kr.render_clip(eng, qr, plan, st.id, rules, jb.short)
        if rules.timing == "default":
            kr.check_plan_vs_query(clip, qr.query, rules)
        else:
            rt.check_rule_clip(clip, rules)
        if part == "shimo":
            kr.check_yoin(clip, rules)
        return clip

    metrics: dict = {}
    problems: list[str] = []
    base = dataclasses.replace(kr.PACE_REF, yoin_mode="short", yoin_short_tail_s=TAIL_S)

    # ------------------------------------------------------------ 1) 長さ3 / Dim遅め1 / Dim遅め2
    kami0 = render("kami", base)
    ref_clip = None
    for label, dim in DIM_VARIANTS:
        rules = dataclasses.replace(base, yoin_short_dim_start_s=dim)
        shimo = render("shimo", rules)
        x, starts = mts.compose(kami0.samples, shimo.samples, rules.trial_gap_s, False)
        path = out / f"{name}_{label}.wav"
        vk.save_wav(path, x)
        y = mts.read_wav(path)
        v0 = shimo.spans[-1][1]
        i0 = starts[0] + int(round(v0 * SR))
        m = measure_tail(y, i0, shimo.yoin_info, v0, starts[0] + len(shimo.samples))
        rec = dict(file=path.name, seconds=len(y) / SR, dim_param=dim, tail_param=TAIL_S,
                   splice_end_from_v0=shimo.yoin_info["splice_end_s"] - v0, **m)
        if ref_clip is None:
            ref_clip = (shimo, y)
        else:
            sp = int(round(shimo.yoin_info["splice_s"] * SR))
            b = starts[0] + sp
            rec["identical_before_splice_to_len3"] = bool(np.array_equal(y[:b], ref_clip[1][:b]))
            rec["kana_ms_same"] = shimo.kana_ms == ref_clip[0].kana_ms
            rec["total_same"] = len(shimo.samples) == len(ref_clip[0].samples)
            if not (rec["identical_before_splice_to_len3"] and rec["kana_ms_same"] and rec["total_same"]):
                problems.append(f"{path.name}: 長さ3 と同一でない部分がある")
        if args.v3 and label == "長さ3":
            v3 = mts.read_wav(args.v3)
            rec["identical_to_v3"] = bool(len(v3) == len(y) and np.array_equal(v3, y))
            if not rec["identical_to_v3"]:
                problems.append("長さ3 が v3 と同一でない")
        # 判定
        if abs(m["audible_s"] - TAIL_S) > 0.05:
            problems.append(f"{path.name}: 聞こえている終わり {m['audible_s']:.3f}")
        if m["max_rise_db"] > (1.5 if dim else 0.5):
            problems.append(f"{path.name}: 減衰が単調でない +{m['max_rise_db']:.2f}")
        if m["smooth_max_rise_db"] > 0.1:
            problems.append(f"{path.name}: Dim の後が単調でない +{m['smooth_max_rise_db']:.2f}")
        if m["hnr_deep_min"] < 12:
            problems.append(f"{path.name}: HNR {m['hnr_deep_min']:.1f}")
        if m["click_splice"] > 1.5 * m["click_body"] + 1.0:
            problems.append(f"{path.name}: クリック {m['click_splice']:.1f} vs {m['click_body']:.1f}")
        if m["n_clip"] or m["peak_dbfs"] > -0.9 or np.abs(y).max() >= 10 ** (-0.9 / 20):
            problems.append(f"{path.name}: クリッピング")
        metrics[path.name] = rec
        print(f"{path.name:32s} {len(y) / SR:6.2f}s audible {m['audible_s']:.3f} dim(-1dB) {m['t_minus1']:.3f} "
              f"-3dB {m['t_minus3']:.3f} plateau_range {m['plateau_range_db']:.2f} HNR min {m['hnr_deep_min']:.1f} "
              f"rise {m['max_rise_db']:+.2f}/{m['smooth_max_rise_db']:+.2f} click {m['click_splice']:.1f}/{m['click_body']:.1f}",
              flush=True)

    # ------------------------------------------------------------ 2) 競技ルール
    rrules = dataclasses.replace(kr.PACE_REF, yoin_mode="short", timing="rule", **RULE_TAIL)
    rk, rs = render("kami", rrules), render("shimo", rrules)
    k0, k1 = rk.spans[0][0], rk.spans[-1][2]            # 上の句: 最初のモーラの開始 〜 最後のモーラの終わり
    s0_, sv0 = rs.spans[0][0], rs.spans[-1][1]          # 下の句: 最初のモーラの開始 〜 最後の母音の開始
    ka, kb = int(round(k0 * SR)), int(round(k1 * SR))
    sa = int(round(s0_ * SR))
    ma = np.zeros(int(round(rrules.rule_ma_s * SR)))
    kami_part = rk.samples[:kb]                         # 先頭の無音（pre）は残し、最後のモーラの終わりで切る
    shimo_part = rs.samples[sa:]
    fileA = np.concatenate([kami_part, ma, shimo_part])
    lead = np.zeros(int(round(0.2 * SR)))
    fileB = np.concatenate([lead, shimo_part, ma, rk.samples[ka:kb]])
    pa, pb = out / f"{name}_競技ルール.wav", out / f"{name}_競技ルール_流れ.wav"
    vk.save_wav(pa, fileA)
    vk.save_wav(pb, fileB)
    ya, yb = mts.read_wav(pa), mts.read_wav(pb)

    rec: dict = dict(params=dict(rrules_tail=RULE_TAIL, kami_s=rrules.rule_kami_s, shimo_s=rrules.rule_shimo_s,
                                 first_mora_s=rrules.rule_first_mora_s, ku_pause_s=rrules.rule_ku_pause_s,
                                 ma_s=rrules.rule_ma_s, src_cands=list(rrules.rule_hold_src_cands)))
    # --- 各部分の長さ（作り方から）
    rec["kami_s_model"] = rk.verse_s
    rec["shimo_s_model"] = rs.verse_s
    rec["yoin_s_model"] = rs.yoin_s
    rec["alpha_kami"], rec["alpha_shimo"] = rk.yoin_info.get("alpha"), rs.yoin_info.get("alpha")
    # --- 書き出した WAV から（信号）: 上の句の最初と最後の音、下の句の最初の音、余韻の聞こえている終わり
    A_kami = active_span(ya, 0.0, (len(kami_part) / SR) + 0.001)
    gap0 = len(kami_part) / SR
    A_shimo_on = active_span(ya, gap0 + 0.5, len(ya) / SR)[0]
    rec["A_kami_onset_s"], rec["A_kami_end_s"] = A_kami
    rec["A_kami_signal_s"] = A_kami[1] - A_kami[0]
    rec["A_ma_signal_s"] = A_shimo_on - A_kami[1]          # 上の句の最後の音 〜 下の句の最初の音
    ia0 = len(kami_part) + len(ma)                          # 下の句の先頭サンプル（ファイル内）
    off_a = ia0 / SR - s0_                                  # 下の句クリップの時刻 + off_a = ファイルの時刻
    i0a = ia0 + int(round(sv0 * SR)) - sa
    ma_ = measure_tail(ya, i0a, rs.yoin_info, sv0, len(ya))
    rec["A_tail"] = ma_
    rec["A_shimo_signal_s"] = (i0a / SR - A_shimo_on)       # 下の句の最初の音 〜 最後の母音の開始（信号の最初の音から）
    rec["A_shimo_to_audible_end_s"] = (i0a / SR + ma_["audible_s"]) - A_shimo_on
    rec["A_total_s"] = len(ya) / SR
    rec["A_yoin_file_s"] = len(ya) / SR - i0a / SR
    # 初句・ポーズ・伸ばし
    first = [(round(e - s, 3)) for (s, _, e), u in zip(rk.spans, rk.qr.units) if u.ku == 0]
    rec["first_ku_mora_s"] = first
    pauses = []
    for clip in (rk, rs):
        for (s, _, e), (s2, _, _), u, u2 in zip(clip.spans, clip.spans[1:], clip.qr.units, clip.qr.units[1:]):
            if u2.ku != u.ku:
                pauses.append(dict(part=clip.plan.kind, after_ku=u.ku + 1, s=round(s2 - e, 3)))
    rec["ku_pauses"] = pauses
    rec["holds"] = {"kami": rk.holds, "shimo": rs.holds}
    rec["hold_measures"] = {"kami": rt.measure_holds(ya, rk, 0.0),
                            "shimo": rt.measure_holds(ya, rs, off_a)}
    # 上の句クリップの先頭は file A と同じ座標（切り取りは後ろだけ）なので offset 0
    # --- 流れ
    off_b_shimo = len(lead) / SR - s0_
    ib0 = len(lead)
    i0b = ib0 + int(round(sv0 * SR)) - sa
    rec["B_tail"] = measure_tail(yb, i0b, rs.yoin_info, sv0, ib0 + len(shimo_part))
    yoin_end_b = (ib0 + len(shimo_part)) / SR
    kami_on_b = (ib0 + len(shimo_part) + len(ma)) / SR
    B_on = active_span(yb, yoin_end_b + 0.5, len(yb) / SR)
    rec["B_total_s"] = len(yb) / SR
    rec["B_shimo_onset_s"] = active_span(yb, 0.0, 1.0)[0]
    rec["B_yoin_end_file_s"] = yoin_end_b
    rec["B_kami_onset_s"], rec["B_kami_end_s"] = B_on
    rec["B_kami_signal_s"] = B_on[1] - B_on[0]
    rec["B_ma_construct_s"] = kami_on_b - yoin_end_b
    # 余韻の音が聞こえている終わり（-40 dB）から上の句の最初の音まで
    rec["B_ma_from_audible_end_s"] = B_on[0] - (i0b / SR + rec["B_tail"]["audible_s"])
    # --- 判定
    for what, val, tgt in (("上の句(モデル)", rec["kami_s_model"], 6.0), ("下の句(モデル)", rec["shimo_s_model"], 5.0),
                           ("余韻", rec["yoin_s_model"], 3.0), ("上の句(信号)", rec["A_kami_signal_s"], 6.0),
                           ("間合い(信号)", rec["A_ma_signal_s"], 1.0), ("上の句(流れ,信号)", rec["B_kami_signal_s"], 6.0),
                           ("間合い(流れ,構成)", rec["B_ma_construct_s"], 1.0)):
        if abs(val - tgt) > 0.15:
            problems.append(f"{what} {val:.3f} (目標 {tgt})")
    if abs(rec["A_yoin_file_s"] - 3.0) > 0.01:
        problems.append(f"余韻のファイル内の長さ {rec['A_yoin_file_s']:.3f}")
    # 下の句の信号上の長さ: 最初の音 〜 聞こえている終わり = 下の句 + 余韻の聞こえている長さ
    exp_shimo_audible = rs.verse_s + ma_["audible_s"]
    rec["A_shimo_audible_expected_s"] = exp_shimo_audible
    if abs(rec["A_shimo_to_audible_end_s"] - exp_shimo_audible) > 0.15:
        problems.append(f"下の句の信号上の長さ {rec['A_shimo_to_audible_end_s']:.3f} vs {exp_shimo_audible:.3f}")
    for tag, tm in (("A", rec["A_tail"]), ("B", rec["B_tail"])):
        if abs(tm["audible_s"] - 2.8) > 0.05:
            problems.append(f"{tag} 余韻の聞こえている終わり {tm['audible_s']:.3f}")
        if tm["max_rise_db"] > 1.5 or tm["smooth_max_rise_db"] > 0.1:
            problems.append(f"{tag} 余韻が単調でない {tm['max_rise_db']:.2f}/{tm['smooth_max_rise_db']:.2f}")
        if tm["hnr_deep_min"] < 12:
            problems.append(f"{tag} 余韻の HNR {tm['hnr_deep_min']:.1f}")
        if tm["click_splice"] > 1.5 * tm["click_body"] + 1.0:
            problems.append(f"{tag} 余韻のクリック")
    for part, ms in rec["hold_measures"].items():
        for h in ms:
            if h["hnr_min"] < 12:
                problems.append(f"{part} 『{h['text']}』の伸ばしの HNR {h['hnr_min']:.1f}")
            if h["click_splice"] > 1.5 * h["click_body"] + 1.0:
                problems.append(f"{part} 『{h['text']}』の伸ばしにクリック {h['click_splice']:.1f}/{h['click_body']:.1f}")
            if h["edge_step_db"] > 3.0:
                problems.append(f"{part} 『{h['text']}』の伸ばしの端でレベルが飛ぶ {h['edge_step_db']:.1f} dB")
            if h["f0_max_step_cents"] > 120:
                problems.append(f"{part} 『{h['text']}』の伸ばしでピッチが飛ぶ {h['f0_max_step_cents']:.0f} cent")
    # つなぎ目（上の句の切り口・下の句の切り口）の振幅
    rec["join_amp"] = dict(A_kami_cut=float(np.abs(ya[len(kami_part) - 24: len(kami_part)]).max()),
                           A_shimo_start=float(np.abs(ya[ia0: ia0 + 24]).max()),
                           B_kami_cut=float(np.abs(yb[-24:]).max()))
    if max(rec["join_amp"].values()) > 0.01:
        problems.append(f"つなぎ目の振幅が大きい {rec['join_amp']}")
    for nm, y in (("競技ルール", ya), ("競技ルール_流れ", yb)):
        if (np.abs(np.round(y * 32767)) >= 32767).any() or np.abs(y).max() >= 10 ** (-0.9 / 20):
            problems.append(f"{nm}: クリッピング")
    rec["peak_dbfs"] = dict(A=float(20 * np.log10(np.abs(ya).max())), B=float(20 * np.log10(np.abs(yb).max())))
    metrics[pa.name] = rec

    print(f"競技ルール: 上の句 モデル {rec['kami_s_model']:.3f}s 信号 {rec['A_kami_signal_s']:.3f}s / 間合い {rec['A_ma_signal_s']:.3f}s / "
          f"下の句 モデル {rec['shimo_s_model']:.3f}s (信号: 最初の音〜最後の母音 {rec['A_shimo_signal_s']:.3f}s) / "
          f"余韻 {rec['yoin_s_model']:.3f}s (聞こえている終わり {ma_['audible_s']:.3f}s) / 全体 {rec['A_total_s']:.2f}s")
    print(f"流れ: 全体 {rec['B_total_s']:.2f}s 上の句(信号) {rec['B_kami_signal_s']:.3f}s 間合い(構成) {rec['B_ma_construct_s']:.3f}s "
          f"(余韻の聞こえている終わりから {rec['B_ma_from_audible_end_s']:.3f}s)")
    for part, ms in rec["hold_measures"].items():
        for h in ms:
            print(f"  hold {part} {h['text']}: HNR min {h['hnr_min']:.1f} mean {h['hnr_mean']:.1f} click {h['click_splice']:.1f}/{h['click_body']:.1f} "
                  f"edge {h['edge_step_db']:.2f} dB f0 step {h['f0_max_step_cents']:.0f} cent xcorr {h['xcorr_front']:.2f}/{h['xcorr_back']:.2f}")

    (out / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=1, default=float), "utf-8")
    write_readme(out, name, metrics, rrules, base)
    if problems:
        print("PROBLEMS:\n" + "\n".join(problems))
        sys.exit(1)
    print("all checks passed")


def write_readme(out: Path, name: str, metrics: dict, rrules: kr.KarutaRules, base: kr.KarutaRules) -> None:
    M = {k: v for k, v in metrics.items()}
    r = M[f"{name}_競技ルール.wav"]
    tA, tB = r["A_tail"], r["B_tail"]
    kc = r["holds"]["kami"]
    sc = r["holds"]["shimo"]
    hm = r["hold_measures"]

    def dim_line(label):
        m = M[f"{name}_{label}.wav"]
        return m

    L3, D1, D2 = (dim_line(l) for l in ("長さ3", "Dim遅め1", "Dim遅め2"))
    lines = [
        "1番歌の試聴サンプル（v4）: 遅めの Dim と 競技ルールの時間配分",
        "",
        "【1. 遅めの Dim（長さ3 の長さで、Dim だけ遅く）】",
        "どれも 上の句 + 0.86 秒の無音 + 下の句（余韻つき）。違いは、下の句の最後の母音（余韻）で、音が小さくなり始める時刻だけです。",
        "上の句、下の句の最後の母音の途中まで、総尺（最後の母音の開始から 3.0 秒）、アプリ用のタイミング（kanaStartMs）は3つとも同じです",
        "（長さ3 とビット単位で同じ部分を確かめてあります。長さ3 は前回の v3 と同一です）。",
        "最後の母音は、きれいな短い母音を WORLD の持続音につなぎ、自然な小さなピッチの下降とビブラートを保ったまま、",
        "平らに保ってから、なめらかに（だんだん速く）小さくしています。小さくなる曲線は dB ∝ 時間^1.25 で、始まりの傾きは 0 です。",
        "「聞こえている終わり」= 最後の母音のピークより 40 dB 小さくなる時刻（母音の開始から）。3つとも約 0.8 秒です。",
        "「Dim の開始」= 平らな部分の高さより 1 dB 下がり、以後戻らない時刻（ビブラートの揺れをならして測定）。",
        "",
    ]
    for label, desc, m in (("長さ3", "Dim は母音の頭から（比較用）", L3), ("Dim遅め1", "平らに保つのは 0.25 秒まで", D1),
                           ("Dim遅め2", "平らに保つのは 0.40 秒まで", D2)):
        cv = m["curve_db"]
        lines += [
            f"_{label}  （{desc}。yoin_short_tail_s = {m['tail_param']:g}、yoin_short_dim_start_s = {m['dim_param']:g}）",
            f"    Dim の開始（-1 dB）{m['t_minus1']:.2f} 秒 / -3 dB {m['t_minus3']:.2f} 秒 / -10 dB {m['t_minus10']:.2f} 秒 / -20 dB {m['t_minus20']:.2f} 秒 / -30 dB {m['t_minus30']:.2f} 秒",
            f"    聞こえている終わり {m['audible_s']:.2f} 秒（細かい窓 {m['audible_fine_s']:.2f} 秒）。平らな部分の揺れ（切り替え〜Dim 開始）{m['plateau_range_db']:.1f} dB（ビブラートに連動した ±0.6 dB 程度の揺れ）。",
            f"    ピークからのレベル: " + "、".join(f"{t} 秒 {v:.0f} dB" for t, v in cv.items() if float(t) <= 0.8) + "。",
            f"    声の質: 持続部分の HNR は最小 {m['hnr_tail_min']:.0f} dB（平均 {m['hnr_tail_mean']:.0f} dB）で雑音のかたまりなし。切り替えのクリックなし"
            f"（接ぎ目 {m['click_splice']:.1f} / 母音の中 {m['click_body']:.1f}）、クリッピングなし。"
            f"減衰は単調（Dim 開始後のならしたレベルの上昇 {m['smooth_max_rise_db']:+.2f} dB 以下）。", ""]
    lines += [
        "【2. 競技ルール（5・3・1・6 方式）】",
        "全日本かるた協会の読手テキストの規則に、全体の時間配分を合わせたバージョンです（1番歌のみ）。",
        "  下の句 約 5 秒 / 余韻 3.0 秒 / 間合い 1.0 秒 / 上の句 約 6 秒。初句の五字は切らずに 1 字およそ 0.2 秒。",
        "合わせ方: 子音と、印のない母音はエンジンの既定のまま（全体をゆっくりにはしていません）。初句の4字だけ 1 字 0.2 秒に",
        "そろえ、伸ばしの印と句末のモーラの母音を長くして、上の句 6.0 秒・下の句 5.0 秒に合わせました。句の間のポーズは 0.40 秒です。",
        "長さの配分は、現行ルールの伸ばしの長さの比（句末 1.0、上の句の終わりの前 0.56、下の句の中 0.94、その他 0.78）に従い、",
        "小さい印（ｰ）にはさらに 0.7 倍を掛けています。",
        "長い母音の作り方: VOICEVOX は約 0.3 秒より長い母音を雑音の床に崩すので、短い母音（0.12〜0.25）をエンジンに作らせ、",
        "その母音の内側の、周期的で安定した前後 2 つの窓の間だけを、同じ窓から取ったスペクトル包絡・非周期性の WORLD 合成音",
        "（包絡は前の窓から後ろの窓へ連続的に変化、F0 は両端の値を結び、自然な小さなビブラート）に置き換えて延ばしています。",
        "母音の立ち上がりと次の子音への渡りは元の波形のままで、接ぎ目は位相をそろえて 25 ms でクロスフェードしています",
        "（あらーみ の ら、わがー の が のように、後ろに子音が続く母音も同じ方法）。余韻は 1. と同じ方法です。",
        "",
        f"_競技ルール.wav  上の句 → 間合い 1.0 秒 → 下の句 → 余韻   （全体 {r['A_total_s']:.2f} 秒）",
        f"  上の句: {r['kami_s_model']:.2f} 秒（最初のモーラの開始 〜 最後のモーラの終わり。書き出した信号で測っても {r['A_kami_signal_s']:.2f} 秒）  目標 6.0 秒",
        f"  間合い: 上の句の最後の音 〜 下の句の最初の音 {r['A_ma_signal_s']:.2f} 秒（作り方では 1.00 秒）  目標 1.0 秒",
        f"  下の句: {r['shimo_s_model']:.2f} 秒（最初のモーラの開始 〜 最後の母音の開始。余韻を除く）  目標 5.0 秒",
        f"  余韻: {r['yoin_s_model']:.2f} 秒（最後の母音の開始 〜 ファイルの終わり）、うち聞こえている（-40 dB まで）のは {tA['audible_s']:.2f} 秒",
        f"        Dim の開始（-1 dB）{tA['t_minus1']:.2f} 秒、-10 dB {tA['t_minus10']:.2f} 秒、-20 dB {tA['t_minus20']:.2f} 秒、-30 dB {tA['t_minus30']:.2f} 秒、0 になるのは 3.0 秒。",
        f"        HNR 最小 {tA['hnr_deep_min']:.0f} dB（平均 {tA['hnr_tail_mean']:.0f} dB）。減衰は単調（ならしたレベルの上昇 {tA['smooth_max_rise_db']:+.2f} dB 以下）。",
        f"  初句の各モーラ: " + "、".join(f"{v:.2f}" for v in r["first_ku_mora_s"][:-1]) + f" 秒（最後の「の」は句末の伸ばし）",
        "  句の間のポーズ: " + "、".join(f"{p['s']:.2f}" for p in r["ku_pauses"]) + " 秒",
        "  伸ばしの母音の長さ（元の短い母音 → 延ばしたあと）:",
    ]
    for part, hs in (("上の句", kc), ("下の句", sc)):
        for h in hs:
            lines.append(f"    {part} 『{h['text']}』 {h['src_s']:.2f} → {h['vowel_s']:.2f} 秒")
    lines += ["  伸ばした所の検査（書き出した信号）:"]
    for part, ms in (("上の句", hm["kami"]), ("下の句", hm["shimo"])):
        for h in ms:
            lines.append(f"    {part} 『{h['text']}』 HNR 最小 {h['hnr_min']:.0f} / 平均 {h['hnr_mean']:.0f} dB、接ぎ目のクリック {h['click_splice']:.1f}（母音の中 {h['click_body']:.1f}）、"
                         f"端のレベル差 {h['edge_step_db']:.1f} dB、ピッチの最大の飛び {h['f0_max_step_cents']:.0f} cent")
    lines += [
        "",
        f"_競技ルール_流れ.wav  前の札の下の句（デモは1番歌の下の句）+ 余韻 3.0 秒 → 間合い 1.0 秒 → 上の句 6 秒   （全体 {r['B_total_s']:.2f} 秒）",
        f"  下の句 5.0 秒 + 余韻 3.0 秒（聞こえている終わりは {tB['audible_s']:.2f} 秒）→ 間合い 1.0 秒（余韻のファイルの終わりから上の句の最初の音まで {r['B_ma_construct_s']:.2f} 秒。",
        f"  余韻の音が聞こえなくなる（-40 dB）時点からだと {r['B_ma_from_audible_end_s']:.2f} 秒）→ 上の句 {r['B_kami_signal_s']:.2f} 秒（信号で測定）。先頭に 0.2 秒の無音。",
        "",
        "注意: 規則が定める間合い 1.0 秒は、本来「余韻のあと、次の札の上の句の前」です。_競技ルール.wav は1首の中で聞き比べるために、",
        "上の句と下の句の間にも同じ 1.0 秒を入れています（実際の試合では、上の句の後の間は読手の息づかいの長さです）。",
        "",
        "全体の検査: クリッピングなし（ピーク " + f"{r['peak_dbfs']['A']:.1f} / {r['peak_dbfs']['B']:.1f} dBFS）、"
        "つなぎ目（上の句の切り口・下の句の頭）の振幅はほぼ 0。",
        "",
        "再現（100首すべてに使う場合）:",
        "  長さ3        : generate_all.py --yoin-mode short --yoin-short-tail-s 0.8",
        "  Dim遅め1     : 上に --yoin-short-dim-start-s 0.25",
        "  Dim遅め2     : 上に --yoin-short-dim-start-s 0.40",
        "  競技ルール   : generate_all.py --yoin-mode short --timing rule --yoin-short-tail-s 2.8 --yoin-short-dim-start-s 1.0"
        " --yoin-short-end-s 3.0 --yoin-drift-ln-short -0.07",
        "  このフォルダの作成: tools/voice/make_dim_rule_samples.py",
    ]
    (out / "README.txt").write_text("\n".join(lines) + "\n", "utf-8")


if __name__ == "__main__":
    main()
