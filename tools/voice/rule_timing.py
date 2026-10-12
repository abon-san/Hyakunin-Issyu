#!/usr/bin/env python3
"""競技かるたの規則どおりの時間配分（5・3・1・6 方式）で 1 首の上の句・下の句を作る（KarutaRules.timing == "rule"）。

公式（全日本かるた協会の読手テキスト）:
  下の句 約 5 秒 / 余韻 3.0 秒 / 間合い 1.0 秒 / 上の句 約 6 秒。初句の五字は切らずに 1 字およそ 0.2 秒で読む。
  間合いは、規則では「余韻のあと、次の札の上の句の前」に置く。

作り方（全体をゆっくりにするのではなく、伸ばしの印のモーラと句末を長くして合わせる）:
  1. 子音と、印のない母音は、エンジンの既定値のまま（speed_scale 0.9）。ただし上の句の初句は 1 字 rule_first_mora_s
     （0.2 秒）にそろえる（母音の長さでフレーム単位に合わせる。初句の途中にポーズを置かない）。
  2. 伸ばしの印のモーラと句末のモーラ（plan.marks の length があるもの）は、いったん「短い母音」
     （vowel_length 0.12〜0.25。印ごとに rule_hold_src_cands の候補から、延ばせる窓が取れる最初のもの）で作る。
     VOICEVOX は約 0.3 秒より長い母音を雑音の床に崩し、0.2〜0.25 でも母音の頭から約 0.1 秒の所に凹みが出ることが
     あるので、長い母音をエンジンに作らせることはしない。
  3. 目標（上の句 6.0 秒 / 下の句 5.0 秒）との差を、印のモーラの母音の長さに「印ごとの重み」で配分する。
     重み = 現行ルールの長さ（句末 0.32、上の句の終わりの前 0.18、下の句の中 0.30、その他 0.25）。
     小さい印（nobashi.json の short）には rule_hold_w_short を掛ける。母音の合計 V_i = alpha * w_i を解く。
  4. 足りない長さは、その母音の内側の安定した窓だけを WORLD の持続音に置き換えて延ばす
     （yoin.extend_interior。母音の立ち上がりと次の子音への渡りは元の波形のまま。位相をそろえてクロスフェード）。
     母音の後ろが子音（あらーみ の ら、わがー の が）でも、句末（ポーズが続く）でも同じ方法で、HNR は元の母音のまま。
  5. 下の句の最後のモーラ（余韻）は yoin.short_tail（遅めの Dim。KarutaRules.yoin_short_*）で 3.0 秒にする。
  6. 句の間のポーズは rule_ku_pause_s（実時間の秒）。
"""
from __future__ import annotations

import dataclasses

import numpy as np

import karuta_rules as kr
import voicevox_karuta as vk
import yoin

SR = vk.SAMPLE_RATE


def vowel_q_for_total(cons_len: float | None, speed: float, total_s: float) -> float:
    """子音の長さ cons_len（クエリ単位）の後で、モーラ全体（子音 + 母音）を total_s 秒（フレーム単位で最も近く）に
    するための vowel_length（クエリ単位）。"""
    nc = vk._frames(cons_len or 0.0, speed)
    target = int(round(total_s * vk.FRAME_RATE))
    nv = max(1, target - nc)
    q = nv * speed / vk.FRAME_RATE
    for d in (0.0, -0.002, 0.002, -0.004, 0.004, -0.006, 0.006):
        if vk._frames(q + d, speed) == nv:
            return q + d
    return q


def hold_weights(plan: kr.VersePlan, rules: kr.KarutaRules, short_ks: set[int]) -> dict[int, float]:
    """伸ばすモーラ → 重み。現行ルールの長さ（クエリ単位）を句末の長さで割ったもの。小さい印は rule_hold_w_short 倍。"""
    out: dict[int, float] = {}
    for mk in plan.marks:
        if mk.length is None:
            continue
        w = mk.length / rules.ku_end_len
        if mk.k in short_ks:
            w *= rules.rule_hold_w_short
        out[mk.mora] = w
    return out


def allocate(extra_total: float, src: dict[int, float], w: dict[int, float], min_ext: float = 0.05
             ) -> tuple[dict[int, float], float]:
    """足す長さの合計 extra_total を、母音の合計 V_i = alpha * w_i になるよう配分する。V_i が元の長さ src_i を
    min_ext 以上上回らないものは延ばさない。戻り値: ({モーラ: 足す秒数}, alpha)。"""
    active = set(w)
    while True:
        alpha = (extra_total + sum(src[i] for i in active)) / sum(w[i] for i in active)
        ext = {i: alpha * w[i] - src[i] for i in active}
        drop = {i for i, d in ext.items() if d < min_ext}
        if not drop:
            break
        active -= drop
        if not active:
            raise ValueError("配分できる印がない")
    return {i: (ext[i] if i in active else 0.0) for i in w}, alpha


