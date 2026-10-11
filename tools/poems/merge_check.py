#!/usr/bin/env python3
"""Wikisource の100首とエージェント作成データを照合し、検証レポートを出す。

- kami / shimo / shimoKana / author は Wikisource との差分を INFO として出す（語の一致を確認する材料）。
- kamiKana は Wikisource の上の句の歴史的仮名遣い（踊り字を展開したもの）と一致し、ひらがなと U+3000 のみの3句であること。
- kamiYomi / shimoYomi は Wikisource の歴史的仮名遣いの正当な現代仮名遣い表記になっているかを許容正規表現で照合。
  あわせて kamiKana / shimoKana との句ごとの対応も照合する（表示の文字位置の対応 game/KanaAlignment.kt が成り立つ条件）。
- 初期決まり字（音で判定、長音「おう」=「おお」）を Wikisource の太字と比較（44番のみ標準の三字「おうこ」）。
使い方: merge_check.py <wikisource.json> <repo>
ISSUES は要修正（0件にする）、INFO は要確認（妥当なら残してよい）。
"""
import collections
import json
import pathlib
import re
import sys

WS = json.load(open(sys.argv[1], encoding="utf-8"))
VOICED = dict(zip("かきくけこさしすせそたちつてとはひふへほ", "がぎぐげござじずぜぞだぢづでどばびぶべぼ"))


def expand_odoriji(s: str) -> str:
    """踊り字（ゝ・ゞ）を直前の仮名に展開する。"""
    out = []
    for c in s:
        if c == "ゝ" and out:
            c = out[-1]
        elif c == "ゞ" and out:
            c = VOICED.get(out[-1], out[-1])
        out.append(c)
    return "".join(out)


# かるたで主流の表記を採用した既知の表記揺れ（Wikisource 側を置き換えて照合する）
VARIANTS = {
    70: {"shimo": "いづこも同じ 秋の夕暮", "shimoKanaHist": "いづこもおなじ あきのゆふぐれ"},  # Wikisource は「いづくも」
}
for no, v in VARIANTS.items():
    WS[no - 1].update(v)

for w in WS:
    for f in ("kamiKanaHist", "shimoKanaHist", "kimarijiHist", "authorKanaHist"):
        if w.get(f):
            w[f] = expand_odoriji(w[f])
ROOT = pathlib.Path(sys.argv[2])
DATA = ROOT / "app/src/main/java/io/github/abonsan/hyakunin/data"
FIELDS = ["kami", "shimo", "kamiYomi", "shimoYomi", "kamiKana", "shimoKana", "author", "authorYomi"]
SP = "　"

agent = {}
for name in ["PoemsPart1.kt", "PoemsPart2.kt"]:
    text = (DATA / name).read_text(encoding="utf-8")
    for block in re.findall(r"Poem\((.*?)\n    \)", text, re.S):
        no = int(re.search(r"no = (\d+)", block).group(1))
        agent[no] = {f: (re.search(rf'\b{f} = "([^"]*)"', block) or [None, None])[1] for f in FIELDS}

LONG = [
    ("てふ", "ちょう"), ("けふ", "きょう"), ("あふ", "おう"), ("わう", "おう"), ("かう", "こう"), ("さう", "そう"),
    ("たう", "とう"), ("なう", "のう"), ("はう", "ほう"), ("まう", "もう"), ("やう", "よう"), ("らう", "ろう"),
    ("せう", "しょう"), ("てう", "ちょう"), ("けう", "きょう"), ("めう", "みょう"), ("れう", "りょう"),
    ("しう", "しゅう"), ("ちう", "ちゅう"), ("きう", "きゅう"), ("りう", "りゅう"), ("ちゆう", "ちゅう"),
    ("じゆう", "じゅう"), ("くわ", "か"), ("ぐわ", "が"), ("ゐん", "いん"),
]
ALT = {"は": "わ", "ひ": "い", "ふ": "う", "へ": "え", "ほ": "お", "ゐ": "い", "ゑ": "え", "を": "お", "ぢ": "じ", "づ": "ず"}


def tolerant(hist: str) -> re.Pattern:
    """歴史的仮名遣いから、許される現代仮名遣い表記すべてにマッチする正規表現を作る。"""
    out, i = [], 0
    while i < len(hist):
        for a, b in sorted(LONG, key=lambda x: -len(x[0])):
            if hist.startswith(a, i):
                chars = "".join(f"[{c}{ALT[c]}]" if c in ALT else re.escape(c) for c in a)
                out.append(f"(?:{chars}|{re.escape(b)})")
                i += len(a)
                break
        else:
            c = hist[i]
            out.append(f"[{c}{ALT[c]}]" if c in ALT else re.escape(c))
            i += 1
    return re.compile("".join(out))


def nospace(s: str) -> str:
    return re.sub(r"[\s　]", "", s)


NORM = str.maketrans({"を": "お", "ゐ": "い", "ゑ": "え", "ぢ": "じ", "づ": "ず"})
O_ROW = set("おこそとのほもよろごぞどぼぽょ")
SMALL = set("ゃゅょぁぃぅぇぉゎ")


def sound(yomi: str) -> str:
    s = nospace(yomi).translate(NORM)
    return "".join("お" if c == "う" and i > 0 and s[i - 1] in O_ROW else c for i, c in enumerate(s))


