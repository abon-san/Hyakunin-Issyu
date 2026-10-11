#!/usr/bin/env python3
"""VOICEVOX CORE で百人一首を競技かるた式に読み上げる音声を作るモジュール。

実行は setup_voicevox.sh が作る venv で行う:
    tools/voice-src/venv/bin/python -I tools/voice/make_samples.py

流れ（1 首 = 上の句 + 間 + 下の句 + 余韻）:
  1. build_query(): 句ごとに漢字かな交じり文を OpenJTalk で解析し、モーラ列を
     読み（現代仮名遣いのひらがな）と照合する。ずれた句だけ、読みからカタカナ
     （AquesTalk 風記法）でアクセント句を作り直す。最終モーラ列が読みと一致
     することを assert する。
  2. shape_verse(): 競技かるた式の整形（句間の間、上の句末の延ばし、下の句の
     「最後の二字の前」を少し延ばす、下の句末の余韻）を AudioQuery に施し、
     speed_scale を二分探索して上の句約 6 秒・下の句約 5 秒に合わせる。
  3. synthesize_poem(): 合成して WAV 用の波形と、読みの各文字のモーラ開始時刻
     （ミリ秒）を返す。

時間の定義:
  * 上の句の長さ = 最初のモーラの開始 ～ 最後のモーラ（延ばし含む）の終わり。
  * 下の句の長さ = 最初のモーラの開始 ～ 最後のモーラの母音の開始（余韻を除く）。
  * 余韻 = 最後のモーラの母音の開始から 3 秒（母音を約 1.5 秒保持し徐々に小さくして、残りは無音）。
  * 間 = 上の句の終わりの音から下の句の最初の音までの無音（既定 1.0 秒）。
  * 句間の間・延ばしなどは「実時間の秒」で指定する（speed_scale には左右されない）。
"""
from __future__ import annotations

import io
import unicodedata
import wave
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from voicevox_core import AccentPhrase, AudioQuery, Mora
from voicevox_core.blocking import Onnxruntime, OpenJtalk, Synthesizer, VoiceModelFile

HERE = Path(__file__).resolve().parent
SRC = HERE.parent / "voice-src"
FRAME_RATE = 24000 / 256  # VOICEVOX のフレームレート（93.75 fps）
SAMPLE_RATE = 24000
SAMPLES_PER_FRAME = 256
SPACES = " 　\t"

# ---------------------------------------------------------------- 読みの単位


def _kata(s: str) -> str:
    return "".join(chr(ord(c) + 0x60) if "ぁ" <= c <= "ゖ" else c for c in s)


def _norm_pron(s: str) -> str:
    """発音上同じ字を寄せる（ヂ→ジ、ヅ→ズ、ヲ→オ）。"""
    return s.translate(str.maketrans({"ヂ": "ジ", "ヅ": "ズ", "ヲ": "オ"}))


_SMALL = set("ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ")
_VOWELS = set("あいうえお")
_ALT = {  # 読みの 1 文字 → 発音として許す別表記（モーラ側の text）
    "は": {"ワ"}, "へ": {"エ"}, "を": {"オ"},
    "あ": {"ー"}, "い": {"ー"}, "う": {"ー"}, "え": {"ー"}, "お": {"ー"},
    "ー": {"ア", "イ", "ウ", "エ", "オ"},
}


@dataclass
class Unit:
    """読みの 1 モーラ分。小さい ゃゅょ などは直前の字と 1 単位。"""
    text: str                # ひらがな（例: 'きゃ'）
    idxs: list[int]          # 読み全体（空白を除く）での文字位置
    ku: int                  # 何番目の句か

    def cands(self) -> set[str]:
        c = {_norm_pron(_kata(self.text))}
        if len(self.text) == 1 and self.text in _ALT:
            c |= _ALT[self.text]
        return c


def split_reading(reading: str) -> list[str]:
    """U+3000（または空白）で句に分ける。"""
    return [k for k in reading.replace("　", " ").split(" ") if k]


