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
  * 下の句の最後のモーラは余韻にする。最後の母音の開始から 3.0 秒になるよう無音で埋める。
    余韻の作り方は yoin_mode で切り替える（v2 の既定は "world"）:
      "voicevox": v1 の方式。vowel_length を 1.35 に伸ばし（実時間 1.5 秒）、0.3 秒後からコサインで
                  フェードアウト。VOICEVOX の長い母音は途中が雑音に崩れる（HNR 0〜3 dB、-20 dB）ので非推奨。
      "world"   : きれいに出る短い母音（vowel_length 0.25）の安定部を WORLD で持続させ（yoin.py）、
                  遅めに始まる指数的な減衰で消す。
      "short"   : 短い母音（vowel_length 0.25）をそのまま自然に減衰させて無音で埋める（持続音なし）。
                  yoin_short_tail_s > 0 なら、その母音を WORLD で延ばし、平らな部分なしで自然に減衰させて、
                  聞こえている長さ（ピークより 40 dB 以内）を yoin_short_tail_s 秒にする（yoin.short_tail）。
                  yoin_short_dim_start_s > 0 なら、減衰の開始をその時刻（母音の開始から）まで遅らせる（それまでは平ら）。
                  yoin_short_end_s > 0 なら、減衰が 0 になる時刻をそこに固定する（長い余韻向け）。
  * timing="rule"（既定は "default" = 上記どおり）: 競技かるたの規則の時間配分（上の句 6 秒 / 下の句 5 秒 / 余韻 3 秒 / 間合い 1.0 秒、
    初句は 1 字 0.2 秒）に合わせる。伸ばしの母音の内側を WORLD で延ばして合わせる（rule_timing.py。yoin_mode="short" が必要）。
  * 上の句・下の句は別々のクリップ（それぞれ前後に余白を持つ）。間はアプリ側で入れる。
  * 全クリップの有音区間 RMS を -22 dBFS にそろえ、ピークは -1 dBFS 以下。
    "world" / "short" では、余韻の持続音を除いた部分（最後の母音の終わりまで）でゲインを決める。
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from voicevox_core import AccentPhrase, AudioQuery, Mora

import voicevox_karuta as vk
import yoin

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
    yoin_mode: str = "world"          # "world" / "short" / "voicevox"（v1）
    yoin_total_s: float = 3.0         # 最後の母音の開始からクリップ末まで（無音で埋める）
    # yoin_mode == "voicevox"（v1）
    yoin_hold_s: float = 1.5
    yoin_fade_delay_s: float = 0.3
    # yoin_mode == "world" / "short"
    yoin_src_q: float = 0.25          # VOICEVOX に作らせる母音の長さ（クエリ単位。0.25 は穴・雑音が出ない）
    yoin_sound_s: float = 1.6         # 音が聞こえている長さ（最後の母音の開始から。"world" のみ）
    yoin_flat_s: float = 0.55         # この時刻までは平ら、それから指数的に減衰
    yoin_decay_db: float = 30.0       # flat から sound_s までに下げる量（dB）
    yoin_taper_s: float = 0.12        # 最後の 0 へのコサイン（クリックを避ける）
    yoin_drift_ln: float = -0.05      # 持続中の ln F0 のゆるやかな下降（sound_s までの合計）
    yoin_vibrato_cents: float = 8.0
    yoin_vibrato_hz: float = 5.2
    yoin_jitter_cents: float = 3.0
    yoin_tremolo_db: float = 0.6
    yoin_ap_floor: float = 0.07       # 非周期性の下駄（持続音の HNR を元の母音に合わせる。実測 21.5 dB 相当）
    # yoin_mode == "short" の延長（0 なら 新2 のまま = 短い母音が自然に消えるだけ）
    yoin_short_tail_s: float = 0.0    # 最後の母音の開始から「聞こえている」長さ（ピークより 40 dB 以内）
    yoin_short_power: float = 1.25    # 減衰の曲線: dB ∝ 時間 ** power（1 = 指数的。>1 は穏やかに始まりだんだん速く）
    yoin_short_plateau_s: float = 0.0  # 切り替えの後の平らな部分（秒。既定は無し）
    yoin_short_dim_start_s: float = 0.0  # > 0: 減衰（Dim）の開始を、最後の母音の開始からこの秒数にする（plateau_s より優先。
                                         # それまでは（ほぼ）平らに保つ = 「遅めの Dim」）
    yoin_short_end_s: float = 0.0     # > 0: 減衰が 0 になる時刻（最後の母音の開始から。長い余韻を総尺ちょうどで消すとき）
    yoin_drift_ln_short: float | None = None   # "short" の持続中の ln F0 の下降（None なら yoin_drift_ln）
    # 競技かるたの規則どおりの時間配分（timing == "rule"。rule_timing.py）。既定の "default" は従来どおり。
    timing: str = "default"            # "default" / "rule"
    rule_kami_s: float = 6.0           # 上の句（最初のモーラの開始 ～ 最後のモーラの終わり）
    rule_shimo_s: float = 5.0          # 下の句（最初のモーラの開始 ～ 最後の母音の開始。余韻を除く）
    rule_first_mora_s: float = 0.2     # 上の句の初句は 1 字これだけ（切らずに読む）
    rule_ku_pause_s: float = 0.40      # 句の間のポーズ（実時間の秒）
    rule_ma_s: float = 1.0             # 間合い（余韻のあと次の上の句の前。試聴用に上の句と下の句の間にも使う）
    rule_hold_src_cands: tuple = (0.2, 0.16, 0.25, 0.12)   # 伸ばしの印の母音をエンジンに作らせる長さの候補（クエリ単位、短いまま）。
                                   # 先頭から試し、延ばせる窓が取れた最初の長さを印ごとに使う（yoin.pick_windows）
    rule_hold_w_short: float = 0.7     # 小さい印（ｰ）の重みの倍率
    rule_hold_vibrato_cents: float = 6.0   # 延ばした母音の中のビブラート（両端でゼロになる形）
    # 音量
    target_db: float = -22.0
    peak_db: float = -1.0
    # 上の句と下の句を 1 ファイルにつなぐとき（試聴用）の無音
    trial_gap_s: float = 0.86