def kimariji(no: int, table: dict) -> int:
    t = sound(table[no])
    k = 1
    while k < len(t) and any(sound(v).startswith(t[:k]) for n, v in table.items() if n != no):
        k += 1
    while k < len(t) and t[k] in SMALL:
        k += 1
    return k


issues = collections.defaultdict(list)
info = collections.defaultdict(list)
for w in WS:
    no = w["no"]
    a = agent.get(no)
    if not a:
        issues[no].append("agent data missing")
        continue
    for f in FIELDS:
        if not a[f]:
            issues[no].append(f"{f} missing")
    # kamiKana: Wikisource の歴史的仮名遣い（句の区切りを U+3000 にしたもの）そのまま、ひらがなと U+3000 のみの3句
    if a["kamiKana"]:
        if a["kamiKana"] != w["kamiKanaHist"].replace(" ", SP):
            issues[no].append(f"kamiKana '{a['kamiKana']}' != wikisource '{w['kamiKanaHist']}'")
        if not re.fullmatch(f"[ぁ-ゖ]+({SP}[ぁ-ゖ]+){{2}}", a["kamiKana"]):
            issues[no].append(f"kamiKana is not 3 hiragana phrases separated by U+3000: '{a['kamiKana']}'")
    # 読みは、歴史的仮名遣いの正当な現代仮名遣い表記であること（句ごと）。表示の対応づけはこの句ごとの対応を前提にする
    for yomi_f, kana_f, hist in [("kamiYomi", "kamiKana", "kamiKanaHist"), ("shimoYomi", "shimoKana", "shimoKanaHist")]:
        if not a[yomi_f]:
            continue
        if not tolerant(nospace(w[hist])).fullmatch(nospace(a[yomi_f])):
            issues[no].append(f"{yomi_f} '{a[yomi_f]}' does not match hist '{w[hist]}'")
        if a[kana_f]:
            ys, ks = a[yomi_f].split(SP), a[kana_f].split(SP)
            if len(ys) != len(ks):
                issues[no].append(f"{yomi_f} phrase count {len(ys)} != {kana_f} phrase count {len(ks)}")
            else:
                for y, k in zip(ys, ks):
                    if not tolerant(k).fullmatch(y):
                        issues[no].append(f"{yomi_f} phrase '{y}' does not match {kana_f} phrase '{k}'")
    if a["authorYomi"] and not tolerant(nospace(w["authorKanaHist"])).fullmatch(nospace(a["authorYomi"])):
        info[no].append(f"authorYomi '{a['authorYomi']}' vs hist '{w['authorKanaHist']}'")
    for f in ["kamiYomi", "shimoYomi", "authorYomi"]:
        v = a[f] or ""
        if re.search(r"[ゐゑ]", v) or re.search(r"(?<![ちつ])[ぢづ]", v):
            issues[no].append(f"{f} has historical kana: '{v}'")
        for ku in v.split(SP):
            medial = [c for c in ku[1:] if c in "はひふへほ"]
            if medial:
                info[no].append(f"{f} keeps medial {''.join(medial)} in '{ku}' (particle/compound-initial?)")
    # 句の区切り位置の一致（読みと本文の句数）
    for f, n in [("kamiYomi", 3), ("shimoYomi", 2)]:
        if a[f] and len(a[f].split(SP)) != n:
            issues[no].append(f"{f} phrase count != {n}: '{a[f]}'")
    if a["kami"] and nospace(a["kami"]) != nospace(w["kami"]):
        info[no].append(f"kami differs: agent '{a['kami']}' / wikisource '{w['kami']}'")
    if a["shimo"] and nospace(a["shimo"]) != nospace(w["shimo"]):
        info[no].append(f"shimo differs: agent '{a['shimo']}' / wikisource '{w['shimo']}'")
    if a["shimoKana"] and nospace(a["shimoKana"]) != nospace(w["shimoKanaHist"]):
        info[no].append(f"shimoKana differs: agent '{a['shimoKana']}' / wikisource '{w['shimoKanaHist']}'")
    if a["author"] and a["author"] != w["author"]:
        info[no].append(f"author differs: agent '{a['author']}' / wikisource '{w['author']}'")

table = {no: a["kamiYomi"] for no, a in agent.items() if a.get("kamiYomi")}
dist = collections.Counter()
if len(table) == 100:
    for w in WS:
        no = w["no"]
        k = kimariji(no, table)
        dist[k] += 1
        expected = 3 if no == 44 else len(nospace(w["kimarijiHist"]))
        if k != expected:
            issues[no].append(f"kimariji {k} ('{nospace(table[no])[:k]}') != expected {expected} ('{w['kimarijiHist']}')")

print(f"agent poems: {len(agent)}")
print("kimariji distribution:", dict(sorted(dist.items())),
      "OK" if dict(dist) == {1: 7, 2: 42, 3: 37, 4: 6, 5: 2, 6: 6} else "MISMATCH (expected 7/42/37/6/2/6)")
print(f"\n== ISSUES ({sum(map(len, issues.values()))}) ==")
for no in sorted(issues):
    for m in issues[no]:
        print(f"{no:3d}: {m}")
print(f"\n== INFO ({sum(map(len, info.values()))}) ==")
for no in sorted(info):
    for m in info[no]:
        print(f"{no:3d}: {m}")