def reading_units(reading: str) -> tuple[list[Unit], int]:
    """読みをモーラ単位に分ける。戻り値は (単位列, 空白を除いた文字数)。"""
    units: list[Unit] = []
    n = 0
    for ku, part in enumerate(split_reading(reading)):
        for ch in part:
            if ch in _SMALL and units and units[-1].ku == ku:
                units[-1].text += ch
                units[-1].idxs.append(n)
            else:
                units.append(Unit(ch, [n], ku))
            n += 1
    return units, n


def mora_compatible(unit: Unit, mora_text: str) -> bool:
    return _norm_pron(mora_text) in unit.cands()


def check_match(units: list[Unit], moras: list[Mora]) -> list[str]:
    """読みとモーラ列の食い違いを返す（空なら一致）。"""
    problems = []
    if len(units) != len(moras):
        problems.append(f"モーラ数が違う: 読み {len(units)} / 音声 {len(moras)}")
    for i, (u, m) in enumerate(zip(units, moras)):
        if not mora_compatible(u, m.text):
            problems.append(f"#{i} 読み『{u.text}』 / 音声『{m.text}』")
    return problems


def align(units: list[Unit], moras: list[Mora]) -> list[tuple[int | None, int | None]]:
    """読みの単位とモーラ列を編集距離で対応づける（(単位番号, モーラ番号)、欠けは None）。"""
    n, m = len(units), len(moras)
    INF = 10**9
    cost = [[INF] * (m + 1) for _ in range(n + 1)]
    back: list[list[tuple[int, int] | None]] = [[None] * (m + 1) for _ in range(n + 1)]
    cost[0][0] = 0
    for i in range(n + 1):
        for j in range(m + 1):
            c = cost[i][j]
            if c >= INF:
                continue
            if i < n and j < m:
                w = 0 if mora_compatible(units[i], moras[j].text) else 1
                if c + w < cost[i + 1][j + 1]:
                    cost[i + 1][j + 1] = c + w
                    back[i + 1][j + 1] = (i, j)
            if i < n and c + 1 < cost[i + 1][j]:
                cost[i + 1][j] = c + 1
                back[i + 1][j] = (i, j)
            if j < m and c + 1 < cost[i][j + 1]:
                cost[i][j + 1] = c + 1
                back[i][j + 1] = (i, j)
    pairs: list[tuple[int | None, int | None]] = []
    i, j = n, m
    while (i, j) != (0, 0):
        pi, pj = back[i][j]  # type: ignore[misc]
        pairs.append((pi if i != pi else None, pj if j != pj else None))
        i, j = pi, pj
    return pairs[::-1]


# ---------------------------------------------------------------- エンジン


def _flat_moras(phrases: list[AccentPhrase]) -> list[Mora]:
    return [m for p in phrases for m in p.moras]


def _clone_mora(m: Mora) -> Mora:
    return Mora(text=m.text, vowel=m.vowel, vowel_length=m.vowel_length, pitch=m.pitch,
                consonant=m.consonant, consonant_length=m.consonant_length)


def _clone_phrases(phrases: list[AccentPhrase]) -> list[AccentPhrase]:
    out = []
    for p in phrases:
        pm = p.pause_mora
        out.append(AccentPhrase(
            moras=[_clone_mora(m) for m in p.moras], accent=p.accent,
            pause_mora=None if pm is None else _clone_mora(pm),
            is_interrogative=p.is_interrogative))
    return out


def _pause_mora(length: float) -> Mora:
    return Mora(text="、", vowel="pau", vowel_length=length, pitch=0.0,
                consonant=None, consonant_length=None)


@dataclass
class Style:
    character: str
    style: str
    id: int
    type: str


@dataclass
class KuResult:
    kanji: str
    reading: str
    fallback: bool
    aquestalk: str          # 実際に使ったアクセント句（フォールバック時は読みから作った記法）
    ojt_kana: str           # OpenJTalk の解析結果（AquesTalk 風）
    problems: list[str]     # OpenJTalk 解析と読みの食い違い（一致なら空）


@dataclass
class QueryResult:
    query: AudioQuery
    units: list[Unit]
    n_chars: int
    ku: list[KuResult]
    style_id: int
    ku_phrase_end: list[int]  # 各句の最後のアクセント句の番号

    @property
    def used_fallback(self) -> bool:
        return any(k.fallback for k in self.ku)

    @property
    def aquestalk(self) -> str:
        return "、".join(k.aquestalk for k in self.ku)


