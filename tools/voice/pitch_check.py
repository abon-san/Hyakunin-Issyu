#!/usr/bin/env python3
"""余韻・伸ばした母音のピッチ（F0）が「上がっていない」ことを測る。

F0 は pyworld の harvest + stonemask（5 ms 刻み）と、自己相関法（40 ms 窓、放物線補間）の 2 通りで測り、
50 ms 窓の中央値（cent）で比べる。cent は基準の F0 に対する 1200 * log2(F0 / 基準)。

    tail_pitch(x, i0, audible_s, dim_start_s)   余韻（x[i0:] が最後の母音の開始から）
    hold_pitch(x, t0, t1)                       伸ばした母音（置き換えた範囲 [t0, t1] 秒）
"""
from __future__ import annotations

import numpy as np
import pyworld as pw

SR = 24000


def f0_harvest(x: np.ndarray, t0: float, t1: float, fp: float = 5.0) -> tuple[np.ndarray, np.ndarray]:
    a, b = max(0, int(t0 * SR)), min(len(x), int(t1 * SR))
    seg = x[a:b].astype(np.float64)
    f0, t = pw.harvest(seg, SR, f0_floor=150, f0_ceil=450, frame_period=fp)
    f0 = pw.stonemask(seg, f0, t, SR)
    return t + a / SR, f0


