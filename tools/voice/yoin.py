#!/usr/bin/env python3
"""下の句の最後の母音を「余韻」として伸ばす（WORLD による持続音）。

VOICEVOX は vowel_length を 0.5 秒より長くすると、母音の途中が約 20 dB 小さい雑音（HNR 0〜3 dB）に
崩れ、終わり際にだけ音が戻る（調査: tools/voice の v2 の記録を参照）。そこで、きれいに出る短い母音
（vowel_length 0.25 前後）を VOICEVOX に作らせ、その母音の安定した部分のスペクトル包絡・非周期性を
WORLD (pyworld) で持続させる。切り替え点は位相をそろえてクロスフェードする。

    sustain_vowel(x, v0, v1, level_ln, ...) -> (y, info)
        x: 24 kHz モノの波形（float）。v0, v1: 最後の母音の開始・終わり（秒）。level_ln: ln F0（クエリの高さ）。
        y: x の先頭から、持続音がつながり、total_s の長さまで（余韻の減衰・無音込み）の波形。
"""
from __future__ import annotations

import numpy as np
import pyworld as pw

SR = 24000
FRAME_MS = 5.0


def _rms_db(x: np.ndarray) -> float:
    return 10 * np.log10(float((x ** 2).mean()) + 1e-12)


def _periodicity(seg: np.ndarray, period: int) -> float:
    """周期 period (±3 サンプル) での自己相関の最大値。"""
    n = len(seg) - period - 8
    if n <= 8:
        return 0.0
    u = seg[:n]
    best = -1.0
    for k in range(-3, 4):
        v = seg[period + k + 3: period + k + 3 + n]
        best = max(best, float(np.dot(u, v) / (np.linalg.norm(u) * np.linalg.norm(v) + 1e-12)))
    return best


def hnr_db(seg: np.ndarray, f0: float, win_s: float = 0.03, hop_s: float = 0.01) -> float:
    """自己相関による HNR（dB）の平均。既知の F0 の周期で測る簡易版。"""
    T = int(round(SR / f0))
    w = int(win_s * SR)
    vals = []
    for s in range(0, max(1, len(seg) - w - T - 8), int(hop_s * SR)):
        r = min(max(_periodicity(seg[s: s + w + T + 8], T), 1e-3), 0.999)
        vals.append(10 * np.log10(r / (1 - r)))
    return float(np.mean(vals)) if vals else float("nan")