def _kana_of(phrases: list[AccentPhrase]) -> str:
    out = []
    for p in phrases:
        s = ""
        for i, m in enumerate(p.moras, 1):
            s += m.text + ("'" if i == p.accent else "")
        out.append(s)
    return "/".join(out)


class Engine:
    """シンセサイザーの読み込みと、声（キャラクター×スタイル）の検索。"""

    def __init__(self, src: Path = SRC, vvm_ids: list[int | str] | None = None):
        lib = sorted((src / "onnxruntime" / "lib").glob("libvoicevox_onnxruntime.so.*"))[-1]
        ort = Onnxruntime.load_once(filename=str(lib))
        self.synth = Synthesizer(ort, OpenJtalk(str(src / "open_jtalk_dic")))
        models = src / "models"
        paths = ([models / f"{i}.vvm" for i in vvm_ids] if vvm_ids
                 else sorted(models.glob("*.vvm"), key=lambda p: (len(p.stem), p.stem)))
        for p in paths:
            self.synth.load_voice_model(VoiceModelFile.open(str(p)))

    # ---- 声の一覧と検索（スタイル ID は決め打ちしない）
    def list_styles(self) -> list[Style]:
        out = []
        for meta in self.synth.metas():
            for st in meta.styles:
                if st.type == "talk":
                    out.append(Style(meta.name, st.name, int(st.id), "talk"))
        return out

    def find_style(self, character: str, style: str | None = None,
                   prefer: tuple[str, ...] = ("ノーマル", "ふつう")) -> Style:
        nk = lambda s: unicodedata.normalize("NFKC", s)  # 全角アンダースコア等の揺れを吸収
        cands = [s for s in self.list_styles() if nk(s.character) == nk(character)]
        if not cands:
            raise KeyError(f"キャラクターが見つからない: {character}")
        if style is not None:
            for s in cands:
                if nk(s.style) == nk(style):
                    return s
            raise KeyError(f"{character} にスタイル『{style}』は無い: {[s.style for s in cands]}")
        for p in prefer:
            for s in cands:
                if s.style == p:
                    return s
        return cands[0]

    # ---- 1) クエリ作成
    def build_query(self, kanji_text: str, reading_kana: str, style_id: int) -> QueryResult:
        """漢字かな交じり文 → AudioQuery。モーラ列は読みと一致していることを assert 済み。"""
        kanjis = split_reading(kanji_text)
        readings = split_reading(reading_kana)
        assert len(kanjis) == len(readings), "漢字文と読みで句の数が違う"
        units, n_chars = reading_units(reading_kana)
        all_phrases: list[AccentPhrase] = []
        ku_end: list[int] = []
        kus: list[KuResult] = []
        for k, (kj, rd) in enumerate(zip(kanjis, readings)):
            ku_units = [u for u in units if u.ku == k]
            ojt = self.synth.create_accent_phrases(kj, style_id)
            for p in ojt:
                p.pause_mora = None
            problems = check_match(ku_units, _flat_moras(ojt))
            if not problems:
                phrases, fallback, aq = ojt, False, _kana_of(ojt)
            else:
                phrases, aq = self._from_reading(ku_units, ojt, style_id)
                fallback = True
                left = check_match(ku_units, _flat_moras(phrases))
                assert not left, f"フォールバックでも不一致: {left}"
            kus.append(KuResult(kj, rd, fallback, aq, _kana_of(ojt), problems))
            all_phrases += phrases
            ku_end.append(len(all_phrases) - 1)
        # 文脈（上の句／下の句全体）で長さと高さを予測し直す
        all_phrases = self.synth.replace_mora_data(all_phrases, style_id)
        for p in all_phrases:
            p.pause_mora = None
        q = AudioQuery(accent_phrases=all_phrases, speed_scale=1.0, pitch_scale=0.0,
                       intonation_scale=1.0, volume_scale=1.0, pre_phoneme_length=0.1,
                       post_phoneme_length=0.1, output_sampling_rate=SAMPLE_RATE,
                       output_stereo=False)
        left = check_match(units, _flat_moras(q.accent_phrases))
        assert not left, f"最終クエリのモーラ列が読みと不一致: {left}"
        return QueryResult(q, units, n_chars, kus, style_id, ku_end)

    def _from_reading(self, units: list[Unit], ojt: list[AccentPhrase], style_id: int
                      ) -> tuple[list[AccentPhrase], str]:
        """読みからカタカナ記法でアクセント句を作る。アクセントは OpenJTalk の結果から可能な限り引き継ぐ。"""
        moras = _flat_moras(ojt)
        pairs = align(units, moras) if moras else [(i, None) for i in range(len(units))]
        pid_of_mora, pos_of_mora = [], []
        for pi, p in enumerate(ojt):
            for pos in range(len(p.moras)):
                pid_of_mora.append(pi)
                pos_of_mora.append(pos)
        unit_pid: list[int | None] = [None] * len(units)
        unit_of_mora: dict[int, int] = {}
        for i, j in pairs:
            if i is not None and j is not None:
                unit_pid[i] = pid_of_mora[j]
                unit_of_mora[j] = i
        last = 0
        for i in range(len(units)):  # 対応のない単位は直前の句に入れる
            if unit_pid[i] is None:
                unit_pid[i] = last
            last = unit_pid[i]  # type: ignore[assignment]
        first = next((x for x in unit_pid if x is not None), 0)
        unit_pid = [first if x is None else x for x in unit_pid]
        # 発音（は→ワ、へ→エ は OpenJTalk と対応が取れた所はそれに従い、他は句末のみ助詞扱い）
        mora_of_unit = {i: j for j, i in unit_of_mora.items()}
        pron: list[str] = []
        for i, u in enumerate(units):
            base = _norm_pron(_kata(u.text))
            if u.text in ("は", "へ"):
                if i in mora_of_unit and moras[mora_of_unit[i]].text in ("ハ", "ワ", "ヘ", "エ"):
                    base = moras[mora_of_unit[i]].text
                elif i == len(units) - 1:
                    base = "ワ" if u.text == "は" else "エ"
            pron.append(base)
        groups: list[list[int]] = []
        for i, pid in enumerate(unit_pid):
            if groups and unit_pid[groups[-1][0]] == pid:
                groups[-1].append(i)
            else:
                groups.append([i])
        parts = []
        for g in groups:
            pid = unit_pid[g[0]]
            acc = len(g)  # 既定は平板（最後のモーラにアクセント）
            if ojt and pid is not None and pid < len(ojt):
                ph = ojt[pid]
                if ph.accent < len(ph.moras):
                    base_j = sum(len(p.moras) for p in ojt[:pid]) + ph.accent - 1
                    i = unit_of_mora.get(base_j)
                    if i is None:  # 対応のないモーラなら手前の対応づいたものを探す
                        for jj in range(base_j - 1, -1, -1):
                            if jj in unit_of_mora:
                                i = unit_of_mora[jj]
                                break
                    acc = g.index(i) + 1 if i in g else min(ph.accent, len(g))
            s = ""
            for pos, i in enumerate(g, 1):
                s += pron[i] + ("'" if pos == acc else "")
            parts.append(s)
        kana = "/".join(parts)
        return self.synth.create_accent_phrases_from_kana(kana, style_id), kana


