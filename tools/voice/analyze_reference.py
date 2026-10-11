#!/usr/bin/env python3
"""VOICEVOX エディタで作った「お手本」の競技かるた読みを解析し、再現する。

実行は setup_voicevox.sh が作る venv で行う（pyworld と scipy が要る）:
    tools/voice-src/venv/bin/python -m pip install scipy pyworld "setuptools<81"
    tools/voice-src/venv/bin/python -I tools/voice/analyze_reference.py REF.wav OUT_DIR

やること:
  1. 同じ文を既定の長さ・高さ（speed_scale 0.9）で合成し、そのモーラ時刻表を得る。
  2. 無音で区切った「句」ごとに、お手本と既定合成を MFCC の DTW で対応づけ、各音素の
     開始・終了をお手本上の時刻に写す（子音・母音を別々に）。
  3. pyworld(harvest) の F0 を各モーラの母音の中央で取り、{低, 中, 高} の三値に丸める。
  4. 長さ・高さ・間を AudioQuery に書き戻して再合成し、お手本と比べる。
     再合成とお手本の DTW のずれを使って音素の長さを数回直す（analysis-by-synthesis）。
  5. モーラ表を analysis.json に、再合成を reconstructed.wav に保存する。
"""
from __future__ import annotations

import argparse
import json
import sys
import warnings
from dataclasses import dataclass
from pathlib import Path

import numpy as np

warnings.filterwarnings("ignore")
sys.path.insert(0, str(Path(__file__).resolve().parent))
import voicevox_karuta as vk  # noqa: E402
from scipy.fft import dct  # noqa: E402

SR = vk.SAMPLE_RATE
HOP = 120                    # 5 ms
WIN = 512
FPS = vk.FRAME_RATE          # VOICEVOX のフレーム（93.75 fps）
LEVELS = {"L": 5.13, "M": 5.53, "H": 5.84}

TEXT_KANJI = "秋の田の　かりほの庵の　苫をあらみ　わが衣手は　露にぬれつつ"
TEXT_READING = "あきのたの　かりほのいおの　とまをあらみ　わがころもでは　つゆにぬれつつ"
CHARACTER, STYLE = "冥鳴ひまり", "ノーマル"

# ---------------------------------------------------------------- 特徴量