def yoin_last_mora_q(rules: KarutaRules) -> float:
    """下の句の最後のモーラの vowel_length（クエリ単位）。"""
    if rules.yoin_mode == "voicevox":
        return rules.yoin_hold_s * rules.speed_scale        # 実時間 hold 秒
    if rules.yoin_mode in ("world", "short"):
        return rules.yoin_src_q
    raise ValueError(f"yoin_mode: {rules.yoin_mode}")


PACE_REF = KarutaRules()
PACE_REF_V1 = KarutaRules(yoin_mode="voicevox")        # v1 の余韻（再現・比較用）
PACE_REF_SHORT = KarutaRules(yoin_mode="short")        # 短い母音を自然に減衰させるだけ
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
        moras[-1].vowel_length = yoin_last_mora_q(rules)
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
    yoin_info: dict = field(default_factory=dict)   # 余韻の作り方の記録（検査用）
    holds: list = field(default_factory=list)       # timing == "rule": 延ばした母音の記録（rule_timing.py）


def _normalize(x: np.ndarray, rules: KarutaRules, ref_end: int | None = None,
               gain_src: np.ndarray | None = None) -> tuple[np.ndarray, float]:
    """有音区間 RMS を target_db に合わせる。ref_end があれば x[:ref_end] でゲインを決める
    （余韻の持続音を数えない）。gain_src があれば、ゲインはそちらの [:ref_end] で決める（余韻を延ばしても
    ほかの部分の音量が変わらないように）。ピークが peak_db を超える分は vk.normalize_loudness と同じ tanh で丸める。"""
    if ref_end is None:
        return vk.normalize_loudness(x, rules.target_db, rules.peak_db)
    src = x if gain_src is None else gain_src
    _, gain_db = vk.normalize_loudness(src[:ref_end].copy(), rules.target_db, rules.peak_db)
    y = x * 10 ** (gain_db / 20)
    ceil = 10 ** (rules.peak_db / 20)
    if np.abs(y).max() > ceil:
        knee = ceil * 0.7
        a = np.abs(y)
        over = a > knee
        y[over] = np.sign(y[over]) * (knee + (ceil - knee) * np.tanh((a[over] - knee) / (ceil - knee)))
    return y, gain_db