# ---------------------------------------------------------------- 競技かるた式の整形


@dataclass
class KarutaParams:
    kami_target_s: float = 6.0       # 上の句の長さ（最初のモーラ開始～最後のモーラ終わり）
    shimo_target_s: float = 5.0      # 下の句の長さ（余韻を除く）
    ku_pause_s: float = 0.30         # 句の間（実時間）
    kami_end_hold_s: float = 0.40    # 上の句の最後のモーラを延ばす量（実時間・追加）
    shimo_penult_stretch_s: float = 0.15  # 下の句「最後の二字の前」を延ばす量（実時間・追加）
    yoin_hold_s: float = 1.5         # 余韻: 最後の母音の保持時間
    yoin_total_s: float = 3.0        # 余韻: 最後の母音の開始から無音の終わりまで
    yoin_fade_delay_s: float = 0.3   # 余韻: 保持開始からこの時間後にフェードアウトを始める
    ma_s: float = 1.0                # 上の句と下の句の間
    pre_s: float = 0.05              # 各句の前後に付ける余白（実時間）
    post_s: float = 0.05
    intonation_scale: float = 1.0
    pitch_scale: float = 0.0
    volume_scale: float = 1.0
    speed_min: float = 0.3
    speed_max: float = 2.0


@dataclass
class Timeline:
    starts: list[float]      # 各モーラの開始（秒、クリップ先頭から）
    ends: list[float]
    vowel_starts: list[float]
    total: float             # post まで含めた長さ