def render_rule_clip(engine: vk.Engine, qr: vk.QueryResult, plan: kr.VersePlan, style_id: int,
                     rules: kr.KarutaRules, short_ks: set[int] | frozenset[int] = frozenset()) -> kr.Clip:
    assert rules.timing == "rule"
    assert rules.yoin_mode == "short" and rules.yoin_short_tail_s > 0, "規則の時間配分は yoin_mode=short + 余韻の長さが必要"
    sr, speed = SR, rules.speed_scale
    units = qr.units
    default_moras = vk._flat_moras(qr.query.accent_phrases)
    w = hold_weights(plan, rules, set(short_ks))
    lvl = {"L": rules.level_L, "M": rules.level_M, "H": rules.level_H}
    first: dict[int, float] = {}
    if plan.kind == "kami":
        for i, u in enumerate(units):
            if u.ku == 0 and i not in w:
                first[i] = vowel_q_for_total(default_moras[i].consonant_length, speed, rules.rule_first_mora_s)
    q_rules = dataclasses.replace(rules, ku_pause=rules.rule_ku_pause_s * speed)

    def render(hold_q: dict[int, float]):
        pl = dataclasses.replace(plan, lengths={**first, **hold_q})
        qq = kr.shape_ref(qr, pl, q_rules)
        tt = vk.timeline(qq)
        xx = vk._wav_to_float(engine.synth.synthesis(qq, style_id, enable_interrogative_upspeak=False))
        err = len(xx) - tt.total * sr
        assert abs(err) <= vk.SAMPLES_PER_FRAME, f"timeline と実際の長さが合わない: {err} サンプル"
        return qq, tt, xx

    # ---- 印ごとに、延ばせる窓が取れる最初の長さの候補を選ぶ（短い母音のほうがきれい）
    src_q: dict[int, float] = {}
    for cand in rules.rule_hold_src_cands:
        todo = [i for i in w if i not in src_q]
        if not todo:
            break
        _, t_c, x_c = render({i: cand for i in w})
        for i in todo:
            if yoin.pick_windows(x_c, t_c.vowel_starts[i], t_c.ends[i], lvl[plan.levels[i]])["ok"]:
                src_q[i] = cand
    missing = [units[i].text for i in w if i not in src_q]
    assert not missing, f"延ばせる母音が作れない印: {missing}"
    q, tl, x = render(src_q)

    kami = plan.kind == "kami"
    target = rules.rule_kami_s if kami else rules.rule_shimo_s
    base_total = (tl.ends[-1] - tl.starts[0]) if kami else (tl.vowel_starts[-1] - tl.starts[0])
    src = {i: tl.ends[i] - tl.vowel_starts[i] for i in w}
    extra = target - base_total
    assert extra > 0, f"{plan.kind}: 延ばす前でもう {base_total:.2f} 秒 > 目標 {target}"
    ext, alpha = allocate(extra, src, w)

    # ---- 母音の内側を延ばす（後ろの印から。前の印の位置は変わらない）
    y = x
    infos: dict[int, dict] = {}
    for i in sorted(w, reverse=True):
        if ext[i] <= 0:
            continue
        y, inf = yoin.extend_interior(y, tl.vowel_starts[i], tl.ends[i], lvl[plan.levels[i]], ext[i],
                                      vibrato_hz=rules.yoin_vibrato_hz, **kr.hold_pitch_kw(rules),
                                      tremolo_db=rules.yoin_tremolo_db, ap_floor=rules.yoin_ap_floor, seed=i)
        infos[i] = inf
    ins = sorted((inf["ins_at"], inf["n_add"]) for inf in infos.values())

    def shift(t_s: float) -> int:
        """元の時刻 t_s（秒）の波形が、延ばしたあとで何サンプル後ろにずれるか。"""
        ts = t_s * sr
        return sum(n for at, n in ins if at <= ts + 1e-6)

    def newt(t_s: float) -> float:
        return t_s + shift(t_s) / sr

    starts = [newt(t) for t in tl.starts]
    vstarts = [newt(t) for t in tl.vowel_starts]
    ends = [newt(t) for t in tl.ends]
    tl2 = vk.Timeline(starts, ends, vstarts, newt(tl.total - 1e-9))

    gain_src = None
    ref_end = None
    info: dict = {}
    if plan.kind == "shimo":
        v0, v1 = vstarts[-1], ends[-1]
        gain_src = y.copy()
        ref_end = int(round(v1 * sr))
        y, info = yoin.short_tail(
            y, v0, v1, lvl[plan.levels[-1]], rules.yoin_short_tail_s,
            power=rules.yoin_short_power, plateau_s=rules.yoin_short_plateau_s,
            dim_start_s=rules.yoin_short_dim_start_s or None, end_s=rules.yoin_short_end_s or None,
            vibrato_hz=rules.yoin_vibrato_hz, **kr.tail_pitch_kw(rules), tremolo_db=rules.yoin_tremolo_db, ap_floor=rules.yoin_ap_floor)
        n = int(round(v0 * sr)) + int(round(rules.yoin_total_s * sr))
        y = y[:n] if len(y) >= n else np.pad(y, (0, n - len(y)))
        verse_s = vstarts[-1] - starts[0]
        yoin_s = len(y) / sr - v0
    else:
        verse_s = ends[-1] - starts[0]
        yoin_s = 0.0
    z, gain = kr._normalize(y, rules, ref_end, gain_src)

    # 延ばした母音の記録（検査用）。座標は延ばしたあとの波形。
    holds = []
    for i in sorted(w):
        inf = infos.get(i)
        before = shift(tl.vowel_starts[i])           # この母音より前の印で増えたサンプル数
        rec = dict(mora=i, text=units[i].text, weight=w[i], src_q=src_q[i], src_s=src[i], ext_req_s=ext[i],
                   vowel_start_s=vstarts[i], vowel_end_s=ends[i], vowel_s=ends[i] - vstarts[i],
                   level=lvl[plan.levels[i]])
        if inf:
            rec.update(n_add_s=inf["n_add"] / sr, f0_front=inf["f0_front"], f0_back=inf["f0_back"],
                       xcorr_front=inf["xcorr_front"], xcorr_back=inf["xcorr_back"],
                       xfade_front=[a + before for a in inf["xfade_front"]],
                       xfade_back=[a + before for a in inf["xfade_back"]],
                       ext_start_s=(inf["s0"] + before) / sr, ext_end_s=(inf["s0"] + before) / sr
                       + (inf["n_add"] + inf["w"]) / sr)
        holds.append(rec)
    info = dict(info, alpha=alpha, base_total_s=base_total, target_s=target, extra_s=extra)
    clip = kr.Clip(z, q, plan, qr, vk._kana_times(qr, tl2, 0.0), verse_s, yoin_s, gain,
                   list(zip(starts, vstarts, ends)), info)
    clip.holds = holds
    return clip


