#!/usr/bin/env python3
"""全日本かるた協会「競技かるた読手テキスト」の伸ばし(ー)位置を、アプリの読みに揃えて表にする。

入力は協会が公開している読み方一覧の PDF（縦書き・Word 製・全 17 頁）。
一覧は 6〜16 頁にあり、1 首が 1 本の縦の列になっている。列の中身は
  - 本文（14pt、漢字＋歴史的仮名）
  - ルビ（8pt、現代仮名の読み。本文の右隣 x+13.9 の列）
  - 伸ばし（本文列にある「ー」。14pt のものと 9pt のものがある）
  - 上の句と下の句の間の空き（本文の文字間が 20pt 以上開く 1 か所）
  - 大山札などの任意の短い伸ばしのヒント（8pt、「‐」入り、本文列の右 x+21.8 の列）
である。ルビの「ー」(おーさか)と伸ばしの「ー」は、フォントの大きさではなく
*列の位置*（ルビ列か本文列か）で完全に区別できる。

使い方（pdfminer.six が要る。リポジトリ外に venv を作ってよい）:
    python3 -m venv /tmp/venv && /tmp/venv/bin/pip install pdfminer.six
    /tmp/venv/bin/python -I tools/voice/extract_nobashi.py PATH/TO/text.pdf \
        [--app-data app/src/main/java/io/github/abonsan/hyakunin/data] [--out-dir tools/voice]

やること:
  1. 6〜16 頁の全文字を座標つきで取り、列ごとに本文・ルビ・数字(歌番号)・ヒントへ振り分ける。
  2. 本文の並び（漢字・仮名・伸ばし・上下の句の境）とルビ列を、アプリの
     kamiYomi + shimoYomi に DP で揃える（ルビは漢字／仮名を置き換え、ルビのない仮名はそのまま）。
     歴史的仮名↔現代仮名、ー↔長音の仮名、ぢ/づ↔じ/ず、む↔ん などは読みの同値として扱う。
  3. 各伸ばしを「アプリの読みの何文字目の後ろか」（上の句は kamiYomi、下の句は shimoYomi の
     空白を除いた文字数）に直す。上の句の最後の字の後ろ（上の句終わりの伸ばし）は len(kami)。
  4. 協会の規則に照らして検証し、外れた歌を notes に残す。
出力: nobashi.json / nobashi_check.txt
  nobashi.json: {"joka"|"1".."100": {kami: [index...], shimo: [index...], pdf: 生の文字列, notes: [...],
                 short: PDFで小さい(9pt)伸ばしの index（二句目の終わり・四句目の途中。短く伸ばす印と思われる）,
                 optional: ヒント(‐)の位置（大山札の二句目途中など。任意の短い伸ばし）}}
  pdf の表記: ルビは《》、上下の句の境は／、小さい伸ばしは半角ｰ、通常の伸ばしはー。
  index は、その句(上の句は kamiYomi、下の句は shimoYomi)の空白を除いた文字数。
"""
from __future__ import annotations

import argparse
import heapq
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

try:
    from pdfminer.high_level import extract_pages
    from pdfminer.layout import LTChar
except ImportError:  # pragma: no cover
    sys.exit("pdfminer.six が必要です: pip install pdfminer.six")

LIST_PAGES = range(6, 17)  # PDF の 6〜16 頁（1 始まり）
BASE_SIZE = 14.04  # 本文
RUBY_SIZE = 8.04  # ルビ・ヒント
MIN_Y = 100.0  # これより下はページ番号
GAP_BREAK = 20.0  # 上の句と下の句の間の空き（pt）
SP = "　"