def _frames(sec: float, speed: float) -> int:
    # コアは「まず速度 1 でフレームに丸め、速度で割ってもう一度丸める」（実測で確認）。f32 で計算する。
    f = np.float32
    n = np.floor(f(sec) * f(FRAME_RATE) + f(0.5))
    return int(np.floor(f(n) / f(speed) + f(0.5)))


def timeline(q: AudioQuery) -> Timeline:
    """エンジンと同じフレーム丸めで、各モーラの時刻を求める。"""
    s = q.speed_scale
    t = _frames(q.pre_phoneme_length, s)
    starts, ends, vstarts = [], [], []
    for p in q.accent_phrases:
        for m in p.moras:
            starts.append(t / FRAME_RATE)
            if m.consonant is not None:
                t += _frames(m.consonant_length or 0.0, s)
            vstarts.append(t / FRAME_RATE)
            t += _frames(m.vowel_length, s)
            ends.append(t / FRAME_RATE)
        if p.pause_mora is not None:
            t += _frames(p.pause_mora.vowel_length, s)
    t += _frames(q.post_phoneme_length, s)
    return Timeline(starts, ends, vstarts, t / FRAME_RATE)


def shape_verse(qr: QueryResult, kind: str, params: KarutaParams, speed: float) -> AudioQuery:
    """指定の speed_scale で競技かるた式に整形した AudioQuery を返す。kind は 'kami' か 'shimo'。"""
    phrases = _clone_phrases(qr.query.accent_phrases)
    for k, pi in enumerate(qr.ku_phrase_end):
        if k < len(qr.ku_phrase_end) - 1:  # 句の間（実時間 → 保存値は speed 倍）
            phrases[pi].pause_mora = _pause_mora(params.ku_pause_s * speed)
    moras = _flat_moras(phrases)
    last = moras[-1]
    last.vowel = last.vowel.lower()  # 無声化していたら有声に戻して保持できるようにする
    if kind == "kami":
        last.vowel_length += params.kami_end_hold_s * speed
    elif kind == "shimo":
        if len(moras) >= 3:  # 最後の二字（二モーラ）の前
            moras[-3].vowel_length += params.shimo_penult_stretch_s * speed
        last.vowel_length = params.yoin_hold_s * speed
    else:
        raise ValueError(kind)
    return AudioQuery(
        accent_phrases=phrases, speed_scale=speed, pitch_scale=params.pitch_scale,
        intonation_scale=params.intonation_scale, volume_scale=params.volume_scale,
        pre_phoneme_length=params.pre_s * speed, post_phoneme_length=params.post_s * speed,
        output_sampling_rate=SAMPLE_RATE, output_stereo=False)


def verse_duration(q: AudioQuery, kind: str) -> float:
    tl = timeline(q)
    end = tl.ends[-1] if kind == "kami" else tl.vowel_starts[-1]
    return end - tl.starts[0]


def fit_speed_candidates(qr: QueryResult, kind: str, params: KarutaParams) -> list[float]:
    """目標の長さに近い speed_scale の候補を近い順に返す。

    長さはフレームの丸めで階段状（単調とは限らない）なので、二分探索で見当をつけてから
    前後 ±2% を 0.05% 刻みで調べ、目標との差が小さいものから並べる。"""
    target = params.kami_target_s if kind == "kami" else params.shimo_target_s
    lo, hi = params.speed_min, params.speed_max
    for _ in range(40):
        mid = (lo + hi) / 2
        if verse_duration(shape_verse(qr, kind, params, mid), kind) > target:
            lo = mid
        else:
            hi = mid
    s0 = (lo + hi) / 2
    cands = []
    for k in range(-40, 41):
        s = round(s0 * (1 + k * 0.0005), 4)
        err = abs(verse_duration(shape_verse(qr, kind, params, s), kind) - target)
        cands.append((round(err, 3), abs(k), s))
    return [s for _, _, s in sorted(set(cands))]