def check_rule_clip(clip: kr.Clip, rules: kr.KarutaRules, tol_s: float = 0.15) -> dict:
    """規則どおりの時間配分の 1 クリップを検査する（目標との差、タイミング表、クリッピング）。"""
    plan, qr = clip.plan, clip.qr
    target = rules.rule_kami_s if plan.kind == "kami" else rules.rule_shimo_s
    assert abs(clip.verse_s - target) <= tol_s, f"{plan.kind} の長さ {clip.verse_s:.3f} 秒（目標 {target}）"
    if plan.kind == "shimo":
        assert abs(clip.yoin_s - rules.yoin_total_s) <= 1.0 / SR + 1e-9, f"余韻 {clip.yoin_s:.3f} 秒"
    ms = clip.kana_ms
    assert len(ms) == qr.n_chars and all(b >= a for a, b in zip(ms, ms[1:])), "kanaStartMs が単調でない"
    sp = [s for s, _, _ in clip.spans]
    assert all(b > a for a, b in zip(sp, sp[1:])), "モーラの開始が増加していない"
    if plan.kind == "kami":
        held = {h["mora"] for h in clip.holds}
        for i, u in enumerate(qr.units):
            if u.ku == 0 and i not in held:
                s_, _, e_ = clip.spans[i]
                assert abs((e_ - s_) - rules.rule_first_mora_s) <= 0.02, f"初句のモーラ #{i} が {e_ - s_:.3f} 秒"
    pcm = np.round(clip.samples * 32767)
    assert (np.abs(pcm) >= 32767).sum() == 0, "クリッピング"
    assert np.abs(clip.samples).max() < 10 ** (rules.peak_db / 20) + 1e-6, "ピークが -1 dBFS を超えた"
    for h in clip.holds:
        assert "n_add_s" not in h or h["xcorr_front"] > 0.5 and h["xcorr_back"] > 0.5, f"接ぎ目の相関が低い: {h}"
    out: dict = {}
    if rules.pitch_mode == "anchored" and clip.holds:
        # 伸ばした母音の F0 が上がっていない（pitch_check.hold_pitch。後ろの窓へ合わせる最後の約 30 ms の上昇は含めない）
        import pitch_check
        ends, slopes, ups = [], [], []
        for h in clip.holds:
            if "ext_start_s" not in h:
                continue
            hp = pitch_check.hold_pitch(clip.samples, h["ext_start_s"], h["ext_end_s"])
            for nm in ("harvest", "acf"):
                assert hp[f"end_cents_{nm}"] <= 12.0, f"『{h['text']}』の伸ばしで F0 が上がっている ({nm}): {hp[f'end_cents_{nm}']:+.1f} cent"
            ends.append(max(hp["end_cents_harvest"], hp["end_cents_acf"]))
            slopes.append(max(hp["slope_cents_per_s_harvest"], hp["slope_cents_per_s_acf"]))
            ups.append(max(hp["max_above_start_cents_harvest"], hp["max_above_start_cents_acf"]))
        if ends:
            out = dict(hold_end_cents_max=max(ends), hold_slope_cents_per_s_max=max(slopes), hold_above_start_cents_max=max(ups))
    return out