def render_clip(engine: vk.Engine, qr: vk.QueryResult, plan: VersePlan, style_id: int,
                rules: KarutaRules, short_ks=frozenset()) -> Clip:
    if rules.timing == "rule":
        import rule_timing          # 循環 import を避けて、必要なときだけ読む
        return rule_timing.render_rule_clip(engine, qr, plan, style_id, rules, short_ks)
    assert rules.timing == "default", rules.timing
    q = shape_ref(qr, plan, rules)
    tl = vk.timeline(q)
    x = vk._wav_to_float(engine.synth.synthesis(q, style_id, enable_interrogative_upspeak=False))
    err = len(x) - tl.total * vk.SAMPLE_RATE
    assert abs(err) <= vk.SAMPLES_PER_FRAME, f"timeline と実際の長さが合わない: {err} サンプル"
    sr = vk.SAMPLE_RATE
    ref_end = None
    gain_src = None
    info: dict = {}
    if plan.kind == "shimo":
        v0 = int(round(tl.vowel_starts[-1] * sr))
        v1 = int(round(tl.ends[-1] * sr))
        n = v0 + int(round(rules.yoin_total_s * sr))
        if rules.yoin_mode == "voicevox":
            vk._fade_out(x, v0 + int(rules.yoin_fade_delay_s * sr), v1)
        elif rules.yoin_mode == "short":
            ref_end = v1
            if rules.yoin_short_tail_s > 0:
                gain_src = x.copy()         # 音量は、延ばす前の自然な母音で決める（新2 と同じゲイン）
                lvl = {"L": rules.level_L, "M": rules.level_M, "H": rules.level_H}[plan.levels[-1]]
                x, sinfo = yoin.short_tail(
                    x, tl.vowel_starts[-1], tl.ends[-1], lvl, rules.yoin_short_tail_s,
                    power=rules.yoin_short_power, plateau_s=rules.yoin_short_plateau_s,
                    dim_start_s=rules.yoin_short_dim_start_s or None, end_s=rules.yoin_short_end_s or None,
                    drift_ln=(rules.yoin_drift_ln if rules.yoin_drift_ln_short is None
                              else rules.yoin_drift_ln_short), vibrato_cents=rules.yoin_vibrato_cents,
                    vibrato_hz=rules.yoin_vibrato_hz, jitter_cents=rules.yoin_jitter_cents,
                    tremolo_db=rules.yoin_tremolo_db, ap_floor=rules.yoin_ap_floor)
                info = sinfo
        elif rules.yoin_mode == "world":
            lvl = {"L": rules.level_L, "M": rules.level_M, "H": rules.level_H}[plan.levels[-1]]
            y, n_valid, sinfo = yoin.sustain_vowel(
                x, tl.vowel_starts[-1], tl.ends[-1], lvl, rules.yoin_sound_s,
                drift_ln=rules.yoin_drift_ln, vibrato_cents=rules.yoin_vibrato_cents,
                vibrato_hz=rules.yoin_vibrato_hz, jitter_cents=rules.yoin_jitter_cents,
                tremolo_db=rules.yoin_tremolo_db, ap_floor=rules.yoin_ap_floor)
            ref_end = v1
            splice_end = n_valid
            end_i = v0 + int(round(rules.yoin_sound_s * sr))
            y = y[:end_i] if len(y) >= end_i else np.pad(y, (0, end_i - len(y)))
            # 持続音（切り替えの後）にだけ減衰をかける
            env = yoin.decay_envelope(len(y) - v0, 0.0, rules.yoin_flat_s, rules.yoin_decay_db,
                                      rules.yoin_sound_s, rules.yoin_taper_s)
            assert splice_end - v0 < rules.yoin_flat_s * sr, "切り替えが減衰の開始より後"
            y[v0:] *= env
            x = y
            info = dict(sinfo, v0_s=v0 / sr, splice_end_s=splice_end / sr, sound_s=rules.yoin_sound_s)
        else:
            raise ValueError(rules.yoin_mode)
        x = x[:n] if len(x) >= n else np.pad(x, (0, n - len(x)))
        verse_s = tl.vowel_starts[-1] - tl.starts[0]
        yoin_s = len(x) / sr - tl.vowel_starts[-1]
    else:
        verse_s = tl.ends[-1] - tl.starts[0]
        yoin_s = 0.0
    y, gain = _normalize(x, rules, ref_end, gain_src)
    return Clip(y, q, plan, qr, vk._kana_times(qr, tl, 0.0), verse_s, yoin_s, gain,
                list(zip(tl.starts, tl.vowel_starts, tl.ends)), info)


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
        expected[len(moras) - 1] = yoin_last_mora_q(rules)
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


