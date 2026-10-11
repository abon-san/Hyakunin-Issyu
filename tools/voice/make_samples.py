#!/usr/bin/env python3
"""候補の読み手の声で 1 番歌の競技かるた式サンプルを作る。

    tools/voice-src/venv/bin/python -I tools/voice/make_samples.py [出力ディレクトリ]
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import voicevox_karuta as vk  # noqa: E402

OUT = Path("/tmp/claude-0/-home-user-Hyakunin-Issyu/cd91ef9b-aaa2-5d8e-a13b-fb5df5bbef37/scratchpad/out/voice_samples")

# (キャラクター, スタイル名。None なら ノーマル/ふつう)
CANDIDATES = [
    ("冥鳴ひまり", None),
    ("雀松朱司", None),
    ("九州そら", None),
    ("剣崎雌雄", None),
    ("青山龍星", "しっとり"),
    ("麒ヶ島宗麟", None),
    ("ナースロボ＿タイプＴ", None),
    ("No.7", "読み聞かせ"),
]
KAMI = ("秋の田の　かりほの庵の　苫をあらみ", "あきのたの　かりほのいおの　とまをあらみ")
SHIMO = ("わが衣手は　露にぬれつつ", "わがころもでは　つゆにぬれつつ")


def selftest_equivalences():
    """発音の読み替え（は→ワ、へ→エ、を→オ、う/い/え→ー、ぢ→ジ、づ→ズ、拗音は 1 モーラ）の確認。"""
    M = lambda t: vk.Mora(text=t, vowel="a", vowel_length=0.1, pitch=5.0)
    cases = [("わがころもでは", ["ワ", "ガ", "コ", "ロ", "モ", "デ", "ワ"]),
             ("おへをう", ["オ", "エ", "オ", "ー"]), ("おうい", ["オ", "ー", "ー"]),
             ("ちぢづ", ["チ", "ジ", "ズ"]), ("きょうしゃ", ["キョ", "ー", "シャ"])]
    for reading, texts in cases:
        units, _ = vk.reading_units(reading)
        assert not vk.check_match(units, [M(t) for t in texts]), (reading, texts)
    units, n = vk.reading_units("きょうしゃ")
    assert n == 5 and [u.idxs for u in units] == [[0, 1], [2], [3, 4]]
    bad, _ = vk.reading_units("あい")
    assert vk.check_match(bad, [M("ア"), M("オ")])


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else OUT
    selftest_equivalences()
    eng = vk.Engine()
    params = vk.KarutaParams()
    results, report = [], {}
    for n, (chara, sty) in enumerate(CANDIDATES, 1):
        st = eng.find_style(chara, sty)
        pa = vk.synthesize_poem(eng, *KAMI, *SHIMO, st.id, params)
        results.append((n, st, pa))
    # 音量をそろえる（有音区間の RMS を同じにして、必要ならピークを丸める）
    for n, st, pa in results:
        y, gain_db = vk.normalize_loudness(pa.samples, target_db=-22.0, peak_db=-1.0)
        path = out / f"{n:02d}_{st.character}.wav"
        vk.save_wav(path, y)
        # ---- 自己検査
        pcm = np.round(np.clip(y, -1, 1) * 32767)
        peak_db = vk._db(np.abs(y).max())
        clipped = int((np.abs(pcm) >= 32767).sum())
        spans = pa.kami_spans + pa.shimo_spans
        mora_db = []
        for a, v, e in spans:
            lo, hi = a, e  # モーラ全体（子音を含む）
            seg = y[int(lo * vk.SAMPLE_RATE): int(hi * vk.SAMPLE_RATE)]
            mora_db.append(vk._db(float(np.sqrt((seg ** 2).mean()))) if len(seg) else -180)
        ma0, ma1 = pa.kami_spans[-1][2] + 0.1, pa.shimo_spans[0][0] - 0.1
        ma_db = vk._db(float(np.abs(y[int(ma0 * vk.SAMPLE_RATE): int(ma1 * vk.SAMPLE_RATE)]).max()))
        tail_start = pa.shimo_spans[-1][1] + 1.55
        tail_peak = vk._db(float(np.abs(y[int(tail_start * vk.SAMPLE_RATE):]).max()))
        d = pa.durations
        print(f"[{n}] {st.character} / {st.style} (id {st.id}) -> {path.name}")
        print(f"    speed 上 {pa.speed_kami:.3f} 下 {pa.speed_shimo:.3f}  | 上の句 {d['kami']:.2f}s 間 {d['ma']:.2f}s "
              f"下の句 {d['shimo']:.2f}s 余韻 {d['yoin']:.2f}s 合計 {d['total']:.2f}s")
        print(f"    音量 有音RMS {vk.active_rms_db(y):.1f} dBFS (補正 {gain_db:+.1f} dB) peak {peak_db:.1f} dBFS clipped={clipped}")
        print(f"    モーラ別 RMS 最小 {min(mora_db):.1f} dBFS / 間のピーク {ma_db:.0f} dBFS / 余韻の無音部ピーク {tail_peak:.0f} dBFS")
        for nm, q in (("上", pa.kami), ("下", pa.shimo)):
            print(f"    {nm}の句 fallback={q.used_fallback} AquesTalk={q.aquestalk}")
            for k in q.ku:
                if k.fallback:
                    print(f"        [{k.kanji}] OpenJTalk={k.ojt_kana} 不一致={k.problems[:3]}")
        assert clipped == 0 and peak_db < -0.5
        assert min(mora_db) > -50, "声が出ているはずの所が無音"
        assert ma_db < -60 and tail_peak < -60
        report[f"{n:02d}_{st.character}"] = {
            "style": st.style, "style_id": st.id, "speed_kami": pa.speed_kami, "speed_shimo": pa.speed_shimo,
            "durations_s": d, "kana_ms_kami": pa.kana_ms_kami, "kana_ms_shimo": pa.kana_ms_shimo,
            "fallback": {"kami": pa.kami.used_fallback, "shimo": pa.shimo.used_fallback},
            "aquestalk": {"kami": pa.kami.aquestalk, "shimo": pa.shimo.aquestalk},
        }
    (out / "timings_poem1.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), "utf-8")


if __name__ == "__main__":
    main()