# ---- 規則 ---------------------------------------------------------------
# 上の句終わり: 二字前を伸ばす 3 首 / 一字前でも二字前でもよい 22 首
KAMI_TWO_BEFORE = {8, 83, 93}
KAMI_EITHER = {7, 13, 15, 17, 20, 24, 27, 28, 30, 41, 42, 46, 48, 49, 50, 51, 62, 73, 79, 86, 88, 95}
# 下の句終わり: 三字前でまとめる 11 首（もがな・思へ・思ふ・なくに・ものを）
SHIMO_THREE_BEFORE = {14, 20, 25, 34, 49, 54, 56, 63, 74, 77, 80}
# 大山札（二句目までを一気に読む。初句の後に伸ばしがない）
OYAMA = {31, 64, 11, 76, 15, 50}


# ---- PDF から文字を取る ---------------------------------------------------
def walk(obj):
    if isinstance(obj, LTChar):
        yield obj
    elif hasattr(obj, "__iter__"):
        for c in obj:
            yield from walk(c)


def load_chars(pdf: str):
    pages = {}
    for pn, page in enumerate(extract_pages(pdf, laparams=None), 1):
        if pn not in LIST_PAGES:
            continue
        pages[pn] = [
            dict(t=c.get_text(), s=round(c.size, 2), x0=round(c.x0, 1), y0=round(c.y0, 1),
                 x1=round(c.x1, 1), y1=round(c.y1, 1), f=c.fontname)
            for c in walk(page)
        ]
    return pages


def is_kana(ch: str) -> bool:
    return "ぁ" <= ch <= "ゟ" or "ァ" <= ch <= "ヺ"


def build_columns(pages):
    """各頁の縦列を {base, ruby, hints, digits} に振り分ける。"""
    cols = []
    for pn, P in pages.items():
        main = [c for c in P if "JEE" in c["f"]]
        xs = sorted({round(c["x0"]) for c in main if abs(c["s"] - BASE_SIZE) < 0.1})
        # 隣り合う丸め違い(±1)を併合
        xs2 = []
        for x in xs:
            if xs2 and x - xs2[-1] <= 1:
                continue
            xs2.append(x)
        used = set()
        for X in xs2:
            col = dict(page=pn, X=X, base=[], ruby=[], digits=[], hints=[], stray=[])
            for idx, c in enumerate(P):
                if c["y0"] < MIN_Y:
                    continue
                dx = c["x0"] - X
                t = c["t"]
                if -2 <= dx <= 3.6 and "JEE" in c["f"] and c["s"] > 8.9:
                    col["base"].append(c); used.add(idx)
                elif 11.5 <= dx <= 16 and "JEE" in c["f"] and abs(c["s"] - RUBY_SIZE) < 0.1:
                    col["ruby"].append(c); used.add(idx)
                elif t.isdigit() and c["y0"] > 650 and -2 <= dx <= 12:
                    col["digits"].append(c); used.add(idx)
                elif 20.5 <= dx <= 23 and "JEE" in c["f"] and abs(c["s"] - RUBY_SIZE) < 0.1:
                    col["hints"].append(c); used.add(idx)
            col["base"].sort(key=lambda c: -c["y1"])
            col["ruby"].sort(key=lambda c: -c["y1"])
            col["hints"].sort(key=lambda c: -c["y1"])
            col["digits"].sort(key=lambda c: c["x0"])
            cols.append(col)
    return cols


def clean_column(col):
    """列から poem 番号・本文要素・ルビ・ヒントを取り出す。"""
    num = "".join(d["t"] for d in col["digits"])
    base = col["base"]
    elems = []  # dict(type, ch, cy, small)
    prev = None
    gaps = 0
    for c in base:
        if prev is not None and prev["y0"] - c["y1"] > GAP_BREAK:
            elems.append(dict(type="G", ch="|", cy=(prev["y0"] + c["y1"]) / 2, small=False))
            gaps += 1
        ch = c["t"]
        cy = (c["y0"] + c["y1"]) / 2
        if ch == "ー":
            elems.append(dict(type="M", ch="ー", cy=cy, small=c["s"] < 10))
        elif is_kana(ch):
            elems.append(dict(type="k", ch=ch, cy=cy, small=False))
        else:
            elems.append(dict(type="K", ch=ch, cy=cy, small=False))
        prev = c
    ruby = [dict(ch=c["t"], cy=(c["y0"] + c["y1"]) / 2) for c in col["ruby"]
            if c["t"] not in "()（）" and c["t"].strip()]
    hint = "".join(c["t"] for c in col["hints"])
    return num, elems, ruby, gaps, hint


