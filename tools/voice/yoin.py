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


def _jitter_ln(n: int, rng: np.random.Generator, cents: float, frame_ms: float = FRAME_MS) -> np.ndarray:
    """平均ゼロのジッタ（ln F0）。25 ms の移動平均でなめらかにしたノイズから、0.3 秒の移動平均（= ゆっくりした
    ゆらぎ・上昇）を引く。標準偏差は cents。"""
    z = rng.normal(0, 1, n + 200)
    k1, k2 = max(1, int(round(25 / frame_ms))), max(3, int(round(300 / frame_ms)))
    z = np.convolve(z, np.ones(k1) / k1, "same") - np.convolve(z, np.ones(k2) / k2, "same")
    z = z[100:100 + n]
    return z / (z.std() + 1e-9) * (cents / 1200 * np.log(2))


def sustain_vowel(x: np.ndarray, v0: float, v1: float, level_ln: float, sustain_s: float, *,
                  drift_ln: float = -0.05, vibrato_cents: float = 8.0, vibrato_hz: float = 5.2,
                  vibrato_delay_s: float = 0.3, jitter_cents: float = 3.0, tremolo_db: float = 0.6,
                  ap_gain: float = 1.0, ap_floor: float = 0.0, xfade_s: float = 0.03, seed: int = 0,
                  match_level: bool = False, fall_cents: float | None = None, fall_start_s: float = 1.0,
                  fall_end_s: float | None = None) -> tuple[np.ndarray, int, dict]:
    """x の最後の母音を WORLD で sustain_s 秒（母音の開始 v0 から数えて）まで持続させる。

    戻り値: (y, n_valid, info)。y は x の先頭から持続音の終わりまで（振幅は平らなまま。減衰は呼び出し側）。
    match_level=True なら、持続音の大きさを「切り替え窓の終わり 12 ms の x の RMS」に合わせる（基準窓の平均ではなく）。
    x がすでに減衰し始めている母音に接ぐとき、切り替えで音が大きくならないようにするため。

    fall_cents が None でないとき（「ピッチを上げない」モード。従来の drift_ln は使わない）:
      * F0 の基準は、切り替え窓の終わり 40 ms の、自然な母音の F0（harvest + stonemask）。切り替えで段差を作らない。
      * 母音の開始から fall_start_s 秒までは完全に平ら、そこから fall_end_s 秒まで、コサインのなめらかな形で fall_cents
        （負の値、cent）だけ下がる。決して上がらない。
      * ビブラートは平均ゼロで、下降の間に 0 まで小さくする。ジッタは低い周波数のゆらぎ（ゆっくりした上昇）を含まない。
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
    if fall_cents is not None:
        f0 = pw.stonemask(seg, f0, t, SR)
        sel_end = (t >= (s0 + w - A) / SR - 0.04) & (t < (s0 + w - A) / SR)
        fe = f0[sel_end][f0[sel_end] > 0]
        if len(fe):
            f0c = float(np.median(fe))
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
    vib = np.sin(2 * np.pi * vibrato_hz * tt + rng.uniform(0, 2 * np.pi))
    vib_ramp = np.clip((tt - lead + t_start - v0 - vibrato_delay_s) / 0.4, 0, 1)   # ビブラートは遅れて入る
    if fall_cents is None:
        lnf = np.log(f0c) + drift_ln * np.clip((tt - lead) / dur, 0, 1)
        lnf += (vibrato_cents / 1200 * np.log(2)) * vib * vib_ramp
        walk = np.cumsum(rng.normal(0, 1, n))
        walk -= np.linspace(walk[0], walk[-1], n)
        lnf += walk / (walk.std() + 1e-9) * (jitter_cents / 1200 * np.log(2))
    else:
        assert fall_cents <= 0.0, "fall_cents は 0 以下（ピッチを上げない）"
        tau = tt - lead + t_start - v0                    # 母音の開始からの時刻
        fe_s = fall_end_s if fall_end_s is not None else sustain_s
        u = np.clip((tau - fall_start_s) / max(fe_s - fall_start_s, 1e-3), 0, 1)
        ease = 0.5 - 0.5 * np.cos(np.pi * u)              # 0 → 1、始まりも終わりも傾き 0
        lnf = np.log(f0c) + (fall_cents / 1200 * np.log(2)) * ease
        lnf += (vibrato_cents / 1200 * np.log(2)) * vib * vib_ramp * (1 - ease)
        lnf += _jitter_ln(n, rng, jitter_cents) * (1 - ease)
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
    info = dict(anchor_s=s0 / SR, splice_s=sp_ / SR, f0=f0c, xcorr=best, splice_len_s=xfade_s, fall_cents=fall_cents)
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
                    end_db: float, taper_s: float, end_s: float | None = None) -> np.ndarray:
    """切り替えの終わり（時刻 ts）からの減衰の振幅（倍率）。plateau_s の間は 1、そのあと
    -AUDIBLE_DB * ((t-ts-plateau)/tau40) ** power [dB]。power>1 は、減衰が穏やかに始まり、だんだん速くなる
    （自然な声の終わり方）。-end_db に達した所で 0 にする（その前 taper_s はコサインで 0 へ）。
    end_s を与えると、0 にする時刻をそこに固定する（end_db は無視。長い余韻を総尺ちょうどで消すため）。"""
    tau = np.maximum(t - ts - plateau_s, 0.0)
    d = -AUDIBLE_DB * (tau / tau40) ** power
    g = 10 ** (d / 20)
    t_end = (ts + plateau_s + tau40 * (end_db / AUDIBLE_DB) ** (1 / power)) if end_s is None else end_s
    tp = np.clip((t_end - t) / taper_s, 0, 1)
    g *= 0.5 * (1 - np.cos(np.pi * tp))
    g[t >= t_end] = 0.0
    if end_s is not None:
        g[t >= t_end - 1.5 / SR] = 0.0       # end_s が総尺のときも、最後のサンプルがちょうど 0 になるように
    return g


def short_tail(x: np.ndarray, v0: float, v1: float, level_ln: float, tail_s: float, *,
               power: float = 1.25, plateau_s: float = 0.0, dim_start_s: float | None = None,
               end_s: float | None = None, end_db: float = 78.0, taper_s: float = 0.04,
               tol_s: float = 0.008, fall_cents: float | None = None, **sustain_kw) -> tuple[np.ndarray, dict]:
    """x の最後の母音の「聞こえている長さ」を tail_s 秒（母音の開始から。AUDIBLE_DB 以内）にして返す。

    きれいな短い母音 x を、安定部のスペクトル・非周期性を持つ WORLD の持続音に切り替え点で接ぎ（sustain_vowel）、
    接いだ所から（plateau_s の平らな部分のあと）穏やかに始まる指数的な減衰をかける。減衰の速さ
    (tau40) は、測った聞こえている長さが tail_s に合うまで繰り返して決める（ビブラートの揺れで窓の測定が
    数 ms 動くので、tol_s 以内、無ければ最良）。戻り値: (y, info)。
    dim_start_s: 減衰の開始を「母音の開始から」この秒数にする（plateau_s = dim_start_s - 切り替えの終わり。
        None なら plateau_s をそのまま使う）。「遅めの Dim」= 平らに保ってから落とす。
    end_s: 減衰が 0 になる時刻（母音の開始から）。None なら -end_db に達した所。長い余韻は end_s を総尺に合わせる。
    fall_cents: None でなければ「ピッチを上げない」モード（sustain_vowel を参照）。F0 は切り替えの F0 から、減衰の開始
        （dim_start_s。無ければ plateau_s + 0.2）まで平ら、そこから減衰の終わりまで fall_cents（負）だけ下がる。
    y の長さは母音の開始 + (end_s があればそれ、無ければ 1.8 * tail_s) + 0.1 秒（呼び出し側が総尺に合わせる）。"""
    assert 0.0 <= plateau_s <= 2.0, "平らな部分は 2 秒まで"
    sustain_s = (end_s + 0.3) if end_s is not None else 1.8 * tail_s + 0.3
    if fall_cents is not None:
        sustain_kw.update(fall_cents=fall_cents,
                          fall_start_s=dim_start_s if dim_start_s is not None else plateau_s + 0.2,
                          fall_end_s=end_s if end_s is not None else 1.8 * tail_s)
    y, n_valid, sinfo = sustain_vowel(x, v0, v1, level_ln, sustain_s, match_level=True, **sustain_kw)
    i0 = int(round(v0 * SR))
    ts = n_valid / SR - v0
    if dim_start_s is not None:
        plateau_s = dim_start_s - ts
        assert plateau_s >= 0.0, f"減衰の開始 {dim_start_s} 秒が、切り替えの終わり {ts:.3f} 秒より早い"
    assert tail_s > ts + plateau_s + 0.05, f"目標の長さ {tail_s} 秒が、減衰の開始 {ts + plateau_s:.3f} 秒より短い"
    if end_s is not None:
        assert end_s > tail_s, "end_s は tail_s より後"
    end_max = (end_s + 0.1) if end_s is not None else 1.8 * tail_s + 0.1
    n_out = i0 + int(round(end_max * SR))
    y = y[:n_out] if len(y) >= n_out else np.pad(y, (0, n_out - len(y)))
    t = np.arange(len(y) - i0) / SR
    base = y[i0:].copy()

    def make(tau40: float) -> np.ndarray:
        z = y.copy()
        z[i0:] = base * short_tail_gain(t, ts, tau40, power, plateau_s, end_db, taper_s, end_s)
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
    if best[0] > tol_s:
        # 反復が行き来するとき（尾のレベルが窓の RMS のゆらぎで平らになり、聞こえている終わりが飛ぶ）は、最良の tau のまわりを細かく探す
        tau0 = best[2]
        for f in np.linspace(0.97, 1.03, 25):
            z = make(tau0 * f)
            L, _, _ = audible_len_s(z, i0)
            if abs(L - tail_s) < best[0]:
                best = (abs(L - tail_s), z, tau0 * f, L)
    err, z, tau, L = best
    if err > 6 * tol_s:
        raise RuntimeError(f"聞こえている長さが {tail_s} 秒に合わない: {L:.3f}")
    t_end = end_s if end_s is not None else ts + plateau_s + tau * (end_db / AUDIBLE_DB) ** (1 / power)
    assert t_end < end_max - 0.01, "減衰の終わりが持続音より後"
    info = dict(sinfo, v0_s=v0, splice_end_s=n_valid / SR, tail_target_s=tail_s, tail_measured_s=L,
                tau40_s=tau, plateau_s=plateau_s, dim_start_s=ts + plateau_s, power=power,
                decay_end_s=t_end, sound_s=t_end)
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
    ip = max(int(round((tpk - 0.01) / 0.005)), int(round(splice_end_rel_s / 0.005)))   # 切り替えより前は自然な母音（前のモーラの尾・凹みを含む）
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


def dim_metrics(x: np.ndarray, i0: int, ts_rel: float, dim_rel: float, *, smooth_s: float = 0.19) -> dict:
    """遅めの Dim の実測。x[i0:] は最後の母音の開始から。ts_rel: 切り替えの終わり、dim_rel: 設計上の減衰の開始（どちらも母音の開始から）。
    level は 20 ms RMS（dB）を、ビブラートの 1 周期（smooth_s）の移動平均でならしたもの。
    ref_db: 切り替えの終わりから減衰の開始までの最大レベル（平らな部分の高さ）。
    plateau_range_db: 同じ区間の（ならす前の）最大 - 最小。
    t_minus{N}: ref より N dB 下がり、以後戻らない最初の時刻（母音の開始から）。
    smooth_max_rise_db: 減衰の開始（dim_rel）から聞こえている終わりまでの、ならしたレベルの 5 ms ごとの上昇の最大
    （0 に近ければ、平らな部分のあとは単調に下がっている）。"""
    seg = x[i0:]
    e = level_track_db(seg)
    k = max(1, int(round(smooth_s / 0.005)))
    p = 10 ** (e / 10)
    ps = np.convolve(p, np.ones(k) / k, mode="same")
    es = 10 * np.log10(ps + 1e-12)
    tc = np.arange(len(e)) * 0.005 + 0.01
    sel = (tc >= ts_rel) & (tc <= dim_rel)
    out: dict = {}
    if not sel.any():
        sel = (tc >= ts_rel) & (tc <= ts_rel + 0.05)
    ref = float(es[sel].max())
    out["ref_db"] = ref
    out["plateau_range_db"] = float(e[sel].max() - e[sel].min())
    half = k // 2
    i1 = int(np.searchsorted(tc, dim_rel))
    i2 = int(np.nonzero(es[: len(es) - half] >= ref - 40)[0][-1])
    out["smooth_max_rise_db"] = float(np.diff(es[i1:i2 + 1]).max()) if i2 > i1 + 1 else 0.0
    for n in (1, 3, 10, 20, 30):
        below = es <= ref - n
        below[:half] = False
        idx = None
        for i in range(len(es) - half):
            if below[i] and below[i:len(es) - half].all():
                idx = i
                break
        out[f"t_minus{n}"] = float(tc[idx]) if idx is not None else float("nan")
    return out


# ---------------------------------------------------------------- 句の途中・句末の伸ばし（母音の内側に WORLD の持続音をはさむ）


def _fill_f0(f0: np.ndarray, default: float) -> np.ndarray:
    """無声（0）のフレームを、近くの有声の値で埋める（なければ default）。"""
    out = f0.copy()
    v = np.nonzero(out > 0)[0]
    if len(v) == 0:
        return np.full_like(out, default)
    idx = np.arange(len(out))
    out[out <= 0] = np.interp(idx[out <= 0], v, out[v])
    return out


def _xcorr(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))


def _analyze_vowel(x: np.ndarray, vs: float, ve: float, level_ln: float) -> dict:
    """母音 [vs, ve) とその前後 60 ms を WORLD で分析する（F0・スペクトル包絡・非周期性）。"""
    A, B = max(0, int((vs - 0.06) * SR)), min(len(x), int((ve + 0.06) * SR))
    seg = x[A:B].astype(np.float64)
    f0, t = pw.harvest(seg, SR, f0_floor=100, f0_ceil=500, frame_period=FRAME_MS)
    f0f = _fill_f0(f0, float(np.exp(level_ln)))
    sp = pw.cheaptrick(seg, f0f, t, SR)
    ap = pw.d4c(seg, f0f, t, SR)
    return dict(A=A, t=t, f0=f0f, lsp=np.log(sp + 1e-20), ap=ap)


def pick_windows(x: np.ndarray, vs: float, ve: float, level_ln: float, *, xfade_s: float = 0.025,
                 per_min: float = 0.9, rel_db: float = 4.0, min_replace_s: float = 0.07,
                 dip_db: float = 5.0, an: dict | None = None) -> dict:
    """母音 [vs, ve) の中で、周期的で大きい（ピークの窓より rel_db 以内の）窓のうち、一番前（front）と一番後ろ（back）を選ぶ。
    VOICEVOX の 0.2〜0.3 秒の母音は、母音の頭から約 0.1 秒の所に 10〜15 dB の凹み（穴）が出ることがある。
    置き換える範囲は [front の開始, back の終わり] なので、凹みがその間にあれば取り除かれる。
    ok = front と back があり、間が min_replace_s 以上で、front より前（vs + 0.03 から）に凹み（front より dip_db 以上低い所）が無い。
    戻り値: dict(ok, reason, fs（front の開始サンプル）, be（back の終わりのサンプル）, per_front, per_back, an)。"""
    an = an or _analyze_vowel(x, vs, ve, level_ln)
    A, t, f0f = an["A"], an["t"], an["f0"]
    L = int(round(xfade_s * SR))
    lo, hi = int((vs + 0.02) * SR), int((ve - 0.04) * SR) - L
    rows = []
    for s in range(lo, hi + 1, int(0.0025 * SR)):
        m = (t >= (s - A) / SR) & (t < (s + L - A) / SR)
        if m.sum() < 2:
            continue
        T = int(round(SR / float(np.median(f0f[m]))))
        rows.append((s, _rms_db(x[s: s + L]), _periodicity(x[s: s + L + T + 8], T)))
    res: dict = dict(ok=False, reason="", an=an)
    if not rows:
        res["reason"] = "母音が短すぎる"
        return res
    c = np.array(rows)
    mx = c[c[:, 2] > 0.85, 1].max() if (c[:, 2] > 0.85).any() else c[:, 1].max()
    good = np.nonzero((c[:, 2] >= per_min) & (c[:, 1] >= mx - rel_db))[0]
    if len(good) < 2:
        res["reason"] = "周期的で大きい窓が少ない"
        return res
    f, b = int(c[good[0], 0]), int(c[good[-1], 0])
    res.update(fs=f, be=b + L, per_front=float(c[good[0], 2]), per_back=float(c[good[-1], 2]))
    if (b + L - f) / SR < min_replace_s:
        res["reason"] = "置き換える範囲が短い"
        return res
    e = level_track_db(x[int((vs + 0.03) * SR): f + L], 0.01, 0.005)
    lvl_f = _rms_db(x[f: f + L])
    res["pre_min_db"] = float(e.min() - lvl_f) if len(e) else 0.0
    if len(e) and e.min() < lvl_f - dip_db:
        res["reason"] = f"front の前に凹み {e.min() - lvl_f:.1f} dB"
        return res
    res["ok"] = True
    return res


def extend_interior(x: np.ndarray, vs: float, ve: float, level_ln: float, add_s: float, *,
                    xfade_s: float = 0.025, vibrato_cents: float = 6.0,
                    vibrato_hz: float = 5.2, jitter_cents: float = 3.0, tremolo_db: float = 0.5,
                    ap_floor: float = 0.07, seed: int = 0, no_rise: bool = False,
                    pick_kw: dict | None = None, join_orig: bool = False) -> tuple[np.ndarray, dict]:
    """x の母音 [vs, ve)（秒）を、内側で add_s 秒（約。ピッチ周期の端数ぶん ±数 ms）延ばして返す。

    母音全体を WORLD で作り直すのではなく、pick_windows が選んだ 2 つの安定した窓（front と back）の間 [fs, be] だけを、
    両方の窓から取ったスペクトル包絡（front → back へ連続的に変える）・非周期性の WORLD 合成音に置き換え、
    前後 xfade_s のクロスフェードで位相をそろえて接ぐ。母音の立ち上がり・次の子音への渡りは元の波形のまま。
    F0 は両端の窓で測った値の間を結び、ゆるいビブラート・ジッタ・小さなトレモロを両端でゼロになる形で加える。
    no_rise=True: F0 の中間の道筋を front の F0 で平らに保ち（back のほうが低ければ、そこへ向けてゆるやかに下げ）、
    back のほうが高いときの上昇は、最後のクロスフェードの中（約 30 ms）だけで行う。伸ばした所で F0 は上がらない。
    戻り値: (y, info)。info: s0（置き換え範囲の開始サンプル）、w（その長さ）、ins_at（= s0 + w。これ以降の元の波形は
    n_add サンプル後ろへずれる）、n_add（足したサンプル数）、xcorr_front / xcorr_back（接ぎ目の相関）、f0_front / f0_back。"""
    rng = np.random.default_rng(seed)
    L = int(round(xfade_s * SR))
    pk = pick_windows(x, vs, ve, level_ln, xfade_s=xfade_s, **(pick_kw or {}))
    if not pk["ok"]:
        raise ValueError(f"母音 {vs:.3f}-{ve:.3f} s に伸ばせる窓が無い: {pk['reason']}")
    an = pk["an"]
    A, t, f0f, lsp, ap = an["A"], an["t"], an["f0"], an["lsp"], an["ap"]
    s0, be = pk["fs"], pk["be"]
    w = be - s0
    m_f = (t >= (s0 - A) / SR) & (t < (s0 + L - A) / SR)
    m_b = (t >= (be - L - A) / SR) & (t < (be - A) / SR)
    lf = float(np.log(np.median(f0f[m_f])))
    lb = float(np.log(np.median(f0f[m_b])))
    spf = lsp[m_f].mean(0)
    spb = lsp[m_b].mean(0)
    apm = np.clip(ap[m_f | m_b].mean(0) + ap_floor, 1e-3, 0.999)

    add = int(round(add_s * SR))
    lead = int(0.06 * SR)
    ve_i = int(round(ve * SR))
    tail_n = max(0, ve_i - be) if no_rise else 0      # no_rise: 後ろの窓のあとの元の尾も、平らな F0 の合成音に置き換える
    total = lead + w + add + tail_n + int(0.06 * SR)
    n = int(round(total / SR * 1000 / FRAME_MS)) + 2
    tt = np.arange(n) * FRAME_MS / 1000
    u_f = (lead + L / 2) / SR
    u_b = (lead + w + add - L / 2) / SR
    r = np.clip((tt - u_f) / max(u_b - u_f, 1e-3), 0, 1)
    lnf = lf + (lb - lf) * r
    if no_rise:
        tgt = min(lb, lf)
        lnf = lf + (tgt - lf) * r
    ramp = min(0.12, max(u_b - u_f, 0.0) / 2.5)
    mask = np.clip((tt - u_f - 0.03) / ramp, 0, 1) * np.clip((u_b - 0.03 - tt) / ramp, 0, 1)
    mask = 0.5 - 0.5 * np.cos(np.pi * mask)
    vib = np.sin(2 * np.pi * vibrato_hz * tt + rng.uniform(0, 2 * np.pi))
    walk = np.cumsum(rng.normal(0, 1, n))
    walk -= np.linspace(walk[0], walk[-1], n)
    walk = walk / (walk.std() + 1e-9)
    cents = np.log(2) / 1200
    lnf = lnf + (vibrato_cents * cents) * vib * mask + (jitter_cents * cents) * walk * mask
    lsp_t = spf[None, :] * (1 - r[:, None]) + spb[None, :] * r[:, None]
    def synth(lnf_):
        y_ = pw.synthesize(np.exp(lnf_), np.exp(lsp_t), np.tile(apm, (n, 1)), SR, FRAME_MS)
        return y_ * 10 ** (tremolo_db * np.interp(np.arange(len(y_)) / SR, tt, vib * mask) / 20)

    y = synth(lnf)
    # --- 位相合わせ（前）
    T = int(round(SR / float(np.exp(lf if no_rise else 0.5 * (lf + lb)))))
    tgt_f = x[s0: s0 + L]
    best, shf = -9.0, lead
    for sh in range(max(0, lead - 3 * T), lead + 3 * T):
        cc = _xcorr(tgt_f, y[sh: sh + L])
        if cc > best:
            best, shf = cc, sh
    # no_rise + join_orig（次の仮名が続く）: 終わりの Lb の間に、F0 を元の波形の終わりの F0 へ合わせ、位相をそろえて元の波形へつなぐ
    Lb = min(int(0.025 * SR), tail_n) if (no_rise and join_orig) else 0
    f_join = None
    if Lb > 0:
        seg_o = x[max(0, ve_i - int(0.08 * SR)): ve_i].astype(np.float64)
        f_o, t_o = pw.harvest(seg_o, SR, f0_floor=150, f0_ceil=450, frame_period=FRAME_MS)
        f_o = pw.stonemask(seg_o, f_o, t_o, SR)
        m_o = (t_o >= len(seg_o) / SR - 0.03) & (f_o > 0)
        if m_o.sum() >= 3:
            f_join = float(np.log(np.median(f_o[m_o])))
        if f_join is not None:
            t_end = (shf + w + add + tail_n) / SR          # 合成音の中で、元の ve に当たる時刻（位相合わせで ±1.5 周期ずれる）
            ramp_j = np.clip((tt - (t_end - Lb / SR - 0.004)) / (Lb / SR + 0.008), 0, 1)
            lnf2 = lnf + np.minimum(f_join - lf, 25 * np.log(2) / 1200) * ramp_j      # 元の F0 へ合わせる。上げ幅は最大 +25 cent（下げは自由）
            y = synth(lnf2)
    yy = y[shf:]
    # --- 位相合わせ（後ろ）: yy[e-L:e] が x[be-L : be] に一番合う e
    tgt_b = x[be - L: be]
    bestb, e = -9.0, w + add
    for ee in (range(w + add - 2 * T, w + add + 2 * T + 1) if not no_rise else [w + add]):    # no_rise: 元の波形に戻らないので位相合わせは不要
        if ee - L < L or ee > len(yy):
            continue
        cc = _xcorr(tgt_b, yy[ee - L: ee])
        if cc > bestb:
            bestb, e = cc, ee
    if Lb > 0 and f_join is not None:
        tgt_j = x[ve_i - Lb: ve_i]
        bj, e = -9.0, w + add
        for ee in range(w + add - int(1.5 * T), w + add + int(1.5 * T) + 1):
            a_ = ee + tail_n - Lb
            if a_ < L or ee + tail_n > len(yy):
                continue
            cc = _xcorr(tgt_j, yy[a_: ee + tail_n])
            if cc > bj:
                bj, e = cc, ee
        join_xcorr = bj
    else:
        join_xcorr = None
    ramp_in = np.linspace(0.0, 1.0, L)
    if not no_rise:
        yy = yy[:e].copy()
        # --- 音量: 前の端と後ろの端を元の波形に合わせる（dB で直線的に）
        gf = np.sqrt((tgt_f ** 2).mean() / ((yy[:L] ** 2).mean() + 1e-20))
        gb = np.sqrt((tgt_b ** 2).mean() / ((yy[e - L:] ** 2).mean() + 1e-20))
        gf, gb = float(np.clip(gf, 0.5, 2.0)), float(np.clip(gb, 0.5, 2.0))
        yy *= np.exp(np.interp(np.arange(e), [L / 2, e - L / 2], [np.log(gf), np.log(gb)]))
        out = np.concatenate([x[:s0], yy, x[be:]])
        out[s0: s0 + L] = x[s0: s0 + L] * (1 - ramp_in) + yy[:L] * ramp_in
        out[s0 + e - L: s0 + e] = yy[e - L:] * (1 - ramp_in) + x[be - L: be] * ramp_in
    else:
        # 元の波形に戻らない（元の尾は F0 が上がっていることがあり、戻る所で上向きにはねる）。後ろの窓のあとは、
        # 同じ平らな F0 の合成音を、元の尾の音量の形（後ろの窓に対する比）で ve まで続け、最後の 8 ms だけ元の波形へ。
        gf = float(np.clip(np.sqrt((tgt_f ** 2).mean() / ((yy[:L] ** 2).mean() + 1e-20)), 0.5, 2.0))
        gb = float(np.clip(np.sqrt((tgt_b ** 2).mean() / ((yy[e - L:e] ** 2).mean() + 1e-20)), 0.5, 2.0))
        body = yy[:e] * np.exp(np.interp(np.arange(e), [L / 2, e - L / 2], [np.log(gf), np.log(gb)]))
        tail = yy[e: e + tail_n].copy()
        if tail_n > 0:
            nat = x[be - L: ve_i]
            hop, win = int(0.005 * SR), int(0.015 * SR)
            ctr = np.arange(L, len(nat) - win // 2, hop)
            rms = np.array([np.sqrt((nat[c - win // 2: c + win // 2] ** 2).mean()) for c in ctr]) if len(ctr) else np.array([1.0])
            ref = np.sqrt((nat[:L] ** 2).mean()) + 1e-12
            gcur = np.clip(rms / ref, 0.0, 1.5)
            if len(ctr) > 1:
                gcur = np.convolve(np.pad(gcur, 1, mode="edge"), np.ones(3) / 3, mode="valid")
                gt = np.interp(np.arange(tail_n), ctr - L, gcur)
            else:
                gt = np.full(tail_n, float(gcur[0]))
            wr = np.clip(np.arange(tail_n) / (0.02 * SR), 0.0, 1.0)      # 接ぎ目（body → tail）で音量が飛ばないよう、20 ms かけて元の尾の形へ
            gt = (1 - wr) + gt * wr
            tail = tail * gb * gt
        seg = np.concatenate([body, tail])
        Lf = Lb if (Lb > 0 and join_xcorr is not None) else min(int(0.008 * SR), tail_n)
        if Lf > 0:
            r8 = np.linspace(0.0, 1.0, Lf)
            seg[-Lf:] = seg[-Lf:] * (1 - r8) + x[ve_i - Lf: ve_i] * r8
        out = np.concatenate([x[:s0], seg, x[ve_i:]])
        out[s0: s0 + L] = x[s0: s0 + L] * (1 - ramp_in) + body[:L] * ramp_in
        e_tail = e
    info = dict(s0=s0, w=w, ins_at=be, n_add=e - w, xcorr_front=best, xcorr_back=bestb,
                f0_front=float(np.exp(lf)), f0_back=float(np.exp(lb)), anchor_s=s0 / SR,
                xfade_front=(s0, s0 + L), xfade_back=(s0 + e - L, s0 + e),
                per_front=pk["per_front"], per_back=pk["per_back"], tail_n=tail_n, no_rise=no_rise, join_xcorr=join_xcorr)
    return out, info
