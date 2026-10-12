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
                  ap_gain: float = 1.0, ap_floor: float = 0.0, xfade_s: float = 0.03, seed: int = 0
                  ) -> tuple[np.ndarray, int, dict]:
    """x の最後の母音を WORLD で sustain_s 秒（母音の開始 v0 から数えて）まで持続させる。

    戻り値: (y, n_valid, info)。y は x の先頭から持続音の終わりまで（振幅は平らなまま。減衰は呼び出し側）。
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