def check_yoin(clip: Clip, rules: KarutaRules) -> dict:
    """下の句の余韻を独立に検査する（"world" / "short" のとき）。測った値を返す。"""
    if clip.plan.kind != "shimo" or rules.yoin_mode == "voicevox":
        return {}
    sr = vk.SAMPLE_RATE
    x = clip.samples
    v0 = clip.spans[-1][1]
    i0 = int(round(v0 * sr))
    assert len(x) == i0 + int(round(rules.yoin_total_s * sr)), "余韻を含む長さが最後の母音の開始 + 3.0 秒でない"
    assert x[-1] == 0.0 and abs(x[i0:]).max() > 0
    out: dict = {}
    # 20 ms ごとの RMS（dB）
    n = int(0.02 * sr)
    seg = x[i0:]
    nf = len(seg) // n
    e = 10 * np.log10((seg[:nf * n].reshape(nf, n) ** 2).mean(1) + 1e-12)
    if rules.yoin_mode == "short":
        k = int(np.argmax(e))
        assert (np.diff(e[k:][e[k:] > -100]) <= 6).all(), "短い余韻の減衰が単調でない"
        if rules.yoin_short_tail_s > 0:
            m = yoin.tail_metrics(x, i0, clip.yoin_info["f0"], clip.yoin_info["splice_s"] - v0,
                                  clip.yoin_info["splice_end_s"] - v0)
            m.update(yoin.dim_metrics(x, i0, clip.yoin_info["splice_end_s"] - v0, clip.yoin_info["dim_start_s"]))
            out.update(m)
            assert abs(m["audible_s"] - rules.yoin_short_tail_s) <= 0.02, f"聞こえている長さ {m['audible_s']:.3f}"
            assert abs(m["audible_fine_s"] - rules.yoin_short_tail_s) <= 0.04, f"聞こえている長さ(細) {m['audible_fine_s']:.3f}"
            if rules.yoin_short_dim_start_s > 0:
                # 平らな部分は、ビブラートに連動した ±0.6 dB 程度の揺れがある。Dim の後はならしたレベルが単調に下がる
                assert m["max_rise_db"] <= 1.5, f"平らな部分の揺れが大きい: +{m['max_rise_db']:.2f} dB / 20 ms"
                assert m["smooth_max_rise_db"] <= 0.1, f"Dim の後で音が戻る: +{m['smooth_max_rise_db']:.2f} dB"
            else:
                assert m["max_rise_db"] <= 0.5, f"減衰の途中で音が戻る: +{m['max_rise_db']:.2f} dB / 20 ms"
            assert m["hnr_deep_min"] >= 12.0, f"尾に雑音: HNR {m['hnr_deep_min']:.1f} dB"
            assert m["click_splice"] <= 1.5 * m["click_body"] + 1.0, \
                f"切り替えにクリック: {m['click_splice']:.1f} vs {m['click_body']:.1f}"
            assert m["n_clip"] == 0, "クリッピング"
        return out
    # --- world: 持続音
    f0 = clip.yoin_info["f0"]       # 持続音の実際の基本周波数（クエリの高さから 数 % ずれる）
    t_end = rules.yoin_sound_s
    # 1) 音の終わり以降は完全な無音
    assert np.abs(seg[int(round((t_end + 0.005) * sr)):]).max() == 0.0, "音の終わりのあとが無音でない"
    # 2) 平らな区間のレベルが揺れない（0.15〜flat 秒の 20 ms RMS の幅）
    a, b = int(0.15 / 0.02), int(rules.yoin_flat_s / 0.02)
    out["flat_range_db"] = float(e[a:b].max() - e[a:b].min())
    assert out["flat_range_db"] <= 3.0, f"持続音のレベルが揺れている: {out['flat_range_db']:.1f} dB"
    # 3) 減衰は単調（20 ms ごとに +1.5 dB を超えて戻らない）で、最後まで落ちる
    d = np.diff(e[b:int(t_end / 0.02)])
    out["max_rise_db"] = float(d.max())
    assert d.max() <= 1.5, f"減衰の途中で音が戻る: +{d.max():.1f} dB"
    assert e[int((t_end - 0.1) / 0.02)] <= e[a:b].mean() - rules.yoin_decay_db * 0.8, "減衰が足りない"
    # 4) 持続音は周期的（HNR が元の母音の範囲）
    seg_h = seg[int(0.25 * sr):int(0.5 * sr)]
    out["hnr_db"] = yoin.hnr_db(seg_h, f0)
    assert 12.0 <= out["hnr_db"] <= 35.0, f"持続音の HNR が範囲外: {out['hnr_db']:.1f}"
    # 5) 切り替えの前後でつながっている: 切り替えの ±10 ms の 1 サンプル差分の最大が、母音の中の差分を超えない
    sp = int(round(clip.yoin_info["splice_end_s"] * sr))
    near = np.abs(np.diff(x[sp - 240:sp + 240])).max()
    body = np.abs(np.diff(x[i0 + int(0.02 * sr):sp - 240])).max()
    out["splice_step_ratio"] = float(near / body)
    assert near <= 1.5 * body, f"切り替えで段差: {near:.4f} vs {body:.4f}"
    # 6) 切り替えの前後 20 ms の RMS の差
    pre = 10 * np.log10((x[sp - 960:sp - 480] ** 2).mean() + 1e-12)
    post = 10 * np.log10((x[sp + 480:sp + 960] ** 2).mean() + 1e-12)
    out["splice_level_step_db"] = float(post - pre)
    assert abs(post - pre) <= 2.5, f"切り替えでレベルが飛ぶ: {post - pre:.1f} dB"
    return out


def unit_starts(qr: vk.QueryResult, ms: list[int]) -> list[int]:
    return [ms[u.idxs[0]] for u in qr.units]


def default_query_of(qr: vk.QueryResult) -> AudioQuery:
    return qr.query