def fit_speed(qr: QueryResult, kind: str, params: KarutaParams) -> tuple[float, AudioQuery]:
    s = fit_speed_candidates(qr, kind, params)[0]
    return s, shape_verse(qr, kind, params, s)


# ---------------------------------------------------------------- 合成と組み立て


def _wav_to_float(data: bytes) -> np.ndarray:
    with wave.open(io.BytesIO(data)) as w:
        assert w.getnchannels() == 1 and w.getframerate() == SAMPLE_RATE
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    return x.astype(np.float64) / 32768.0


def _fade_out(x: np.ndarray, start: int, end: int) -> None:
    """[start, end) をコサインで 1→0 に（余韻の母音を徐々に小さくする）。"""
    n = end - start
    if n <= 0:
        return
    x[start:end] *= 0.5 * (1 + np.cos(np.pi * np.arange(n) / n))
    x[end:] = 0.0


@dataclass
class PoemAudio:
    samples: np.ndarray              # float64, -1..1, 24 kHz mono
    speed_kami: float
    speed_shimo: float
    durations: dict                  # kami / ma / shimo / yoin / total（秒）
    kana_ms_kami: list[int]          # 上の句の読みの各文字の開始（ms, クリップ先頭から）
    kana_ms_shimo: list[int]
    kami: QueryResult
    shimo: QueryResult
    kami_range_s: tuple[float, float] = field(default=(0.0, 0.0))   # 声の出ている区間
    shimo_range_s: tuple[float, float] = field(default=(0.0, 0.0))
    # 各モーラの (開始, 母音の開始, 終わり) 秒（クリップ先頭から）。検査用
    kami_spans: list[tuple[float, float, float]] = field(default_factory=list)
    shimo_spans: list[tuple[float, float, float]] = field(default_factory=list)


def _kana_times(qr: QueryResult, tl: Timeline, offset_s: float) -> list[int]:
    out = [0] * qr.n_chars
    assert len(tl.starts) == len(qr.units)
    for u, st in zip(qr.units, tl.starts):
        for i in u.idxs:
            out[i] = int(round((offset_s + st) * 1000))
    return out


def _fit_and_render(engine: Engine, qr: QueryResult, kind: str, p: KarutaParams, style_id: int):
    """速度を合わせて合成する。実際の波形の長さが timeline() の予測と 1 フレーム以内で合うことを確認し、
    丸めの境目で外れた候補は捨てて次の候補を使う（時刻表の正確さを保証するため）。"""
    for s in fit_speed_candidates(qr, kind, p)[:30]:
        q = shape_verse(qr, kind, p, s)
        x = _wav_to_float(engine.synth.synthesis(q, style_id, enable_interrogative_upspeak=False))
        tl = timeline(q)
        if abs(len(x) - tl.total * SAMPLE_RATE) <= SAMPLES_PER_FRAME / 2:
            return s, q, x, tl
    raise AssertionError("timeline が実際の長さと合わない")