def find_anchor(x: np.ndarray, v0: float, v1: float, level_ln: float, win_s: float = 0.05
                ) -> tuple[int, int]:
    """母音 [v0, v1) の中で、周期性が高く、大きく、安定している窓 (開始サンプル, 長さ) を選ぶ。"""
    T = int(round(SR / float(np.exp(level_ln))))
    lo, hi = v0 + 0.03, v0 + (v1 - v0) * 0.75
    hop, w = int(0.005 * SR), int(win_s * SR)
    cands = []
    for s in range(int(lo * SR), int(hi * SR) - w, hop):
        seg = x[s: s + w]
        cands.append((s, _rms_db(seg), _periodicity(seg, T)))
    if not cands:
        raise ValueError("母音が短すぎて持続の基準窓が取れない")
    c = np.array(cands)
    ok = (c[:, 1] >= c[:, 1].max() - 6) & (c[:, 2] > 0.85)
    if not ok.any():
        ok = c[:, 2] >= np.sort(c[:, 2])[-max(3, len(c) // 5)]
    score = c[:, 2] * 10 + c[:, 1] * 0.2
    score[~ok] = -1e9
    i = int(np.argmax(score))
    return int(c[i, 0]), w


def sustain_vowel(x: np.ndarray, v0: float, v1: float, level_ln: float, sustain_s: float, *,
                  drift_ln: float = -0.05, vibrato_cents: float = 8.0, vibrato_hz: float = 5.2,
                  vibrato_delay_s: float = 0.3, jitter_cents: float = 3.0, tremolo_db: float = 0.6,
                  ap_gain: float = 1.0, ap_floor: float = 0.0, xfade_s: float = 0.03, seed: int = 0,
                  match_level: bool = False) -> tuple[np.ndarray, int, dict]:
    """x の最後の母音を WORLD で sustain_s 秒（母音の開始 v0 から数えて）まで持続させる。

    戻り値: (y, n_valid, info)。y は x の先頭から持続音の終わりまで（振幅は平らなまま。減衰は呼び出し側）。
    match_level=True なら、持続音の大きさを「切り替え窓の終わり 12 ms の x の RMS」に合わせる（基準窓の平均ではなく）。
    x がすでに減衰し始めている母音に接ぐとき、切り替えで音が大きくならないようにするため。
    """
    rng = np.random.default_rng(seed)
    s0, w = find_anchor(x, v0, v1, level_ln)
    pad = int(0.08 * SR)
    A, B = max(0, s0 - pad), min(len(x), s0 + w + pad)
    seg = x[A:B].astype(np.float64)
    f0, t = pw.harvest(seg, SR, f0_floor=100, f0_ceil=500, frame_period=FRAME_MS)
    sel = (t >= (s0 - A) / SR) & (t < (s0 + w - A) / SR)
    fv = f0[sel][f0[sel] > 0]
    f0c = float(np.median(fv)) if len(fv) else float(np.exp(level_ln))
    f0_for = np.where(f0 > 0, f0, f0c)
    sp = pw.cheaptrick(seg, f0_for, t, SR)
    ap = pw.d4c(seg, f0_for, t, SR)
    spm = np.exp(np.log(sp[sel] + 1e-20).mean(0))
    apm = np.clip(ap[sel].mean(0) * ap_gain + ap_floor, 1e-3, 0.999)

    t_start = (s0 + w - int(xfade_s * SR)) / SR      # 切り替えの開始時刻（x の先頭から）
    n_sus = sustain_s - t_start + v0                # 切り替え後の持続音の長さ
    lead = 0.1                                      # 位相合わせのために先に作っておく長さ
    n = int(round((n_sus + lead + 0.05) * 1000 / FRAME_MS)) + 1
    tt = np.arange(n) * FRAME_MS / 1000
    dur = max(n_sus, 1e-3)
    lnf = np.log(f0c) + drift_ln * np.clip((tt - lead) / dur, 0, 1)
    vib = np.sin(2 * np.pi * vibrato_hz * tt + rng.uniform(0, 2 * np.pi))
    vib_ramp = np.clip((tt - lead + t_start - v0 - vibrato_delay_s) / 0.4, 0, 1)   # ビブラートは遅れて入る
    lnf += (vibrato_cents / 1200 * np.log(2)) * vib * vib_ramp
    walk = np.cumsum(rng.normal(0, 1, n))
    walk -= np.linspace(walk[0], walk[-1], n)
    lnf += walk / (walk.std() + 1e-9) * (jitter_cents / 1200 * np.log(2))
    y = pw.synthesize(np.exp(lnf), np.tile(spm, (n, 1)), np.tile(apm, (n, 1)), SR, FRAME_MS)
    # 振幅の揺れ（ビブラートに連動した小さなトレモロ）
    trem = np.interp(np.arange(len(y)) / SR, tt, vib * vib_ramp)
    y = y * 10 ** (tremolo_db * trem / 20)
    ref = x[s0: s0 + w]
    k0 = int(0.12 * SR)
    y *= np.sqrt(float((ref ** 2).mean()) / (float((y[k0: k0 + w] ** 2).mean()) + 1e-20))
    # 位相合わせ: x の切り替え窓に y のどの位置が一番合うか
    L = int(xfade_s * SR)
    sp_ = s0 + w - L
    T = int(round(SR / f0c))
    tgt = x[sp_: sp_ + L]
    center = int(lead * SR)
    best, bs = -9.0, center
    for sh in range(center - 3 * T, center + 3 * T):
        if sh < 0:
            continue
        c = float(np.dot(tgt, y[sh: sh + L]) / (np.linalg.norm(tgt) * np.linalg.norm(y[sh: sh + L]) + 1e-12))
        if c > best:
            best, bs = c, sh
    ys = y[bs:]
    if match_level:
        k = int(0.012 * SR)
        r = np.sqrt(float((x[sp_ + L - k: sp_ + L] ** 2).mean()) / (float((ys[L - k: L] ** 2).mean()) + 1e-20))
        ys = ys * float(np.clip(r, 0.5, 2.0))
    out = np.concatenate([x[:sp_], np.zeros(len(ys))])
    ramp = np.linspace(0.0, 1.0, L)
    out[sp_: sp_ + L] = x[sp_: sp_ + L] * (1 - ramp) + ys[:L] * ramp
    out[sp_ + L:] = ys[L:]
    info = dict(anchor_s=s0 / SR, splice_s=sp_ / SR, f0=f0c, xcorr=best, splice_len_s=xfade_s)
    return out, sp_ + L, info


def decay_envelope(n: int, t0: float, flat_s: float, decay_db: float, end_s: float, taper_s: float
                   ) -> np.ndarray:
    """持続音の振幅。t0 秒（配列の先頭からの時刻の基準）から数えて flat_s 秒は平ら、
    そのあと end_s 秒までは dB に直線的（= 指数）に decay_db だけ下がり、最後は taper_s のコサインで 0 に。
    戻り値は長さ n の配列（サンプル単位、先頭が時刻 0）。"""
    t = np.arange(n) / SR
    d = np.clip((t - flat_s) / max(end_s - flat_s, 1e-6), 0, 1)
    g = 10 ** (-decay_db * d / 20)
    tp = np.clip((end_s - t) / taper_s, 0, 1)
    g *= 0.5 * (1 - np.cos(np.pi * tp))
    g[t >= end_s] = 0.0
    return g


# ---------------------------------------------------------------- 「短い」余韻の延長（yoin_mode="short" + yoin_short_tail_s）

AUDIBLE_DB = 40.0     # 「聞こえている」= 母音のピークの RMS より 40 dB 以内


def level_track_db(x: np.ndarray, win_s: float = 0.02, hop_s: float = 0.005) -> np.ndarray:
    """win_s 窓・hop_s 刻みの RMS（dB）。i 番目は x[i*hop : i*hop+win] の窓。"""
    n, h = int(round(win_s * SR)), int(round(hop_s * SR))
    k = (len(x) - n) // h + 1
    if k <= 0:
        return np.zeros(0)
    idx = np.arange(k)[:, None] * h + np.arange(n)[None, :]
    return 10 * np.log10((x[idx] ** 2).mean(1) + 1e-12)


def audible_len_s(x: np.ndarray, i0: int, *, floor_db: float = AUDIBLE_DB, win_s: float = 0.02,
                  hop_s: float = 0.005) -> tuple[float, float, float]:
    """x[i0:]（最後の母音の開始から）で、ピーク RMS より floor_db 以内で聞こえている最後の時刻。
    戻り値: (その窓の中心の時刻[秒, i0 から], ピーク窓の中心の時刻, ピーク dB)。"""
    e = level_track_db(x[i0:], win_s, hop_s)
    pk = float(e.max())
    last = int(np.nonzero(e >= pk - floor_db)[0][-1])
    return last * hop_s + win_s / 2, int(np.argmax(e)) * hop_s + win_s / 2, pk


def short_tail_gain(t: np.ndarray, ts: float, tau40: float, power: float, plateau_s: float,
                    end_db: float, taper_s: float) -> np.ndarray:
    """切り替えの終わり（時刻 ts）からの減衰の振幅（倍率）。plateau_s の間は 1、そのあと
    -AUDIBLE_DB * ((t-ts-plateau)/tau40) ** power [dB]。power>1 は、減衰が穏やかに始まり、だんだん速くなる
    （自然な声の終わり方）。-end_db に達した所で 0 にする（その前 taper_s はコサインで 0 へ）。"""
    tau = np.maximum(t - ts - plateau_s, 0.0)
    d = -AUDIBLE_DB * (tau / tau40) ** power
    g = 10 ** (d / 20)
    t_end = ts + plateau_s + tau40 * (end_db / AUDIBLE_DB) ** (1 / power)
    tp = np.clip((t_end - t) / taper_s, 0, 1)
    g *= 0.5 * (1 - np.cos(np.pi * tp))
    g[t >= t_end] = 0.0
    return g


def short_tail(x: np.ndarray, v0: float, v1: float, level_ln: float, tail_s: float, *,
               power: float = 1.25, plateau_s: float = 0.0, end_db: float = 78.0, taper_s: float = 0.04,
               tol_s: float = 0.008, **sustain_kw) -> tuple[np.ndarray, dict]:
    """x の最後の母音の「聞こえている長さ」を tail_s 秒（母音の開始から。AUDIBLE_DB 以内）にして返す。

    きれいな短い母音 x を、安定部のスペクトル・非周期性を持つ WORLD の持続音に切り替え点で接ぎ（sustain_vowel）、
    接いだ所から（plateau_s ≤ 0.1 秒の平らな部分のあと）穏やかに始まる指数的な減衰をかける。減衰の速さ
    (tau40) は、測った聞こえている長さが tail_s に合うまで繰り返して決める（ビブラートの揺れで窓の測定が
    数 ms 動くので、tol_s 以内、無ければ最良）。戻り値: (y, info)。
    y の長さは母音の開始 + 1.8 * tail_s + 0.1 秒（呼び出し側が総尺に合わせる）。"""
    assert 0.0 <= plateau_s <= 0.1, "平らな部分は 0.1 秒まで"
    sustain_s = 1.8 * tail_s + 0.3
    y, n_valid, sinfo = sustain_vowel(x, v0, v1, level_ln, sustain_s, match_level=True, **sustain_kw)
    i0 = int(round(v0 * SR))
    ts = n_valid / SR - v0
    assert tail_s > ts + plateau_s + 0.05, f"目標の長さ {tail_s} 秒が、切り替えの終わり {ts:.3f} 秒より短い"
    end_max = 1.8 * tail_s + 0.1
    n_out = i0 + int(round(end_max * SR))
    y = y[:n_out] if len(y) >= n_out else np.pad(y, (0, n_out - len(y)))
    t = np.arange(len(y) - i0) / SR
    base = y[i0:].copy()

    def make(tau40: float) -> np.ndarray:
        z = y.copy()
        z[i0:] = base * short_tail_gain(t, ts, tau40, power, plateau_s, end_db, taper_s)
        return z

    tau = tail_s - ts - plateau_s
    best = (9.0, None, None, None)
    for _ in range(12):
        z = make(tau)
        L, _, _ = audible_len_s(z, i0)
        if abs(L - tail_s) < best[0]:
            best = (abs(L - tail_s), z, tau, L)
        if abs(L - tail_s) <= tol_s:
            break
        tau *= (tail_s - ts - plateau_s) / max(L - ts - plateau_s, 0.05)
    err, z, tau, L = best
    if err > 3 * tol_s:
        raise RuntimeError(f"聞こえている長さが {tail_s} 秒に合わない: {L:.3f}")
    t_end = ts + plateau_s + tau * (end_db / AUDIBLE_DB) ** (1 / power)
    assert t_end < end_max - 0.01, "減衰の終わりが持続音より後"
    info = dict(sinfo, v0_s=v0, splice_end_s=n_valid / SR, tail_target_s=tail_s, tail_measured_s=L,
                tau40_s=tau, plateau_s=plateau_s, power=power, decay_end_s=t_end, sound_s=t_end)
    return z, info


def hnr_local_db(seg: np.ndarray, f0: float, win_s: float = 0.04, hop_s: float = 0.02, span: float = 0.1
                 ) -> np.ndarray:
    """窓ごとの HNR（dB）。周期は f0 の周期の ±span の範囲で自己相関が最大の所を探す（F0 の下降・ビブラート対策）。"""
    w, h = int(win_s * SR), int(hop_s * SR)
    T0 = SR / f0
    lags = range(int(T0 * (1 - span)), int(T0 * (1 + span)) + 1)
    out = []
    for s in range(0, len(seg) - w - max(lags) - 1, h):
        u = seg[s: s + w]
        best = 0.0
        for lag in lags:
            v = seg[s + lag: s + lag + w]
            best = max(best, float(np.dot(u, v) / (np.linalg.norm(u) * np.linalg.norm(v) + 1e-12)))
        r = min(max(best, 1e-3), 0.999)
        out.append(10 * np.log10(r / (1 - r)))
    return np.array(out)


def click_ratio(seg: np.ndarray, half_s: float = 0.003) -> np.ndarray:
    """1 サンプル差分の絶対値 / その周り ±half_s の差分の RMS。クリックは周りよりずっと大きく出る。"""
    d = np.diff(seg)
    k = int(half_s * SR)
    box = np.ones(2 * k + 1) / (2 * k + 1)
    loc = np.sqrt(np.convolve(d ** 2, box, mode="same") + 1e-18)
    return np.abs(d) / loc


def tail_metrics(x: np.ndarray, i0: int, f0: float, splice_rel_s: float, splice_end_rel_s: float) -> dict:
    """余韻（x[i0:] は最後の母音の開始から）を測る。x は書き出す PCM と同じ値（-1〜1）。
    audible_s: ピークより 40 dB 以内で聞こえている長さ（20 ms 窓・5 ms 刻み）、audible_fine_s: 10 ms 窓・2.5 ms 刻み。
    max_rise_db: ピークから聞こえている終わりまでで、20 ms 前より大きくなった最大量（20 ms 窓・5 ms 刻み）。
    hnr_tail_*: 切り替えの終わりから聞こえている終わり -0.03 秒まで（持続音の部分）の HNR の最小・平均、
    hnr_deep_min: 同じく -50 dB 以内まで、hnr_all_min: 母音の開始から（自然な母音の部分も含む）。
    splice_rel_s, splice_end_rel_s: 切り替えの開始・終わりの時刻（最後の母音の開始から）。
    click_*: splice は切り替えの両端 ±2 ms での click_ratio の最大、body はそれ以外の最大。"""
    seg = x[i0:]
    L, tpk, pk = audible_len_s(x, i0)
    Lf, _, _ = audible_len_s(x, i0, win_s=0.01, hop_s=0.0025)
    e = level_track_db(seg)
    ip = int(round((tpk - 0.01) / 0.005))
    ie = int(round((L - 0.01) / 0.005))
    d = e[ip + 4: ie + 1] - e[ip: ie + 1 - 4]
    L50, _, _ = audible_len_s(x, i0, floor_db=50.0)
    sa = int(splice_end_rel_s * SR)
    hn = hnr_local_db(seg[sa: int((L - 0.03) * SR)], f0)
    hd = hnr_local_db(seg[sa: int((L50 - 0.03) * SR)], f0)
    ha = hnr_local_db(seg[: int((L - 0.03) * SR)], f0)
    cr = click_ratio(seg[: int((L + 0.2) * SR)])
    mask = np.ones(len(cr), bool)
    for tt in (splice_rel_s, splice_end_rel_s):
        c = int(tt * SR)
        mask[max(0, c - 48): c + 48] = False        # ±2 ms を splice 領域とする
    spl = ~mask
    return dict(audible_s=L, audible_fine_s=Lf, peak_s=tpk, peak_db=pk, max_rise_db=float(d.max()) if len(d) else 0.0,
                hnr_tail_min=float(hn.min()), hnr_tail_mean=float(hn.mean()), hnr_deep_min=float(hd.min()),
                hnr_all_min=float(ha.min()),
                click_splice=float(cr[spl].max()), click_body=float(cr[mask].max()),
                peak_dbfs=float(20 * np.log10(np.abs(x).max() + 1e-12)),
                n_clip=int((np.abs(np.round(x * 32767)) >= 32767).sum()))