def f0_acf(x: np.ndarray, t0: float, t1: float, fp: float = 5.0, win: float = 0.04) -> tuple[np.ndarray, np.ndarray]:
    """自己相関（窓の自己相関で正規化）で 150〜450 Hz の最大の山を放物線補間。戻り値: (時刻, F0)。"""
    w = int(win * SR)
    lo, hi = int(SR / 450), int(SR / 150)
    han = np.hanning(w)
    wr = np.fft.irfft(np.abs(np.fft.rfft(han, 2 * w)) ** 2)[:w]
    ts = np.arange(t0 + win / 2, t1 - win / 2, fp / 1000)
    out = []
    for t in ts:
        c = int(round(t * SR))
        seg = x[c - w // 2: c + w // 2]
        if len(seg) < w:
            out.append(0.0)
            continue
        seg = (seg - seg.mean()) * han
        r = np.fft.irfft(np.abs(np.fft.rfft(seg, 2 * w)) ** 2)[:w]
        r = r / (wr + 1e-12)
        r = r / (r[0] + 1e-18)
        k = int(np.argmax(r[lo:hi + 1])) + lo
        if lo < k < hi:
            y0, y1, y2 = r[k - 1], r[k], r[k + 1]
            d = y0 - 2 * y1 + y2
            k = k + (0.5 * (y0 - y2) / d if d != 0 else 0.0)
        out.append(SR / k)
    return ts, np.array(out)


def win_cents(t: np.ndarray, f: np.ndarray, centers: np.ndarray, ref: float, win: float = 0.05) -> np.ndarray:
    """centers の各時刻の、±win/2 の有声フレームの F0 の中央値（ref に対する cent）。無ければ nan。"""
    out = np.full(len(centers), np.nan)
    for i, c in enumerate(centers):
        m = (np.abs(t - c) <= win / 2) & (f > 0)
        if m.sum() >= 3:
            out[i] = 1200 * np.log2(np.median(f[m]) / ref)
    return out


def _med(t, f, a, b) -> float:
    m = (t >= a) & (t < b) & (f > 0)
    return float(np.median(f[m]))


def max_upmove(c: np.ndarray) -> float:
    """後の窓が前の窓より高い量の最大（cent）。= 「どこかで上がった量」。単調に下がるなら 0 以下。"""
    c = c[~np.isnan(c)]
    best, run_min = 0.0, np.inf
    for v in c:
        run_min = min(run_min, v)
        best = max(best, v - run_min)
    return float(best)


def fit_slope(tc: np.ndarray, c: np.ndarray) -> float:
    m = ~np.isnan(c)
    return float(np.polyfit(tc[m], c[m], 1)[0]) if m.sum() > 2 else 0.0


def brightness(x: np.ndarray, t: float, win: float = 0.08) -> tuple[float, float]:
    """時刻 t の、(スペクトル重心 [Hz, 80 Hz 以上], 1 kHz 以上のエネルギーの割合 [dB]。全体に対して)。"""
    c, h = int(round(t * SR)), int(win * SR / 2)
    s = x[c - h: c + h] * np.hanning(2 * h)
    S = np.abs(np.fft.rfft(s, 8192)) ** 2
    f = np.fft.rfftfreq(8192, 1 / SR)
    m = (f > 80) & (f < 11000)
    cen = float((f[m] * S[m]).sum() / (S[m].sum() + 1e-30))
    hi = float(10 * np.log10(S[(f >= 1000) & (f < 11000)].sum() / (S[m].sum() + 1e-30) + 1e-12))
    return cen, hi


def tail_pitch(x: np.ndarray, i0: int, audible_s: float, dim_start_s: float = 1.0) -> dict:
    """余韻のピッチ。x[i0:] が最後の母音の開始から。audible_s: 聞こえている終わり（-40 dB）。
    基準: 母音の 0.10〜0.20 秒の F0（hold 以前の、WORLD の持続の始まり。= 母音の頭の高さ）。
    hold_hz: 0.25〜(dim_start - 0.1) 秒の F0 の中央値（平らな部分の高さ）。"""
    t0 = i0 / SR
    th, fh = f0_harvest(x, t0 + 0.05, t0 + audible_s + 0.02)
    ta, fa = f0_acf(x, t0 + 0.05, t0 + audible_s + 0.02)
    ref = _med(th, fh, t0 + 0.10, t0 + 0.20)
    hold = _med(th, fh, t0 + 0.25, t0 + max(0.4, dim_start_s - 0.1))
    ctr = t0 + np.arange(0.15, audible_s - 0.02, 0.025)
    ch = win_cents(th, fh, ctr, ref)
    ca = win_cents(ta, fa, ctr, ref)
    rel = ctr - t0
    out: dict = dict(ref_hz=ref, hold_hz=hold, hold_cents=1200 * np.log2(hold / ref))
    for name, c in (("harvest", ch), ("acf", ca)):
        out[f"slope_cents_per_s_{name}"] = fit_slope(rel[rel >= 0.25], c[rel >= 0.25])
        out[f"max_above_hold_cents_{name}"] = float(np.nanmax(c[rel >= 0.25]) - out["hold_cents"])
        out[f"max_upmove_cents_{name}"] = max_upmove(c[rel >= 0.25])
        for dur in (0.5, 1.0):
            m = (rel >= audible_s - dur) & (rel <= audible_s - 0.03)
            out[f"last{dur:g}s_cents_{name}"] = float(np.nanmedian(c[m]))
        out[f"end_cents_{name}"] = float(np.nanmedian(c[rel >= audible_s - 0.15]))
    # 全体の動き（0.1 秒ごと、harvest）
    out["curve_cents"] = {f"{r:.1f}": float(np.nanmedian(ch[np.abs(rel - r) <= 0.05])) for r in np.arange(0.2, audible_s, 0.2)}
    # 音色: 重心と 1 kHz 以上の割合の推移（0.5 秒ごと）。ピッチが下がると重心も少し下がる
    cen, hi = zip(*[brightness(x, t0 + r) for r in np.arange(0.2, audible_s - 0.05, 0.1)])
    r_ = np.arange(0.2, audible_s - 0.05, 0.1)
    out["centroid_hz_first"], out["centroid_hz_last"] = float(np.mean(cen[:3])), float(np.mean(cen[-3:]))
    out["centroid_slope_hz_per_s"] = float(np.polyfit(r_, cen, 1)[0])
    out["hi_db_first"], out["hi_db_last"] = float(np.mean(hi[:3])), float(np.mean(hi[-3:]))
    out["hi_db_slope_per_s"] = float(np.polyfit(r_, hi, 1)[0])
    return out


def hold_pitch(x: np.ndarray, t0: float, t1: float) -> dict:
    """伸ばした母音（置き換えた範囲 [t0, t1] 秒、x の時刻。no_rise では母音の終わりまで）のピッチ。基準 = 範囲の最初の 25%（クロスフェードの後）。
    end_cents: 範囲の最後の 25%（最後の 10 ms を除く）の F0。
    flick_cents: 終わりの上向きのはね。最後の 120 ms（最後の 10 ms を除く）の 30 ms 窓の中央値（5 ms 刻み）の最大 - その前 150 ms の中央値
    （cent）。音量が範囲の最大より 12 dB 以内の有声フレームだけ。伸ばした所の終わりの 30 ms も含めて測る。"""
    a, b = t0 + 0.035, t1 - 0.01
    th, fh = f0_harvest(x, t0 - 0.03, t1 + 0.03)
    ta, fa = f0_acf(x, t0 - 0.03, t1 + 0.03)
    e = np.array([10 * np.log10((x[int(c * SR) - 240: int(c * SR) + 240] ** 2).mean() + 1e-14) for c in th])
    top = e[(th >= t0) & (th <= t1)].max()
    fh_g = np.where(e > top - 12, fh, 0.0)
    q = (b - a) / 4
    ref = _med(th, fh, a, a + max(q, 0.05))
    ctr = np.arange(a + 0.025, b - 0.025, 0.025)
    out: dict = dict(ref_hz=ref, body_s=b - a)
    for name, (t, f) in (("harvest", (th, fh)), ("acf", (ta, fa))):
        c = win_cents(t, f, ctr, ref)
        out[f"slope_cents_per_s_{name}"] = fit_slope(ctr - a, c)
        out[f"max_above_start_cents_{name}"] = float(np.nanmax(c))
        out[f"max_upmove_cents_{name}"] = max_upmove(c)
        out[f"end_cents_{name}"] = float(np.nanmedian(c[ctr >= b - max(q, 0.05)]))
    # 終わりのはね（verifier と同じ定義）
    vend = t1 - 0.01
    m_ref = (th >= vend - 0.27) & (th < vend - 0.12) & (fh_g > 0)
    flick = 0.0
    if m_ref.sum() >= 5:
        r0 = np.median(fh_g[m_ref])
        for cc in np.arange(vend - 0.12 + 0.015, vend - 0.01, 0.005):
            mm = (np.abs(th - cc) <= 0.015) & (fh_g > 0)
            if mm.sum() >= 3:
                flick = max(flick, 1200 * np.log2(np.median(fh_g[mm]) / r0))
    out["flick_cents"] = float(flick)
    out["join_rise_cents"] = float(flick)      # 以前の名前（README 用）
    return out