def _mel_fb(n_mels=40, n_fft=WIN, fmin=60.0, fmax=8000.0) -> np.ndarray:
    mel = lambda f: 2595 * np.log10(1 + f / 700)
    imel = lambda m: 700 * (10 ** (m / 2595) - 1)
    pts = imel(np.linspace(mel(fmin), mel(fmax), n_mels + 2))
    bins = np.floor((n_fft + 1) * pts / SR).astype(int)
    fb = np.zeros((n_mels, n_fft // 2 + 1))
    for i in range(n_mels):
        l, c, r = bins[i], bins[i + 1], bins[i + 2]
        for k in range(l, c):
            fb[i, k] = (k - l) / max(c - l, 1)
        for k in range(c, r):
            fb[i, k] = (r - k) / max(r - c, 1)
    return fb


_FB = _mel_fb()


def features(x: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """(MFCC+Δ 特徴 [T, D], フレーム対数エネルギー dB [T])。フレーム i は時刻 i*HOP/SR が中心。"""
    pad = WIN // 2
    xp = np.pad(x, (pad, pad))
    n = 1 + (len(xp) - WIN) // HOP
    idx = np.arange(WIN)[None, :] + HOP * np.arange(n)[:, None]
    fr = xp[idx] * np.hanning(WIN)[None, :]
    spec = np.abs(np.fft.rfft(fr, axis=1)) ** 2
    mel = np.log(spec @ _FB.T + 1e-10)
    c = dct(mel, type=2, axis=1, norm="ortho")[:, :14]
    en = 10 * np.log10(np.mean(fr ** 2, axis=1) / np.mean(np.hanning(WIN) ** 2) + 1e-12)
    mf = c[:, 1:]
    d = np.gradient(mf, axis=0)
    # エネルギーも特徴に入れる（子音の閉鎖区間などで効く）
    e = np.clip(en, -70, 0)[:, None] / 10.0
    return np.hstack([mf, 1.5 * d, 0.6 * c[:, :1] / 2 + e]), en


def dtw(a: np.ndarray, b: np.ndarray, band: float = 0.5) -> list[tuple[int, int]]:
    """a[T1,D], b[T2,D] の DTW（両端固定）。経路は (i, j) の列。"""
    t1, t2 = len(a), len(b)
    cost = np.sqrt(((a[:, None, :] - b[None, :, :]) ** 2).sum(-1))
    acc = np.full((t1 + 1, t2 + 1), np.inf)
    acc[0, 0] = 0.0
    bk = np.zeros((t1 + 1, t2 + 1), dtype=np.int8)
    for i in range(1, t1 + 1):
        for j in range(1, t2 + 1):
            c = acc[i - 1, j - 1], acc[i - 1, j], acc[i, j - 1]
            k = int(np.argmin(c))
            acc[i, j] = cost[i - 1, j - 1] + c[k]
            bk[i, j] = k
    path, i, j = [], t1, t2
    while i > 0 and j > 0:
        path.append((i - 1, j - 1))
        k = bk[i, j]
        if k == 0:
            i, j = i - 1, j - 1
        elif k == 1:
            i -= 1
        else:
            j -= 1
    return path[::-1]


def map_points(path: list[tuple[int, int]], pts: np.ndarray, n_b: int) -> np.ndarray:
    """a 側のフレーム位置を b 側へ写す。a のフレーム i に対応する b のフレームのうち最小のものを返す
    （長く伸びた音の「始まり」は始まりに、次の音の始まりは伸びた後に写る）。a の終端なら b の終端。"""
    first: dict[int, int] = {}
    for i, j in path:
        first.setdefault(i, j)
    out = []
    na = max(first) + 1
    for p in pts:
        i = int(round(p))
        if i >= na:
            out.append(float(n_b))
        else:
            out.append(float(first[i]))
    return np.array(out)


# ---------------------------------------------------------------- 区間分け


def speech_blocks(en: np.ndarray, thr_db=-60.0, min_gap_s=0.25) -> list[tuple[int, int]]:
    """エネルギーで声のある塊（[開始フレーム, 終了フレーム)）に分ける。"""
    act = en > thr_db
    blocks, i, n = [], 0, len(act)
    while i < n:
        if not act[i]:
            i += 1
            continue
        j = i
        gap = 0
        k = i
        while k < n:
            if act[k]:
                j = k
                gap = 0
            else:
                gap += 1
                if gap * HOP / SR > min_gap_s:
                    break
            k += 1
        blocks.append((i, j + 1))
        i = j + 1
    return blocks


# ---------------------------------------------------------------- 音素の時刻表（クエリ → 音素区間）


@dataclass
class Seg:
    mora: int            # モーラ番号（全体で通し）
    kind: str            # 'c' 子音 / 'v' 母音
    start: float         # 秒
    end: float
    label: str


def query_segments(q) -> tuple[list[Seg], list[tuple[float, float]], float]:
    """AudioQuery の音素区間（秒）と、アクセント句ごとの pause 区間、全長を返す。エンジンのフレーム丸めを再現。"""
    s = q.speed_scale
    t = vk._frames(q.pre_phoneme_length, s)
    segs, pauses = [], []
    mi = 0
    for p in q.accent_phrases:
        for m in p.moras:
            if m.consonant is not None:
                n = vk._frames(m.consonant_length or 0.0, s)
                segs.append(Seg(mi, "c", t / FPS, (t + n) / FPS, m.consonant))
                t += n
            n = vk._frames(m.vowel_length, s)
            segs.append(Seg(mi, "v", t / FPS, (t + n) / FPS, m.vowel))
            t += n
            mi += 1
        if p.pause_mora is not None:
            n = vk._frames(p.pause_mora.vowel_length, s)
            pauses.append((t / FPS, (t + n) / FPS))
            t += n
    t += vk._frames(q.post_phoneme_length, s)
    return segs, pauses, t / FPS


def synth(engine: vk.Engine, q, style_id: int) -> np.ndarray:
    return vk._wav_to_float(engine.synth.synthesis(q, style_id, enable_interrogative_upspeak=False))


def all_moras(q):
    return [m for p in q.accent_phrases for m in p.moras]


# ---------------------------------------------------------------- 長さをフレーム数で指定してクエリに書く


def stored_for_frames(n: int, speed: float) -> float:
    """実時間で n フレームになる保存値（秒）。エンジンは「保存値を速度 1 のフレームに丸め、速度で割って再度丸める」。"""
    best = None
    c = int(round(n * speed))
    for n1 in range(max(0, c - 2), c + 3):
        got = vk._frames(n1 / FPS, speed)
        key = (abs(got - n), abs(n1 / speed - n))
        if best is None or key < best[0]:
            best = (key, n1)
    return best[1] / FPS


def apply_timing(q, speed: float, cons_fr: dict[int, int], vowel_fr: dict[int, int],
                 pause_fr: dict[int, int] | None = None, pre_fr: int | None = None,
                 post_fr: int | None = None) -> None:
    """モーラ番号 → 実時間のフレーム数、で長さを指定する（指定のないものは今のまま）。
    pause_fr はアクセント句の番号 → 句末の間（フレーム）。"""
    q.speed_scale = speed
    mi = 0
    for pi, p in enumerate(q.accent_phrases):
        for m in p.moras:
            if m.consonant is not None and mi in cons_fr:
                m.consonant_length = stored_for_frames(cons_fr[mi], speed)
            if mi in vowel_fr:
                m.vowel_length = stored_for_frames(vowel_fr[mi], speed)
            mi += 1
        if pause_fr and pi in pause_fr:
            p.pause_mora = vk._pause_mora(stored_for_frames(pause_fr[pi], speed)) if pause_fr[pi] > 0 else None
    if pre_fr is not None:
        q.pre_phoneme_length = stored_for_frames(pre_fr, speed)
    if post_fr is not None:
        q.post_phoneme_length = stored_for_frames(post_fr, speed)


# ---------------------------------------------------------------- 再合成と DTW による測り直し


@dataclass
class Timing:
    """実時間のフレーム数（FPS=93.75/秒）で持つ長さのパラメータ。"""
    cons: dict[int, int]       # モーラ番号 → 子音フレーム数（子音のあるモーラのみ）
    vow: dict[int, int]        # モーラ番号 → 母音フレーム数
    pause: dict[int, int]      # 句の番号 k（0..3）→ 句末の間（フレーム）
    pre: int
    post: int


def timing_of(q) -> Timing:
    """クエリが今持つ長さをフレーム数にする（エンジンの丸めと同じ）。"""
    s = q.speed_scale
    cons, vow, pause = {}, {}, {}
    mi = 0
    for p in q.accent_phrases:
        for m in p.moras:
            if m.consonant is not None:
                cons[mi] = vk._frames(m.consonant_length or 0.0, s)
            vow[mi] = vk._frames(m.vowel_length, s)
            mi += 1
    return Timing(cons, vow, pause, vk._frames(q.pre_phoneme_length, s), vk._frames(q.post_phoneme_length, s))


def ku_mora_ranges(qr: vk.QueryResult) -> list[tuple[int, int]]:
    q = qr.query
    ends = [sum(len(p.moras) for p in q.accent_phrases[: pe + 1]) for pe in qr.ku_phrase_end]
    return [(0 if k == 0 else ends[k - 1], ends[k]) for k in range(len(ends))]


def make_query(qr: vk.QueryResult, speed: float, t: Timing, pitches: dict[int, float] | None = None,
               voiceless: set[int] | None = None):
    """既定クエリの複製に、長さ（Timing）と高さを書いて返す。"""
    import copy
    q = copy.deepcopy(qr.query)
    ku_end = qr.ku_phrase_end
    for p in q.accent_phrases:
        p.pause_mora = None
    apply_timing(q, speed, t.cons, t.vow, {ku_end[k]: n for k, n in t.pause.items()}, t.pre, t.post)
    mi = 0
    for p in q.accent_phrases:
        for m in p.moras:
            if pitches is not None:
                m.pitch = 0.0 if (voiceless and mi in voiceless) else pitches.get(mi, m.pitch)
            mi += 1
    return q


def measure_vs_ref(x_rec: np.ndarray, f_ref, en_ref, blocks_ref, f_rec, en_rec, blocks_rec, segs,
                   ku_seg_range: list[tuple[int, int]], margin_s=0.06):
    """句ごとに再合成とお手本を DTW し、再合成の各音素の境界をお手本の時刻（秒）に写す。

    戻り値: 音素ごとの (お手本上の開始, 終了)。"""
    m = int(round(margin_s * SR / HOP))
    out: list[tuple[float, float]] = [(0.0, 0.0)] * len(segs)
    for k, (lo, hi) in enumerate(ku_seg_range):
        ra0, ra1 = blocks_rec[k]
        rb0, rb1 = blocks_ref[k]
        a0, a1 = max(ra0 - m, 0), min(ra1 + m, len(f_rec))
        b0, b1 = max(rb0 - m, 0), min(rb1 + m, len(f_ref))
        path = dtw(f_rec[a0:a1], f_ref[b0:b1])
        for si in range(lo, hi):
            s = segs[si]
            pts = np.array([s.start * SR / HOP - a0, s.end * SR / HOP - a0])
            pts = np.clip(pts, 0, a1 - a0)
            mm = map_points(path, pts, b1 - b0)
            out[si] = ((mm[0] + b0) * HOP / SR, (mm[1] + b0) * HOP / SR)
    return out


def seg_ranges(segs: list[Seg], mrs: list[tuple[int, int]]) -> list[tuple[int, int]]:
    out = []
    for lo, hi in mrs:
        idx = [i for i, s in enumerate(segs) if lo <= s.mora < hi]
        out.append((idx[0], idx[-1] + 1))
    return out


class Ctx:
    """解析で使う共通の状態。"""

    def __init__(self, ref_wav: Path, speed: float = 0.9):
        self.engine = vk.Engine()
        self.style = self.engine.find_style(CHARACTER, STYLE)
        self.qr = self.engine.build_query(TEXT_KANJI, TEXT_READING, self.style.id)
        self.speed = speed
        self.xr = vk._wav_to_float(Path(ref_wav).read_bytes())
        self.fr, self.er = features(self.xr)
        self.br = speech_blocks(self.er)
        self.mrs = ku_mora_ranges(self.qr)

    def render(self, q):
        x = synth(self.engine, q, self.style.id)
        f, e = features(x)
        return x, f, e, speech_blocks(e)

    def measure(self, q):
        """q を合成して、各音素の境界をお手本時刻に写す。(segs, 写像, 再合成の波形, ブロック)"""
        x, f, e, blocks = self.render(q)
        segs, _, _ = query_segments(q)
        assert len(blocks) == len(self.br) == len(self.mrs), (len(blocks), len(self.br))
        mp = measure_vs_ref(x, self.fr, self.er, self.br, f, e, blocks, segs, seg_ranges(segs, self.mrs))
        return segs, mp, x, blocks


def default_timing(ctx: Ctx, pause_s=0.4) -> Timing:
    import copy
    q = copy.deepcopy(ctx.qr.query)
    q.speed_scale = ctx.speed
    t = timing_of(q)
    t.pause = {k: int(round(pause_s * FPS)) for k in range(len(ctx.mrs) - 1)}
    return t


def refine(ctx: Ctx, t: Timing, free: list[tuple[str, int]], iters: int = 8, pitches=None, voiceless=None,
           verbose=False) -> Timing:
    """free に挙げた音素（('v', モーラ番号) か ('c', モーラ番号)）の長さと、句間の間・前後の余白を、
    再合成とお手本のずれから繰り返し直す。他の音素は t の長さのまま。

    句頭の位置は「声のある塊（-60 dB を超える区間）」の始まりで、句末の母音は塊の終わりで合わせ、
    句の途中の音素は DTW で写したお手本の長さで合わせる。1 回の更新で前の変更が後ろを動かす分は
    cum（累積のずれ、フレーム）で打ち消す。"""
    import copy
    t = copy.deepcopy(t)
    H = HOP / SR
    total_ref = len(ctx.xr) / SR
    free_set = set(free)
    for it in range(iters):
        q = make_query(ctx.qr, ctx.speed, t, pitches, voiceless)
        segs, mp, x, blocks = ctx.measure(q)
        idx = {(s.kind, s.mora): i for i, s in enumerate(segs)}
        if verbose:
            print(f"  iter {it}: total {len(x)/SR:.3f}s (ref {total_ref:.3f}) block start err "
                  f"{[round((ctx.br[k][0]-blocks[k][0])*H*1000) for k in range(len(blocks))]} ms, end err "
                  f"{[round((ctx.br[k][1]-blocks[k][1])*H*1000) for k in range(len(blocks))]} ms")
        cum = 0.0          # これまでの更新で後ろへずれる量（フレーム）
        d_pre = int(round((ctx.br[0][0] - blocks[0][0]) * H * FPS))
        t.pre = max(0, t.pre + d_pre)
        cum += d_pre
        for k, (lo, hi) in enumerate(ctx.mrs):
            if k > 0:
                onset_err = (ctx.br[k][0] - blocks[k][0]) * H * FPS
                d = int(round(onset_err - cum))
                newp = max(1, t.pause[k - 1] + d)
                cum += newp - t.pause[k - 1]
                t.pause[k - 1] = newp
            for m in range(lo, hi):
                for kind in ("c", "v"):
                    if (kind, m) not in free_set:
                        continue
                    cur = (t.cons if kind == "c" else t.vow)[m]
                    if kind == "v" and m == hi - 1:   # 句末の母音: 塊の終わりで合わせる
                        end_err = (ctx.br[k][1] - blocks[k][1]) * H * FPS
                        n = cur + int(round(end_err - cum))
                    else:
                        a, b = mp[idx[(kind, m)]]
                        n = int(round((b - a) * FPS))
                    n = max(1, n)
                    cum += n - cur
                    (t.cons if kind == "c" else t.vow)[m] = n
        # 全長はあとの余白で合わせる
        q = make_query(ctx.qr, ctx.speed, t, pitches, voiceless)
        tot = query_segments(q)[2]
        t.post = max(0, t.post + int(round((total_ref - tot) * FPS)))
    return t


# ---------------------------------------------------------------- 高さ（F0）


F0_STEP_MS = 2.5


def f0_contour(x: np.ndarray, fast: bool = False) -> tuple[np.ndarray, np.ndarray]:
    """(時刻[秒], ln F0。無声は nan)。pyworld の harvest（fast=True なら dio + stonemask）。"""
    import pyworld as pw
    xx = np.ascontiguousarray(x, dtype=np.float64)
    if fast:
        f0, tt = pw.dio(xx, SR, f0_floor=70, f0_ceil=700, frame_period=F0_STEP_MS)
        f0 = pw.stonemask(xx, f0, tt, SR)
    else:
        f0, tt = pw.harvest(xx, SR, f0_floor=70, f0_ceil=700, frame_period=F0_STEP_MS)
    lf = np.where(f0 > 0, np.log(np.maximum(f0, 1.0)), np.nan)
    return tt, lf


def vowel_pitch(tt: np.ndarray, lf: np.ndarray, a: float, b: float, lo=0.25, hi=0.75):
    """母音区間 [a, b] の中央 (lo..hi) の ln F0 の中央値と有声フレーム数。"""
    sel = (tt >= a + lo * (b - a)) & (tt <= a + hi * (b - a)) & ~np.isnan(lf)
    if sel.sum() < 3:  # 短い母音は区間を広げる
        sel = (tt >= a) & (tt <= b) & ~np.isnan(lf)
    return (float(np.median(lf[sel])) if sel.sum() else float("nan")), int(sel.sum())


def snap(v: float) -> tuple[str, float]:
    k = min(LEVELS, key=lambda c: abs(LEVELS[c] - v))
    return k, v - LEVELS[k]


def pitch_loss(tt_r, lf_r, tt_x, lf_x, voiced_only_both=True) -> float:
    """二つの ln F0 曲線の二乗平均の差（両方が有声のフレームだけ）。"""
    n = min(len(lf_r), len(lf_x))
    a, b = lf_r[:n], lf_x[:n]
    ok = ~np.isnan(a) & ~np.isnan(b)
    return float(np.mean((a[ok] - b[ok]) ** 2)) if ok.sum() else 1e9


def mora_pitches(segs: list[Seg], tt, lf, spans: dict[int, tuple[float, float]] | None = None) -> dict[int, float]:
    """各モーラの母音中央の ln F0（spans があればそこの時刻、なければ segs の時刻）。"""
    out = {}
    for s in segs:
        if s.kind != "v":
            continue
        a, b = spans[s.mora] if spans else (s.start, s.end)
        out[s.mora] = vowel_pitch(tt, lf, a, b)[0]
    return out


def search_levels(ctx: Ctx, t: Timing, init: dict[int, float], ref_vp: dict[int, float],
                  voiceless: set[int] | None = None, rounds: int = 4, verbose=False) -> dict[int, float]:
    """各モーラの高さを {低,中,高} の中から選ぶ。再合成の各モーラの母音の高さ（ln F0 の中央値）が
    お手本に近づくよう、1 モーラずつ替えてみて、良くなれば採用する（座標降下）。"""
    cur = dict(init)

    def loss(p):
        q = make_query(ctx.qr, ctx.speed, t, p, voiceless)
        x = synth(ctx.engine, q, ctx.style.id)
        tt, lf = f0_contour(x, fast=True)
        segs, _, _ = query_segments(q)
        vp = mora_pitches(segs, tt, lf)
        d = [(vp[m] - ref_vp[m]) ** 2 for m in vp if not np.isnan(vp[m]) and not np.isnan(ref_vp[m])]
        return float(np.sum(d))

    best = loss(cur)
    if verbose:
        print(f"  initial loss {best:.5f}")
    for r in range(rounds):
        changed = False
        for m in range(len(cur)):
            if voiceless and m in voiceless:
                continue
            for lv in LEVELS.values():
                if abs(cur[m] - lv) < 1e-9:
                    continue
                trial = dict(cur)
                trial[m] = lv
                l = loss(trial)
                if l < best - 1e-7:
                    best, cur, changed = l, trial, True
                    if verbose:
                        print(f"  round {r} mora {m} -> {lv}: loss {best:.5f}")
        if not changed:
            break
    return cur


# ---------------------------------------------------------------- 長くした音素の特定

STRETCH_RATIO = 1.8
STRETCH_MIN_EXTRA_S = 0.07


def feature_distance(ctx: Ctx, x: np.ndarray) -> float:
    f, _ = features(x)
    n = min(len(f), len(ctx.fr))
    return float(np.sqrt(((f[:n] - ctx.fr[:n]) ** 2).sum(-1)).mean())


def find_free(ctx: Ctx, t0: Timing, verbose=False) -> tuple[list[tuple[str, int]], list[dict]]:
    """既定の長さの合成とお手本を DTW し、極端に長い音素を探す。

    母音が長ければそのまま候補にする。子音が長いと出た場合は、DTW が長い母音の手前の渋滞を子音に
    割り当てた可能性があるので、{その子音, その母音, 前のモーラの母音} のどれを長くした再合成が
    お手本に最も近いか（MFCC 距離）で決める。"""
    q = make_query(ctx.qr, ctx.speed, t0)
    segs, mp, _, _ = ctx.measure(q)
    free: list[tuple[str, int]] = []
    cons_flag: list[int] = []
    notes: list[dict] = []
    for s, (a, b) in zip(segs, mp):
        d0, d1 = s.end - s.start, b - a
        if d1 >= STRETCH_RATIO * d0 and d1 - d0 >= STRETCH_MIN_EXTRA_S:
            (free if s.kind == "v" else cons_flag).append(("v", s.mora) if s.kind == "v" else s.mora)
    for m in cons_flag:
        lo = next(lo for lo, hi in ctx.mrs if lo <= m < hi)
        cands = [("c", m), ("v", m)] + ([("v", m - 1)] if m - 1 >= lo else [])
        scores = {}
        for c in cands:
            tt = refine(ctx, t0, free + [c], iters=5)
            scores[c] = feature_distance(ctx, synth(ctx.engine, make_query(ctx.qr, ctx.speed, tt), ctx.style.id))
        best = min(scores, key=scores.get)
        notes.append({"stretched_consonant_mora": m,
                      "candidates": {f"{k}{mm}": round(v, 3) for (k, mm), v in scores.items()},
                      "chosen": f"{best[0]}{best[1]}"})
        free.append(best)
        if verbose:
            print("  consonant stretch at mora", m, notes[-1])
    return sorted(set(free), key=lambda p: (p[1], p[0])), notes


# ---------------------------------------------------------------- 残りのずれの確認

def mora_rows(segs: list[Seg], mp, n_moras: int):
    """モーラごとの (最初の音素の番号, 母音の番号)。"""
    first, vow = {}, {}
    for i, s in enumerate(segs):
        first.setdefault(s.mora, i)
        if s.kind == "v":
            vow[s.mora] = i
    return [(first[m], vow[m]) for m in range(n_moras)]


def start_errors(segs, mp, n_moras) -> list[float]:
    """各モーラの開始時刻の誤差（DTW でお手本に写した時刻 − 再合成の時刻、秒）。"""
    rows = mora_rows(segs, mp, n_moras)
    return [mp[f][0] - segs[f].start for f, _ in rows]


def residual_free(ctx: Ctx, t: Timing, free, threshold_s=0.025, verbose=False):
    """主要な伸ばし以外にも、開始時刻の誤差が段差になっているモーラがあれば、その母音も自由にして直す。"""
    free = list(free)
    for _ in range(2):
        q = make_query(ctx.qr, ctx.speed, t)
        segs, mp, _, _ = ctx.measure(q)
        n = len(all_moras(q))
        err = start_errors(segs, mp, n)
        rows = mora_rows(segs, mp, n)
        added = []
        for m in range(n):
            if ("v", m) in free:
                continue
            f, v = rows[m]
            dev = (mp[v][1] - mp[f][0]) - (segs[v].end - segs[f].start)   # モーラ全体の長さのずれ
            if abs(dev) >= threshold_s and m + 1 < n and abs(err[m + 1] - err[m]) >= 0.8 * abs(dev):
                lo, hi = next((lo, hi) for lo, hi in ctx.mrs if lo <= m < hi)
                if m != hi - 1:
                    added.append(("v", m))
                    if verbose:
                        print(f"  extra free: mora {m} dev {dev*1000:+.0f} ms")
        if not added:
            break
        free += added
        t = refine(ctx, t, free, iters=6)
    return t, sorted(set(free), key=lambda p: (p[1], p[0]))


# ---------------------------------------------------------------- 全体の流れ


def analyze(ref_wav: Path, out_dir: Path, speed: float = 0.9, verbose: bool = True) -> dict:
    import dataclasses
    log = print if verbose else (lambda *a, **k: None)
    out_dir.mkdir(parents=True, exist_ok=True)
    ctx = Ctx(ref_wav, speed)
    H = HOP / SR
    n_mora = len(ctx.qr.units)
    total_ref = len(ctx.xr) / SR
    log(f"参照 {total_ref:.3f}s / 声のある塊 {[(round(a*H,3), round(b*H,3)) for a, b in ctx.br]}")

    # --- 1) 長さ
    t_def = default_timing(ctx)
    qd = make_query(ctx.qr, speed, t_def)
    segs_def, mp_def, _, _ = ctx.measure(qd)          # 既定 vs お手本（DTW）
    free, notes = find_free(ctx, t_def, verbose=verbose)
    t = refine(ctx, t_def, free, iters=8)
    t, free_all = residual_free(ctx, t, free, verbose=verbose)
    t = refine(ctx, t, free_all, iters=6)
    main_free = {m for _, m in free}

    # --- 2) 高さ
    q0 = make_query(ctx.qr, speed, t)
    segs0, mp0, _, _ = ctx.measure(q0)
    spans = {s.mora: mp0[i] for i, s in enumerate(segs0) if s.kind == "v"}
    tt_r, lf_r = f0_contour(ctx.xr)
    ref_vp, ref_nv = {}, {}
    for m, (a, b) in spans.items():
        ref_vp[m], ref_nv[m] = vowel_pitch(tt_r, lf_r, a, b)
    voiceless = {m for m in range(n_mora) if ref_nv[m] < 3 or np.isnan(ref_vp[m])}
    init = {m: LEVELS[snap(ref_vp[m])[0]] if m not in voiceless else 0.0 for m in range(n_mora)}
    log(f"無声のモーラ: {sorted(voiceless)}; 初期の高さ(丸め): {''.join(snap(ref_vp[m])[0] if m not in voiceless else '-' for m in range(n_mora))}")
    ttf, lff = f0_contour(ctx.xr, fast=True)
    ref_vp_fast = mora_pitches(segs0, ttf, lff, spans)
    pitches = search_levels(ctx, t, init, ref_vp_fast, voiceless, rounds=2, verbose=verbose)

    # --- 3) 再合成と検証
    q = make_query(ctx.qr, speed, t, pitches, voiceless)
    segs, mp, x, blocks = ctx.measure(q)
    ar_err = start_errors(segs, mp, n_mora)
    tt_x, lf_x = f0_contour(x)
    n = min(len(lf_r), len(lf_x))
    both = ~np.isnan(lf_r[:n]) & ~np.isnan(lf_x[:n])
    f0_corr = float(np.corrcoef(lf_r[:n][both], lf_x[:n][both])[0, 1])
    # 母音の中だけ（子音との境目の F0 推定ゆれを除く）
    vmask = np.zeros(n, bool)
    for s in segs:
        if s.kind == "v":
            vmask |= (tt_r[:n] >= s.start + 0.2 * (s.end - s.start)) & (tt_r[:n] <= s.end - 0.2 * (s.end - s.start))
    bv = both & vmask
    f0_corr_v = float(np.corrcoef(lf_r[:n][bv], lf_x[:n][bv])[0, 1])
    f0_rmse_v = float(np.sqrt(np.mean((lf_r[:n][bv] - lf_x[:n][bv]) ** 2)))
    rec_vp = {s.mora: vowel_pitch(tt_x, lf_x, s.start, s.end)[0] for s in segs if s.kind == "v"}
    mora_corr = float(np.corrcoef([ref_vp[m] for m in range(n_mora) if m not in voiceless],
                                  [rec_vp[m] for m in range(n_mora) if m not in voiceless])[0, 1])
    vk.save_wav(out_dir / "reconstructed.wav", x)

    # --- 4) 表
    segs_d, _, _ = query_segments(qd)
    d_idx = {(s.kind, s.mora): s for s in segs_d}
    idx = {(s.kind, s.mora): i for i, s in enumerate(segs)}
    midx = {(s.kind, s.mora): i for i, s in enumerate(segs_def)}
    moras = all_moras(q)
    phrase_of, pos_of, acc_of = [], [], []
    for pi, p in enumerate(ctx.qr.query.accent_phrases):
        for k in range(len(p.moras)):
            phrase_of.append(pi)
            pos_of.append(k + 1)
            acc_of.append(p.accent)
    ku_of = [next(k for k, (lo, hi) in enumerate(ctx.mrs) if lo <= m < hi) for m in range(n_mora)]
    rows = []
    for m in range(n_mora):
        has_c = ("c", m) in idx
        first = idx[("c", m)] if has_c else idx[("v", m)]
        sv = segs[idx[("v", m)]]
        c_ms = (segs[idx[("c", m)]].end - segs[idx[("c", m)]].start) * 1000 if has_c else 0.0
        v_ms = (sv.end - sv.start) * 1000
        dc = (d_idx[("c", m)].end - d_idx[("c", m)].start) * 1000 if has_c else 0.0
        dv = (d_idx[("v", m)].end - d_idx[("v", m)].start) * 1000
        # DTW が直接示す比（既定 → お手本）。子音と母音の境目は ±1 フレーム程度ゆれる
        a_c, b_c = mp_def[midx[("c", m)]] if has_c else (0, 0)
        a_v, b_v = mp_def[midx[("v", m)]]
        dtw_c = (b_c - a_c) * 1000 if has_c else 0.0
        dtw_v = (b_v - a_v) * 1000
        vp = ref_vp[m]
        lvl, res = snap(vp) if m not in voiceless else (None, None)
        chosen = [k for k, v in LEVELS.items() if abs(v - pitches[m]) < 1e-9]
        lengthened = ("v", m) in main_free
        rows.append({
            "mora": m, "kana": ctx.qr.units[m].text, "katakana": moras[m].text, "ku": ku_of[m] + 1,
            "consonant": moras[m].consonant, "vowel": moras[m].vowel,
            "start_ms": round(segs[first].start * 1000), "end_ms": round(sv.end * 1000),
            "vowel_start_ms": round(sv.start * 1000),
            "consonant_ms": round(c_ms), "vowel_ms": round(v_ms),
            "default_consonant_ms": round(dc), "default_vowel_ms": round(dv),
            "ratio_consonant": round(c_ms / dc, 2) if has_c and dc else None,
            "ratio_vowel": round(v_ms / dv, 2),
            "dtw_ratio_consonant": round(dtw_c / dc, 2) if has_c and dc else None,
            "dtw_ratio_vowel": round(dtw_v / dv, 2),
            "lengthened": lengthened,
            "stored_vowel_length_s": round(moras[m].vowel_length, 3),
            "stored_consonant_length_s": None if moras[m].consonant is None else round(moras[m].consonant_length, 3),
            "ref_ln_f0": None if np.isnan(vp) else round(vp, 3),
            "ref_f0_hz": None if np.isnan(vp) else round(float(np.exp(vp)), 1),
            "voiced_frames": ref_nv[m],
            "level": None if m in voiceless else chosen[0],
            "level_by_snap": lvl,
            "level_value": None if m in voiceless else pitches[m],
            "residual": None if res is None else round(res, 3),
            "recon_ln_f0": None if np.isnan(rec_vp[m]) else round(rec_vp[m], 3),
            "residual_vs_recon": None if np.isnan(rec_vp[m]) or np.isnan(vp) else round(vp - rec_vp[m], 3),
            "start_err_ms": round(ar_err[m] * 1000),
            "accent_phrase": phrase_of[m], "pos_in_phrase": pos_of[m], "phrase_accent": acc_of[m],
            "default_pitch": round(ctx.qr.query.accent_phrases[phrase_of[m]].moras[pos_of[m] - 1].pitch, 2),
        })
    pauses_ref = [round((ctx.br[k + 1][0] - ctx.br[k][1]) * H, 3) for k in range(len(ctx.br) - 1)]
    pause_q = {k: round(stored_for_frames(n_, speed), 3) for k, n_ in t.pause.items()}
    dtw_all = [abs(e) * 1000 for e in ar_err]
    result = {
        "text": {"kanji": TEXT_KANJI, "reading": TEXT_READING},
        "voice": {"character": CHARACTER, "style": STYLE, "style_id": ctx.style.id, "speed_scale": speed},
        "reference": {"duration_s": round(total_ref, 3),
                      "speech_blocks_s": [[round(a * H, 3), round(b * H, 3)] for a, b in ctx.br],
                      "lead_silence_s": round(ctx.br[0][0] * H, 3),
                      "trail_silence_s": round(total_ref - ctx.br[-1][1] * H, 3),
                      "silent_gaps_between_blocks_s": pauses_ref},
        "fitted": {"pre_phoneme_length_s": round(q.pre_phoneme_length, 3),
                   "post_phoneme_length_s": round(q.post_phoneme_length, 3),
                   "pause_after_ku_stored_s": pause_q,
                   "pause_after_ku_real_s": {k: round(n_ / FPS, 3) for k, n_ in t.pause.items()},
                   "lengthened_main": sorted(main_free), "free_extra": sorted({m for _, m in free_all} - main_free),
                   "consonant_stretch_notes": notes},
        "verification": {"recon_duration_s": round(len(x) / SR, 3), "ref_duration_s": round(total_ref, 3),
                         "mora_start_err_ms_mean_abs": round(float(np.mean(dtw_all)), 1),
                         "mora_start_err_ms_max_abs": round(float(np.max(dtw_all)), 1),
                         "block_start_err_ms": [round((ctx.br[k][0] - blocks[k][0]) * H * 1000) for k in range(len(blocks))],
                         "block_end_err_ms": [round((ctx.br[k][1] - blocks[k][1]) * H * 1000) for k in range(len(blocks))],
                         "f0_corr_all_voiced_frames": round(f0_corr, 3),
                         "f0_corr_vowel_frames": round(f0_corr_v, 3),
                         "f0_rmse_ln_vowel_frames": round(f0_rmse_v, 3),
                         "vowel_level_corr_per_mora": round(mora_corr, 4),
                         "level_sequence": "".join(r["level"] or "-" for r in rows),
                         "level_sequence_by_snap": "".join(r["level_by_snap"] or "-" for r in rows),
                         "feature_distance": round(feature_distance(ctx, x), 3)},
        "ojt": [{"ku": i + 1, "kanji": k.kanji, "reading": k.reading, "fallback": k.fallback,
                 "openjtalk": k.ojt_kana, "used": k.aquestalk} for i, k in enumerate(ctx.qr.ku)],
        "morae": rows,
        "audio_query": dataclasses.asdict(q),
    }
    (out_dir / "analysis.json").write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    return result


def print_tables(res: dict) -> None:
    rows = res["morae"]
    print(f"参照 {res['reference']['duration_s']}s / 再合成 {res['verification']['recon_duration_s']}s")
    for ku in range(1, 6):
        print(f"\n### 第{ku}句")
        print("| # | かな | 開始ms | 子音ms | 母音ms | 比(c/v) | F0 Hz | ln F0 | 高さ | 残差 | 開始誤差ms |")
        print("|---|---|---|---|---|---|---|---|---|---|---|")
        for r in rows:
            if r["ku"] != ku:
                continue
            rc = "-" if r["ratio_consonant"] is None else r["ratio_consonant"]
            star = "*" if r["lengthened"] else ""
            print(f"| {r['mora']} | {r['kana']} | {r['start_ms']} | {r['consonant_ms']} | {r['vowel_ms']}{star} | "
                  f"{rc}/{r['ratio_vowel']} | {r['ref_f0_hz']} | {r['ref_ln_f0']} | {r['level']} | {r['residual']} | {r['start_err_ms']:+d} |")


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ref_wav", type=Path)
    ap.add_argument("out_dir", type=Path)
    ap.add_argument("--speed", type=float, default=0.9)
    a = ap.parse_args(argv)
    res = analyze(a.ref_wav, a.out_dir, a.speed)
    print_tables(res)
    print(json.dumps({k: res[k] for k in ("reference", "fitted", "verification")}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