def synthesize_poem(engine: Engine, kami_kanji: str, kami_reading: str,
                    shimo_kanji: str, shimo_reading: str, style_id: int,
                    params: KarutaParams | None = None) -> PoemAudio:
    p = params or KarutaParams()
    kami = engine.build_query(kami_kanji, kami_reading, style_id)
    shimo = engine.build_query(shimo_kanji, shimo_reading, style_id)
    sk, qk, xk, tk = _fit_and_render(engine, kami, "kami", p, style_id)
    ss, qs, xs, ts = _fit_and_render(engine, shimo, "shimo", p, style_id)
    # 余韻: 最後の母音を保持し、途中から徐々に小さくして、無音で 3 秒にそろえる
    v0 = int(round(ts.vowel_starts[-1] * SAMPLE_RATE))
    v1 = int(round(ts.ends[-1] * SAMPLE_RATE))
    _fade_out(xs, v0 + int(p.yoin_fade_delay_s * SAMPLE_RATE), v1)
    xs = xs[: v0 + int(round(p.yoin_total_s * SAMPLE_RATE))]
    if len(xs) < v0 + int(round(p.yoin_total_s * SAMPLE_RATE)):
        xs = np.pad(xs, (0, v0 + int(round(p.yoin_total_s * SAMPLE_RATE)) - len(xs)))
    # 間: 上の句の終わりの音から下の句の最初の音までを ma_s にする
    gap = p.ma_s - (len(xk) / SAMPLE_RATE - tk.ends[-1]) - ts.starts[0]
    gap_n = max(0, int(round(gap * SAMPLE_RATE)))
    x = np.concatenate([xk, np.zeros(gap_n), xs])
    off_shimo = (len(xk) + gap_n) / SAMPLE_RATE
    durations = {
        "kami": verse_duration(qk, "kami"),
        "ma": off_shimo + ts.starts[0] - tk.ends[-1],
        "shimo": verse_duration(qs, "shimo"),
        "yoin": len(xs) / SAMPLE_RATE - ts.vowel_starts[-1],
        "total": len(x) / SAMPLE_RATE,
    }
    return PoemAudio(
        x, sk, ss, durations, _kana_times(kami, tk, 0.0), _kana_times(shimo, ts, off_shimo),
        kami, shimo, (tk.starts[0], tk.ends[-1]),
        (off_shimo + ts.starts[0], off_shimo + ts.vowel_starts[-1] + p.yoin_hold_s),
        list(zip(tk.starts, tk.vowel_starts, tk.ends)),
        [(off_shimo + a, off_shimo + b, off_shimo + c)
         for a, b, c in zip(ts.starts, ts.vowel_starts, ts.ends)])


# ---------------------------------------------------------------- 音量・検査・保存


def _db(x: float) -> float:
    return 20 * np.log10(max(x, 1e-9))


def active_rms_db(x: np.ndarray, win_s: float = 0.1, gate_db: float = -45.0) -> float:
    """100 ms 窓の RMS がゲートを超える部分だけの RMS（dBFS）。"""
    n = int(win_s * SAMPLE_RATE)
    w = x[: len(x) // n * n].reshape(-1, n)
    r = np.sqrt((w ** 2).mean(axis=1))
    act = w[_db_arr(r) > gate_db]
    return _db(float(np.sqrt((act ** 2).mean()))) if len(act) else -120.0


def _db_arr(r: np.ndarray) -> np.ndarray:
    return 20 * np.log10(np.maximum(r, 1e-9))


def normalize_loudness(x: np.ndarray, target_db: float = -22.0, peak_db: float = -1.0
                       ) -> tuple[np.ndarray, float]:
    """有音区間の RMS を target_db に合わせる。ピークが peak_db を超える分は tanh で軽く丸める。"""
    g = 10 ** ((target_db - active_rms_db(x)) / 20)
    y = x * g
    ceil = 10 ** (peak_db / 20)
    if np.abs(y).max() > ceil:  # 滑らかなリミッタ（ceil を超えないように）
        knee = ceil * 0.7
        a = np.abs(y)
        over = a > knee
        y[over] = np.sign(y[over]) * (knee + (ceil - knee) * np.tanh((a[over] - knee) / (ceil - knee)))
    return y, _db(g)


def window_rms_db(x: np.ndarray, win_s: float = 0.1) -> np.ndarray:
    n = int(win_s * SAMPLE_RATE)
    w = x[: len(x) // n * n].reshape(-1, n)
    return _db_arr(np.sqrt((w ** 2).mean(axis=1)))


def save_wav(path: Path, x: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = np.round(np.clip(x, -1.0, 1.0) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(pcm.tobytes())


_default_engine: Engine | None = None


def build_query(kanji_text: str, reading_kana: str, style_id: int,
                engine: Engine | None = None) -> QueryResult:
    """モジュールの既定エンジン（全モデル読み込み）で Engine.build_query を呼ぶ簡易入口。"""
    global _default_engine
    if engine is None:
        if _default_engine is None:
            _default_engine = Engine()
        engine = _default_engine
    return engine.build_query(kanji_text, reading_kana, style_id)