# ---- アプリの読み --------------------------------------------------------
def load_app_readings(data_dir: Path):
    out = {}
    for fn in ("PoemsPart1.kt", "PoemsPart2.kt"):
        text = (data_dir / fn).read_text(encoding="utf-8")
        for m in re.finditer(r"no = (\d+),.*?kamiYomi = \"([^\"]*)\",\s*shimoYomi = \"([^\"]*)\"", text, re.S):
            out[int(m.group(1))] = (m.group(2), m.group(3))
    if len(out) != 100:
        sys.exit(f"アプリの歌が 100 首読めません: {len(out)}")
    return out


JOKA = ("なにわずに　さくやこのはな　ふゆごもり", "いまをはるべと　さくやこのはな")


# ---- 読みの同値 ---------------------------------------------------------
EQ = {
    ("は", "わ"), ("へ", "え"), ("ひ", "い"), ("ふ", "う"), ("ゐ", "い"), ("ゑ", "え"),
    ("を", "お"), ("ぢ", "じ"), ("づ", "ず"), ("む", "ん"), ("ほ", "お"), ("ほ", "う"),
}
VOWELS = set("あいうえお")


def sub_cost(a: str, b: str) -> float:
    """PDF 側の字 a と アプリ側の字 b の置換コスト。"""
    if a == b:
        return 0.0
    if (a, b) in EQ or (b, a) in EQ:
        return 0.1
    if a == "ー" and b in VOWELS:
        return 0.1
    if b == "ー" and a in VOWELS:
        return 0.1
    return 1.0


def align(elems, ruby, S: str, n_kami: int, force_gap: bool = True):
    """本文要素 elems とルビ ruby を S（アプリの読み）に揃える。
    要素: M=伸ばし, G=上下の句の境, k=仮名(ルビなし/あり), K=漢字(ルビ必須)。
    戻り値: (cost, ops) ops は経路。"""
    n, m, ns = len(elems), len(ruby), len(S)
    GEO = 34.0  # ルビと本文の中心がこれ以上離れたら対応させない
    INF = 1e18
    best = {}
    parent = {}
    # i は 0..n、層ごとに処理するので heap を層ごとに分ける
    layer_heaps = [[] for _ in range(n + 1)]

    def push(key, cost, prev, tag):
        if cost < best.get(key, INF) - 1e-9:
            best[key] = cost
            parent[key] = (prev, tag)
            heapq.heappush(layer_heaps[key[0]], (key[1], key[2], key[3], cost))

    push((0, 0, 0, 0), 0.0, None, None)
    for i in range(n + 1):
        h = layer_heaps[i]
        el = elems[i] if i < n else None
        seen = set()
        while h:
            j, k, s, cost = heapq.heappop(h)
            key = (i, j, k, s)
            if key in seen or cost > best[key] + 1e-9:
                continue
            seen.add(key)
            # S の字を飛ばす（本文側に読みがない）
            if k < ns:
                push((i, j, k + 1, s), cost + 1.0, key, ("skipS", k))
            if s == 1:
                # 要素を終える
                push((i + 1, j, k, 0), cost, key, ("fin",))
                # ルビをさらに食う
                if j < m and (el is None or abs(ruby[j]["cy"] - el["cy"]) <= GEO):
                    g = 0.002 * abs(ruby[j]["cy"] - el["cy"]) if el else 0
                    if k < ns:
                        push((i, j + 1, k + 1, 1), cost + sub_cost(ruby[j]["ch"], S[k]) + g, key, ("rub", j, k))
                    push((i, j + 1, k, 1), cost + 1.0 + g, key, ("rub", j, None))
                continue
            if el is None:
                continue
            t = el["type"]
            if t == "M":
                push((i + 1, j, k, 0), cost, key, ("M", k))
            elif t == "G":
                if (not force_gap) or k == n_kami:
                    push((i + 1, j, k, 0), cost, key, ("G", k))
            else:
                # 仮名: ルビなし（そのまま読む）
                if t == "k":
                    if k < ns:
                        push((i + 1, j, k + 1, 0), cost + sub_cost(el["ch"], S[k]), key, ("own", el["ch"], k))
                    push((i + 1, j, k, 0), cost + 1.0, key, ("own", el["ch"], None))
                else:
                    # 漢字にルビがない（異常）
                    push((i + 1, j, k, 0), cost + 3.0, key, ("noruby",))
                # ルビあり
                if j < m and abs(ruby[j]["cy"] - el["cy"]) <= GEO:
                    g = 0.002 * abs(ruby[j]["cy"] - el["cy"])
                    if k < ns:
                        push((i, j + 1, k + 1, 1), cost + sub_cost(ruby[j]["ch"], S[k]) + g, key, ("rub", j, k))
                    push((i, j + 1, k, 1), cost + 1.0 + g, key, ("rub", j, None))
    endkey = (n, m, ns, 0)
    if endkey not in best:
        return None, None
    ops = []
    key = endkey
    while key is not None:
        prev, tag = parent[key]
        if tag is not None:
            ops.append((prev[0], tag, prev))  # 要素番号 = 遷移元の層
        key = prev
    ops.reverse()
    return best[endkey], ops


