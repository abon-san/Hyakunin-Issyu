#!/usr/bin/env python3
"""競技かるたの読みの韻律ルール（KarutaRules / PACE_REF）を VOICEVOX の AudioQuery に施す。

ユーザー自身の 1 番歌の読み（tools/voice/analyze_reference.py で分析）から導いたルール。
既存の KarutaParams / PACE_A / PACE_B（voicevox_karuta.py）とは独立の、明示的なルールセットで、
そちらは変更しない。速さ・高さ・延ばし・間・余韻を「機械的に」決める（句ごとの速度合わせはしない）。

単位: 特記なき数値は AudioQuery（クエリ）の単位。vowel_length / pause は speed_scale で割られて
実時間になる（speed 0.9 なら 0.32 → 約 0.356 秒）。

ルール（KarutaRules の既定値 = PACE_REF）:
  * speed_scale 0.9 固定。pre/post_phoneme_length 0.1、intonation_scale 1.0、pitch_scale 0.0。
    子音と、印のない母音の長さはエンジンの既定値のまま。
  * 高さ（ln F0）: L=5.13 / M=5.53 / H=5.84 をモーラごとに明示。無声化母音 (A I U E O) は有声に直す。
      句1・句4・句5: 先頭 L、残り M
      句2: 先頭 2 モーラ M、3 モーラ目から最後の一つ手前まで H、最後 M（7 モーラなら MMHHHHM）
      句3: すべて M
    ポーズのモーラは 0。
  * 長さ: 下の句の最後の句を除く各句の最後のモーラ vowel_length=0.32。
    句の途中の伸ばし（nobashi.json の印）: 上の句の終わりの前（句3 の最後の印）0.18、
    四句目の途中 0.30、下の句の最後の句の「最後の二字（または三字）の前」0.30、その他 0.25。
  * 句間のポーズ 0.31。大山札（句1 の後に印がない歌）は句1-句2 の間にポーズも 0.32 も置かない。
  * 下の句の最後のモーラは母音を約 1.5 秒保持してコサインでフェードアウトし、最後の母音の開始から
    3.0 秒になるよう無音で埋める（余韻）。
  * 上の句・下の句は別々のクリップ（それぞれ前後に余白を持つ）。間はアプリ側で入れる。
  * 全クリップの有音区間 RMS を -22 dBFS にそろえ、ピークは -1 dBFS 以下。
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from voicevox_core import AccentPhrase, AudioQuery, Mora

import voicevox_karuta as vk

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
POEM_FILES = [REPO / "app/src/main/java/io/github/abonsan/hyakunin/data/PoemsPart1.kt",
              REPO / "app/src/main/java/io/github/abonsan/hyakunin/data/PoemsPart2.kt"]
NOBASHI_JSON = HERE / "nobashi.json"

OYAMA_FUDA = (31, 64, 11, 76, 15, 50)  # 句1 の後に印がなく、句2 の終わりまで一息で読む歌

JOKA_KANJI = ("難波津に　咲くやこの花　冬ごもり", "今を春べと　咲くやこの花")
JOKA_YOMI = ("なにわずに　さくやこのはな　ふゆごもり", "いまをはるべと　さくやこのはな")

# 公式の読み（全日本かるた協会）に合わせた別読み。置換は (現アプリの読み → 公式の読み)。
ALT_REPLACE = {
    (8, "shimo"): ("いう", "ゆう"),
    (22, "shimo"): ("いう", "ゆう"),
    (45, "kami"): ("いう", "ゆう"),
    (63, "shimo"): ("いう", "ゆう"),
    (76, "shimo"): ("まがう", "まごう"),
}


@dataclass(frozen=True)
class KarutaRules:
    speed_scale: float = 0.9
    pre_phoneme_length: float = 0.1
    post_phoneme_length: float = 0.1
    intonation_scale: float = 1.0
    pitch_scale: float = 0.0
    volume_scale: float = 1.0
    # ln F0
    level_L: float = 5.13
    level_M: float = 5.53
    level_H: float = 5.84
    # 長さ（クエリ単位）
    ku_end_len: float = 0.32          # 句末のモーラ（下の句の最後の句を除く）
    mark_kami_end_len: float = 0.18   # 上の句の終わりの前（句3 の最後の印）
    mark_shimo_len: float = 0.30      # 四句目の途中、下の句の終わりの前
    mark_other_len: float = 0.25      # その他の句の途中の印
    ku_pause: float = 0.31            # 句の間のポーズ（クエリ単位）
    # 余韻（実時間の秒）
    yoin_hold_s: float = 1.5
    yoin_fade_delay_s: float = 0.3
    yoin_total_s: float = 3.0
    # 音量
    target_db: float = -22.0
    peak_db: float = -1.0
    # 上の句と下の句を 1 ファイルにつなぐとき（試聴用）の無音
    trial_gap_s: float = 0.86


PACE_REF = KarutaRules()
ALL_UNVOICED = "AIUEO"


# ---------------------------------------------------------------- データ読み込み


def load_poems() -> dict[int, dict]:
    """app の PoemsPart1/2.kt から 1〜100 番の漢字文と読みを取り出す。"""
    poems: dict[int, dict] = {}
    pat = re.compile(r'no = (\d+),\s*kami = "([^"]*)",\s*shimo = "([^"]*)",\s*'
                     r'kamiYomi = "([^"]*)",\s*shimoYomi = "([^"]*)"')
    for f in POEM_FILES:
        for m in pat.finditer(f.read_text("utf-8")):
            n = int(m.group(1))
            poems[n] = {"no": n, "kami": m.group(2), "shimo": m.group(3),
                        "kamiYomi": m.group(4), "shimoYomi": m.group(5)}
    assert sorted(poems) == list(range(1, 101)), "100 首そろっていない"
    return poems


def load_nobashi() -> dict:
    return json.loads(NOBASHI_JSON.read_text("utf-8"))


# ---------------------------------------------------------------- 計画（高さ・長さ・ポーズ）


@dataclass
class Mark:
    k: int            # nobashi.json の位置（延ばすのは文字 k-1 のモーラ）
    ch: str           # 延ばす文字
    mora: int         # 延ばすモーラの番号（クリップ内）
    ku: int           # その句（0 始まり）
    kind: str         # 'ku_end' / 'kami_end' / 'shimo_mid' / 'shimo_penult' / 'other' / 'ku_end(skipped)'
    length: float | None
    note: str = ""


@dataclass
class VersePlan:
    kind: str                     # 'kami' / 'shimo'
    reading: str
    ku_roles: list[int]           # 各句の役割（句1〜句5 の番号）
    ku_sizes: list[int]           # 各句のモーラ数
    levels: str                   # モーラごとの L/M/H
    lengths: dict[int, float]     # モーラ番号 → vowel_length（クエリ単位）
    marks: list[Mark]
    pause_after: list[bool]       # 各句の後にポーズを置くか
    oyama: bool
    anomalies: list[str] = field(default_factory=list)


def ku_levels(role: int, n: int) -> str:
    """1 句ぶんの高さの並び（モーラ数 n）。"""
    if role in (1, 4, 5):
        return "L" + "M" * (n - 1)
    if role == 2:
        return "".join("M" if (i < 2 or i == n - 1) else "H" for i in range(n))
    if role == 3:
        return "M" * n
    raise ValueError(role)


def plan_verse(qr: vk.QueryResult, kind: str, reading: str, marks: list[int], oyama: bool,
               rules: KarutaRules) -> VersePlan:
    roles = [1, 2, 3] if kind == "kami" else [4, 5]
    kus = vk.split_reading(reading)
    assert len(kus) == len(roles), (kind, reading)
    ku_chars = [len(k) for k in kus]
    ku_end_k = [sum(ku_chars[:i + 1]) for i in range(len(kus))]   # 句末の印の位置
    n_chars = sum(ku_chars)
    units = qr.units
    ku_moras = [[i for i, u in enumerate(units) if u.ku == k] for k in range(len(kus))]
    assert all(ku_moras)
    levels = "".join(ku_levels(r, len(m)) for r, m in zip(roles, ku_moras))
    assert len(levels) == len(units)
    anomalies: list[str] = []
    lengths: dict[int, float] = {}
    out_marks: list[Mark] = []

    def put(mora: int, val: float, what: str):
        assert mora not in lengths, f"{kind}: モーラ {mora} に二重に長さを割り当て（{what}）"
        lengths[mora] = val

    def mora_of_char(c: int) -> int:
        for i, u in enumerate(units):
            if c in u.idxs:
                return i
        raise AssertionError(c)

    # 句末（下の句の最後の句を除く）。大山札は句1 の末にない。
    last_ku = len(kus) - 1
    for k_i, ends in enumerate(ku_end_k):
        if kind == "shimo" and k_i == last_ku:
            continue
        has_mark = ends in marks
        m = ku_moras[k_i][-1]
        if kind == "kami" and k_i == 0 and oyama:
            if has_mark:
                anomalies.append(f"大山札なのに句1 の末に印がある: {ends}")
            out_marks.append(Mark(ends, reading_chars(reading)[ends - 1], m, k_i,
                                  "ku_end(skipped)", None, "大山札: 句1-句2 は一息"))
            continue
        if not has_mark:
            anomalies.append(f"{kind} 句{k_i + 1} の末（位置 {ends}）に印がないが、規則どおり 0.32 を入れる")
        put(m, rules.ku_end_len, "句末")
        out_marks.append(Mark(ends, reading_chars(reading)[ends - 1], m, k_i, "ku_end",
                              rules.ku_end_len))
    # 句の途中の印
    interior = [k for k in marks if k not in ku_end_k]
    by_ku: dict[int, list[int]] = {}
    for k in interior:
        ch_idx = k - 1
        m = mora_of_char(ch_idx)
        assert units[m].idxs[-1] == ch_idx, f"印 {k} がモーラの途中（{units[m].text}）"
        by_ku.setdefault(units[m].ku, []).append(k)
    for k_i, ks in sorted(by_ku.items()):
        ks = sorted(ks)
        for j, k in enumerate(ks):
            m = mora_of_char(k - 1)
            is_last_in_ku = j == len(ks) - 1
            back = n_chars - k  # 終わりまで何字残るか（上の句の終わり・下の句の終わりの前の判定）
            if kind == "kami" and k_i == 2 and is_last_in_ku and back in (1, 2):
                kd, val = "kami_end", rules.mark_kami_end_len
            elif kind == "shimo" and k_i == 0:
                kd, val = "shimo_mid", rules.mark_shimo_len
            elif kind == "shimo" and k_i == 1 and is_last_in_ku and back in (2, 3):
                kd, val = "shimo_penult", rules.mark_shimo_len
            else:
                kd, val = "other", rules.mark_other_len
            note = ""
            if kd == "other":
                note = f"その他の印 (句{k_i + 1}, 終わりまで {back} 字)"
            put(m, val, kd)
            out_marks.append(Mark(k, reading_chars(reading)[k - 1], m, k_i, kd, val, note))
    out_marks.sort(key=lambda x: (x.k, x.kind))
    pause_after = []
    for k_i in range(len(kus)):
        pause_after.append(k_i < last_ku and not (kind == "kami" and k_i == 0 and oyama))
    return VersePlan(kind, reading, roles, [len(m) for m in ku_moras], levels, lengths,
                     out_marks, pause_after, oyama, anomalies)


def reading_chars(reading: str) -> str:
    return "".join(vk.split_reading(reading))


# ---------------------------------------------------------------- クエリへの適用と合成


def shape_ref(qr: vk.QueryResult, plan: VersePlan, rules: KarutaRules) -> AudioQuery:
    """KarutaRules を施した AudioQuery を返す。"""
    phrases = vk._clone_phrases(qr.query.accent_phrases)
    moras = vk._flat_moras(phrases)
    assert len(moras) == len(plan.levels)
    lv = {"L": rules.level_L, "M": rules.level_M, "H": rules.level_H}
    for i, m in enumerate(moras):
        if m.vowel in ALL_UNVOICED:
            m.vowel = m.vowel.lower()          # 無声化母音も有声に（ささやかない）
        m.pitch = lv[plan.levels[i]]
    for i, v in plan.lengths.items():
        moras[i].vowel_length = v
    for p in phrases:
        p.pause_mora = None
    for k, pi in enumerate(qr.ku_phrase_end):
        if plan.pause_after[k]:
            phrases[pi].pause_mora = vk._pause_mora(rules.ku_pause)
    if plan.kind == "shimo":
        moras[-1].vowel_length = rules.yoin_hold_s * rules.speed_scale   # 実時間 hold 秒
    return AudioQuery(
        accent_phrases=phrases, speed_scale=rules.speed_scale, pitch_scale=rules.pitch_scale,
        intonation_scale=rules.intonation_scale, volume_scale=rules.volume_scale,
        pre_phoneme_length=rules.pre_phoneme_length, post_phoneme_length=rules.post_phoneme_length,
        output_sampling_rate=vk.SAMPLE_RATE, output_stereo=False)


@dataclass
class Clip:
    samples: np.ndarray            # float64, 正規化済み
    query: AudioQuery
    plan: VersePlan
    qr: vk.QueryResult
    kana_ms: list[int]
    verse_s: float                 # 余韻を除く長さ（上: 最初のモーラ開始〜最後のモーラ終わり、下: 〜最後の母音の開始）
    yoin_s: float                  # 下の句のみ（最後の母音の開始〜クリップ末）
    gain_db: float
    spans: list[tuple[float, float, float]]   # モーラの (開始, 母音の開始, 終わり) 秒


def render_clip(engine: vk.Engine, qr: vk.QueryResult, plan: VersePlan, style_id: int,
                rules: KarutaRules) -> Clip:
    q = shape_ref(qr, plan, rules)
    tl = vk.timeline(q)
    x = vk._wav_to_float(engine.synth.synthesis(q, style_id, enable_interrogative_upspeak=False))
    err = len(x) - tl.total * vk.SAMPLE_RATE
    assert abs(err) <= vk.SAMPLES_PER_FRAME, f"timeline と実際の長さが合わない: {err} サンプル"
    if plan.kind == "shimo":
        v0 = int(round(tl.vowel_starts[-1] * vk.SAMPLE_RATE))
        v1 = int(round(tl.ends[-1] * vk.SAMPLE_RATE))
        vk._fade_out(x, v0 + int(rules.yoin_fade_delay_s * vk.SAMPLE_RATE), v1)
        n = v0 + int(round(rules.yoin_total_s * vk.SAMPLE_RATE))
        x = x[:n] if len(x) >= n else np.pad(x, (0, n - len(x)))
        verse_s = tl.vowel_starts[-1] - tl.starts[0]
        yoin_s = len(x) / vk.SAMPLE_RATE - tl.vowel_starts[-1]
    else:
        verse_s = tl.ends[-1] - tl.starts[0]
        yoin_s = 0.0
    y, gain = vk.normalize_loudness(x, rules.target_db, rules.peak_db)
    return Clip(y, q, plan, qr, vk._kana_times(qr, tl, 0.0), verse_s, yoin_s, gain,
                list(zip(tl.starts, tl.vowel_starts, tl.ends)))


# ---------------------------------------------------------------- 検査


def check_plan_vs_query(clip: Clip, default_q: AudioQuery, rules: KarutaRules) -> None:
    """最終クエリが、読み・高さ・長さ・ポーズの規則どおりであることを独立に検査する。"""
    plan, q, qr = clip.plan, clip.query, clip.qr
    moras = vk._flat_moras(q.accent_phrases)
    dmoras = vk._flat_moras(default_q.accent_phrases)
    # 1) 読みとモーラ列が一致
    left = vk.check_match(qr.units, moras)
    assert not left, f"モーラ列が読みと不一致: {left}"
    # 2) 速さなどのグローバル設定
    assert q.speed_scale == rules.speed_scale and q.intonation_scale == rules.intonation_scale
    assert q.pre_phoneme_length == rules.pre_phoneme_length
    assert q.post_phoneme_length == rules.post_phoneme_length
    # 3) 高さ: 句ごとの規則どおり、全モーラ有声
    lv = {"L": rules.level_L, "M": rules.level_M, "H": rules.level_H}
    exp = []
    for k, role in enumerate(plan.ku_roles):
        idx = [i for i, u in enumerate(qr.units) if u.ku == k]
        n = len(idx)
        if role in (1, 4, 5):
            exp += ["L"] + ["M"] * (n - 1)
        elif role == 3:
            exp += ["M"] * n
        else:
            exp += ["M", "M"] + ["H"] * max(0, n - 3) + ["M"] if n >= 3 else ["M"] * n
    assert len(exp) == len(moras)
    for i, (m, e) in enumerate(zip(moras, exp)):
        assert m.pitch == lv[e], f"モーラ {i} の高さ {m.pitch} != {lv[e]}({e})"
        assert m.vowel not in ALL_UNVOICED, f"モーラ {i} が無声のまま: {m.vowel}"
    assert "".join(exp) == plan.levels
    # 4) 長さ: 印のモーラだけが既定値から変わっている
    cum = np.cumsum([len(u.idxs) for u in qr.units])    # モーラ i の最後の文字は cum[i]-1
    expected: dict[int, float] = {}
    for mk in plan.marks:
        if mk.length is None:
            continue
        i = int(np.searchsorted(cum, mk.k))              # 文字 k-1 を含むモーラ
        assert cum[i] == mk.k, f"印 {mk.k} がモーラ境界でない"
        expected[i] = mk.length
    if plan.kind == "shimo":
        expected[len(moras) - 1] = rules.yoin_hold_s * rules.speed_scale
    for i, (m, d) in enumerate(zip(moras, dmoras)):
        if i in expected:
            assert abs(m.vowel_length - expected[i]) < 1e-9, f"モーラ {i} の長さ {m.vowel_length}"
        else:
            assert m.vowel_length == d.vowel_length, f"印のないモーラ {i} の長さが変わった"
        assert m.consonant_length == d.consonant_length
    assert set(expected) == set(plan.lengths) | ({len(moras) - 1} if plan.kind == "shimo" else set())
    # 5) ポーズ
    for k, pi in enumerate(qr.ku_phrase_end):
        pm = q.accent_phrases[pi].pause_mora
        if plan.pause_after[k]:
            assert pm is not None and pm.vowel_length == rules.ku_pause and pm.pitch == 0.0
        else:
            assert pm is None
    n_pause = sum(1 for p in q.accent_phrases if p.pause_mora is not None)
    assert n_pause == sum(plan.pause_after)
    # 6) タイミング: 単調増加、文字数と一致
    ms = clip.kana_ms
    assert len(ms) == qr.n_chars
    assert all(b >= a for a, b in zip(ms, ms[1:])), "kanaStartMs が単調でない"
    assert all(b > a for a, b in zip([u_ms for u_ms in unit_starts(qr, ms)],
                                     unit_starts(qr, ms)[1:])), "モーラの開始が増加していない"
    # 7) クリッピングなし
    pcm = np.round(clip.samples * 32767)
    assert np.abs(clip.samples).max() < 10 ** (rules.peak_db / 20) + 1e-6, "ピークが -1 dBFS を超えた"
    assert (np.abs(pcm) >= 32767).sum() == 0, "クリッピング"


def unit_starts(qr: vk.QueryResult, ms: list[int]) -> list[int]:
    return [ms[u.idxs[0]] for u in qr.units]


def default_query_of(qr: vk.QueryResult) -> AudioQuery:
    return qr.query