def measure_holds(wav: np.ndarray, clip: kr.Clip, offset_s: float = 0.0) -> list[dict]:
    """書き出した波形から、延ばした母音ごとに HNR・クリック・レベル変化を測る。wav は -1〜1。clip の時刻 t は wav の t + offset_s 秒。"""
    out = []
    for h in clip.holds:
        if "ext_start_s" not in h:
            continue
        a, b = int(round((h["ext_start_s"] + offset_s) * SR)), int(round((h["ext_end_s"] + offset_s) * SR))
        f0 = float(np.sqrt(h["f0_front"] * h["f0_back"]))
        span = 0.12 + abs(np.log(h["f0_back"] / h["f0_front"]))
        seg = wav[a:b]
        hn = yoin.hnr_local_db(seg, f0, span=span)
        # 同じ母音の「元の部分」（延ばした所の前後 50 ms）との比較
        orig = np.concatenate([wav[max(0, a - int(0.05 * SR)): a], wav[b: b + int(0.05 * SR)]])
        # クリック: 延ばした領域 ±30 ms の 1 サンプル差分比、接ぎ目 ±2 ms とそれ以外の最大
        lo, hi = a - int(0.03 * SR), b + int(0.03 * SR)
        cr = yoin.click_ratio(wav[lo:hi])
        mask = np.ones(len(cr), bool)
        for c in list(h["xfade_front"]) + list(h["xfade_back"]):
            k = c + int(round(offset_s * SR)) - lo
            mask[max(0, k - 48): k + 48] = False
        e = yoin.level_track_db(wav[a:b])
        # ピッチの連続性: 延ばした所（±10 ms）の F0（harvest）の 5 ms ごとの変化の最大。前後 60 ms の文脈で分析する
        import pyworld as pw
        c0 = max(0, a - int(0.06 * SR))
        pseg = wav[c0: b + int(0.06 * SR)].astype(np.float64)
        f0t, tt = pw.harvest(pseg, SR, f0_floor=100, f0_ceil=500, frame_period=5.0)
        sel = (tt >= (a - c0) / SR - 0.01) & (tt <= (b - c0) / SR + 0.01)
        f0s = f0t[sel]
        fv = f0s > 0
        both = fv[1:] & fv[:-1]
        dln = np.abs(np.diff(np.log(np.where(fv, f0s, 1.0))))[both]
        out.append(dict(f0_max_step_cents=float(dln.max() * 1200 / np.log(2)) if len(dln) else 0.0,
                        f0_voiced_frac=float(fv.mean()), mora=h["mora"], text=h["text"], hnr_min=float(hn.min()), hnr_mean=float(hn.mean()),
                        click_splice=float(cr[~mask].max()), click_body=float(cr[mask].max()),
                        level_range_db=float(e.max() - e.min()),
                        edge_step_db=float(abs(10 * np.log10((wav[a - 480: a] ** 2).mean() + 1e-12)
                                               - 10 * np.log10((wav[a: a + 480] ** 2).mean() + 1e-12))),
                        xcorr_front=h["xcorr_front"], xcorr_back=h["xcorr_back"],
                        orig_rms_db=float(10 * np.log10((orig ** 2).mean() + 1e-12))))
    return out