def analyse(elems, ruby, S, n_kami):
    force = True
    cost, ops = align(elems, ruby, S, n_kami, True)
    notes = []
    if ops is None:
        cost, ops = align(elems, ruby, S, n_kami, False)
        force = False
        notes.append("上の句/下の句の境で読みの長さが揃わなかったので、境の拘束なしで揃えた")
    if ops is None:
        return None
    marks = []  # (k, small, element index)
    gap_k = None
    # 要素ごとに PDF の文字列を再構成する(表示用)
    elem_ruby = defaultdict(list)
    pairs = []  # (pdf_char or None, S index or None)
    for (i, tag, prev) in ops:
        kind = tag[0]
        if kind == "M":
            marks.append((tag[1], elems[i]["small"], i))
        elif kind == "G":
            gap_k = tag[1]
        elif kind == "rub":
            elem_ruby[i].append(ruby[tag[1]]["ch"])
            pairs.append((ruby[tag[1]]["ch"], tag[2]))
        elif kind == "own":
            pairs.append((tag[1], tag[2]))
        elif kind == "skipS":
            pairs.append((None, tag[1]))
        elif kind == "noruby":
            notes.append(f"漢字「{elems[i]['ch']}」にルビがない")
    return dict(cost=cost, marks=marks, gap_k=gap_k, pairs=pairs, elem_ruby=dict(elem_ruby),
                notes=notes, forced=force)


def hint_positions(hint: str, S: str):
    """ヒント(例 あさぼらけありあけの‐つきとー)の「‐」が S の何字目の後ろか。
    戻り値は [(位置 or None, 推測したか)]。「‐」が長音の仮名の代わりに書かれている
    ヒント(よをおも‐ゆえにー = おもう の う が ‐)は、‐ を母音 1 字として探し、その後ろを位置にする(推測)。"""
    text = hint.replace("ー", "").replace("‐", "")
    start = S.find(text)
    out = []
    n = 0
    for ch in hint:
        if ch == "‐":
            if start >= 0:
                out.append((start + n, False))
            else:
                pre = hint[: hint.index("‐")].replace("ー", "")
                found = None
                for v in "うおいえあ":
                    t2 = hint.replace("ー", "").replace("‐", v)
                    st = S.find(t2)
                    if st >= 0:
                        found = st + len(pre) + 1
                        break
                out.append((found, True))
        elif ch != "ー":
            n += 1
    return out


def raw_string(num, elems, elem_ruby, hint):
    """目で確かめるための生の文字列。ルビは《》、上下の句の境は「／」、小さい伸ばしは「ｰ」。"""
    out = []
    for i, e in enumerate(elems):
        if e["type"] == "G":
            out.append("／")
        elif e["type"] == "M":
            out.append("ｰ" if e["small"] else "ー")
        else:
            out.append(e["ch"])
            if i in elem_ruby:
                out.append("《" + "".join(elem_ruby[i]) + "》")
    return (num + " " if num else "") + "".join(out)


# ---- 検証 -----------------------------------------------------------------
def phrase_lengths(y: str):
    return [len(p) for p in y.split(SP)]


def validate(no, kami_y, shimo_y, kami_marks, shimo_marks):
    """規則に照らした逸脱の一覧を返す。no は int(1..100) か 0(序歌)。"""
    dev = []
    kl = phrase_lengths(kami_y)
    sl = phrase_lengths(shimo_y)
    e1 = kl[0]
    e2 = kl[0] + kl[1]
    e3 = sum(kl)
    f1 = sl[0]
    f2 = sum(sl)
    km = Counter(kami_marks)
    sm = Counter(shimo_marks)
    oyama = no in OYAMA
    # 初句は切らない
    inner = [x for x in kami_marks if 0 < x < e1]
    if inner:
        dev.append(f"初句の途中に伸ばし {inner}")
    # 句の終わり
    if oyama:
        if km[e1]:
            dev.append("大山札なのに初句の後ろに伸ばしがある")
    else:
        if not km[e1]:
            dev.append(f"初句の終わり({e1})に伸ばしがない")
    if not km[e2]:
        dev.append(f"二句目の終わり({e2})に伸ばしがない")
    if not km[e3]:
        dev.append(f"上の句の終わり({e3})に伸ばしがない")
    elif km[e3] > 1:
        dev.append(f"上の句の終わりの伸ばしが{km[e3]}個")
    if not sm[f1]:
        dev.append(f"四句目の終わり({f1})に伸ばしがない")
    # 上の句終わりの前の伸ばし
    one, two = e3 - 1, e3 - 2
    if no in KAMI_TWO_BEFORE:
        if not km[two]:
            dev.append(f"上の句: 二字前({two})に伸ばしがない(規則上は二字前)")
        if km[one]:
            dev.append(f"上の句: 一字前({one})にも伸ばしがある(規則上は二字前のみ)")
    elif no in KAMI_EITHER:
        if not (km[one] or km[two]):
            dev.append(f"上の句: 一字前({one})も二字前({two})も伸ばしがない")
    else:
        if not km[one]:
            dev.append(f"上の句: 一字前({one})に伸ばしがない")
        if km[two]:
            dev.append(f"上の句: 二字前({two})に伸ばしがある(規則上は一字前)")
    # 四句目の途中
    mid = [x for x in shimo_marks if 0 < x < f1]
    if not mid:
        dev.append("四句目の途中に伸ばしがない")
    # 下の句の終わり
    want = f2 - (3 if no in SHIMO_THREE_BEFORE else 2)
    other = f2 - (2 if no in SHIMO_THREE_BEFORE else 3)
    if not sm[want]:
        dev.append(f"下の句: {'三' if no in SHIMO_THREE_BEFORE else '二'}字前({want})に伸ばしがない")
    if sm[other]:
        dev.append(f"下の句: {'二' if no in SHIMO_THREE_BEFORE else '三'}字前({other})にも伸ばしがある")
    if sm[f2]:
        dev.append(f"下の句の最後({f2})に伸ばしがある")
    if any(x > f2 or x < 0 for x in shimo_marks) or any(x > e3 or x < 0 for x in kami_marks):
        dev.append("範囲外の伸ばし")
    if sm[0]:
        dev.append("下の句の先頭(0)に伸ばしがある")
    return dev


def with_marks(yomi: str, marks, short=()):
    """yomi(空白区切り)の指定位置にーを挿入する。PDFで小さい(9pt)伸ばしは半角のｰにする。"""
    cnt = Counter(marks)
    sh = Counter(short)
    out = []
    pos = 0
    for ch in yomi:
        if ch == SP:
            out.append(SP)
            continue
        out.append(ch)
        pos += 1
        n_short = min(sh.get(pos, 0), cnt.get(pos, 0))
        out.append("ー" * (cnt.get(pos, 0) - n_short) + "ｰ" * n_short)
    if cnt.get(0):
        out.insert(0, "ー" * cnt[0])
    return "".join(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pdf")
    here = Path(__file__).resolve().parent
    ap.add_argument("--app-data", type=Path,
                    default=here.parents[1] / "app/src/main/java/io/github/abonsan/hyakunin/data")
    ap.add_argument("--out-dir", type=Path, default=here)
    args = ap.parse_args()

    pages = load_chars(args.pdf)
    if set(pages) != set(LIST_PAGES):
        sys.exit(f"一覧の頁が足りません: {sorted(pages)}")
    cols = build_columns(pages)
    app = load_app_readings(args.app_data)

    result = {}
    check_lines = []
    reading_diffs = {}
    guessed = {}
    seen = set()
    dist = Counter()
    for col in cols:
        num, elems, ruby, gaps, hint = clean_column(col)
        if not elems:
            continue
        if num == "":
            if col["page"] != 6 or not any(e["ch"] == "咲" or e["ch"] == "津" for e in elems):
                continue  # 見出し列など
            key = "joka"; no = 0
            kami_y, shimo_y = JOKA
        else:
            no = int(num); key = str(no)
            kami_y, shimo_y = app[no]
        if key in seen:
            sys.exit(f"歌 {key} が重複")
        seen.add(key)
        S = (kami_y + shimo_y).replace(SP, "")
        n_kami = len(kami_y.replace(SP, ""))
        notes = []
        if gaps != 1:
            notes.append(f"上下の句の境(20pt以上の空き)が{gaps}か所")
        an = analyse(elems, ruby, S, n_kami)
        if an is None:
            sys.exit(f"歌 {key}: 揃えられない")
        notes += an["notes"]
        kami_marks = sorted(k for k, sm, i in an["marks"] if an["gap_k"] is None or _before_gap(elems, i))
        shimo_marks = sorted(k - n_kami for k, sm, i in an["marks"] if an["gap_k"] is not None and not _before_gap(elems, i))
        if an["gap_k"] is None:
            notes.append("境が見つからず、伸ばしを上の句/下の句に振り分けられない")
        small_k = sorted(k for k, sm, i in an["marks"] if sm and _before_gap(elems, i))
        small_s = sorted(k - n_kami for k, sm, i in an["marks"] if sm and not _before_gap(elems, i))
        # 読みの食い違い
        diffs = []
        for pc, si in an["pairs"]:
            if pc is None:
                diffs.append(f"アプリにだけ「{S[si]}」(位置{si})")
            elif si is None:
                diffs.append(f"PDFにだけ「{pc}」")
            elif sub_cost(pc, S[si]) >= 1.0:
                diffs.append(f"PDF「{pc}」≠アプリ「{S[si]}」(位置{si})")
        if diffs:
            notes.append("読みの食い違い: " + "; ".join(diffs))
            reading_diffs[key] = diffs
        dev = validate(no, kami_y, shimo_y, kami_marks, shimo_marks) if no else validate(0, kami_y, shimo_y, kami_marks, shimo_marks)
        for d in dev:
            notes.append("規則との差: " + d)
        # 大山札などのヒント(任意の短い伸ばし)
        opt_k, opt_s = [], []
        if hint:
            notes.append("ヒント(任意の短い伸ばし): " + hint)
            for pos, guess in hint_positions(hint, S):
                if pos is None:
                    notes.append("ヒントの文字列がアプリの読みに見つからない: " + hint)
                    continue
                if guess:
                    notes.append("ヒントの「‐」が長音の仮名(う)の代わりに書かれている。その仮名の後ろと推測した")
                    guessed.setdefault(key, "hint")
                if pos < n_kami:
                    opt_k.append(pos)
                else:
                    opt_s.append(pos - n_kami)
        # 一字前/二字前の別（上の句）
        e3 = n_kami
        km = Counter(kami_marks)
        if no in KAMI_EITHER:
            notes.append("上の句終わり: " + ("一字前と二字前の両方" if km[e3 - 1] and km[e3 - 2]
                                                  else "一字前" if km[e3 - 1] else "二字前" if km[e3 - 2] else "なし"))
        raw = raw_string(num, elems, an["elem_ruby"], hint)
        entry = dict(kami=kami_marks, shimo=shimo_marks, pdf=raw, notes=notes)
        if small_k or small_s:
            entry["short"] = dict(kami=small_k, shimo=small_s)
        if opt_k or opt_s:
            entry["optional"] = dict(kami=sorted(opt_k), shimo=sorted(opt_s))
        result[key] = entry
        dist[len(kami_marks) + len(shimo_marks)] += 1
        if an["cost"] >= 1.0 or not an["forced"]:
            guessed[key] = round(an["cost"], 2)
        label = "序歌" if key == "joka" else f"{no}"
        check_lines.append(f"{label}: {with_marks(kami_y, kami_marks, small_k)} / {with_marks(shimo_y, shimo_marks, small_s)}")

    missing = [str(i) for i in range(1, 101) if str(i) not in result]
    if missing or "joka" not in result:
        sys.exit(f"抜けている歌: {missing} joka={'joka' in result}")

    ordered = {"joka": result["joka"]}
    ordered.update({str(i): result[str(i)] for i in range(1, 101)})
    out = args.out_dir
    out.mkdir(parents=True, exist_ok=True)
    (out / "nobashi.json").write_text(json.dumps(ordered, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    lines = sorted(check_lines, key=lambda s: (0 if s.startswith("序歌") else 1, int(s.split(":")[0]) if s[0].isdigit() else 0))
    header = ["# 伸ばし位置の確認用(nobashi.json から生成)。PDF の見た目と見比べる。",
              "# 形式: 番号: 上の句 / 下の句   （句の区切りは全角空白）",
              "# ー = PDF の伸ばし(14pt)、ｰ(半角) = PDF で小さく(9pt)書かれた伸ばし（二句目の終わり・四句目の途中）",
              "# 大山札などの任意のヒント(‐)は nobashi.json の optional / notes にある", ""]
    footer = ["", "# ---- 規則との差 ----"]
    footer += [f"{k}: {n}" for k, v in result.items() for n in v["notes"] if n.startswith("規則との差")] or ["なし"]
    footer += ["", "# ---- PDF とアプリの読みの食い違い ----"]
    footer += [f"{k}: {'; '.join(v)}" for k, v in reading_diffs.items()] or ["なし"]
    (out / "nobashi_check.txt").write_text("\n".join(header + lines + footer) + "\n", encoding="utf-8")

    # ---- 報告 ----
    print("歌数:", len(result) - 1, "+ 序歌")
    print("伸ばしの個数の分布(歌ごと):", dict(sorted(dist.items())))
    nd = {k: [n for n in v["notes"] if n.startswith("規則との差")] for k, v in result.items()}
    nd = {k: v for k, v in nd.items() if v}
    print(f"\n規則との差がある歌: {len(nd)}")
    for k, v in nd.items():
        print(f"  [{k}] {result[k]['pdf']}")
        for n in v:
            print("     ", n)
    print(f"\n読みの食い違い(PDF≠アプリ): {len(reading_diffs)}")
    for k, v in reading_diffs.items():
        print(f"  [{k}] {result[k]['pdf']}\n      {v}")
    print(f"\n要確認(揃えのコスト>=1 または境拘束なし): {guessed}")


def _before_gap(elems, idx):
    for e in elems[idx:]:
        if e["type"] == "G":
            return True
    return False


if __name__ == "__main__":
    main()
