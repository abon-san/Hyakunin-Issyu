#!/usr/bin/env python3
"""アプリの100首データを、検証用の CSV（UTF-8 BOM 付き）に出力する。

使い方: export_csv.py <wikisource.json> <repo> <出力.csv>
"""
import csv
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[2])
DATA = ROOT / "app/src/main/java/io/github/abonsan/hyakunin/data"
FIELDS = ["kami", "shimo", "kamiYomi", "shimoYomi", "kamiKana", "shimoKana", "author", "authorYomi"]
WS = {w["no"]: w for w in json.load(open(sys.argv[1], encoding="utf-8"))}

poems = {}
for name in ["PoemsPart1.kt", "PoemsPart2.kt"]:
    for block in re.findall(r"Poem\((.*?)\n    \)", (DATA / name).read_text(encoding="utf-8"), re.S):
        no = int(re.search(r"no = (\d+)", block).group(1))
        poems[no] = {f: re.search(rf'\b{f} = "([^"]*)"', block).group(1) for f in FIELDS}

NORM = str.maketrans({"を": "お", "ゐ": "い", "ゑ": "え", "ぢ": "じ", "づ": "ず"})
O_ROW = set("おこそとのほもよろごぞどぼぽょ")
SMALL = set("ゃゅょぁぃぅぇぉゎ")


def sound(y):
    s = re.sub(r"\s", "", y).translate(NORM)
    return "".join("お" if c == "う" and i and s[i - 1] in O_ROW else c for i, c in enumerate(s))


def kimariji(no):
    t = sound(poems[no]["kamiYomi"])
    k = 1
    while k < len(t) and any(sound(p["kamiYomi"]).startswith(t[:k]) for n, p in poems.items() if n != no):
        k += 1
    while k < len(t) and t[k] in SMALL:
        k += 1
    return re.sub(r"\s", "", poems[no]["kamiYomi"])[:k]


out = pathlib.Path(sys.argv[3])
with out.open("w", encoding="utf-8-sig", newline="") as f:
    w = csv.writer(f)
    w.writerow([
        "番号", "上の句", "下の句", "上の句の読み（現代仮名遣い）", "下の句の読み（現代仮名遣い）",
        "上の句（歴史的仮名遣い）", "取り札（歴史的仮名遣い）", "作者", "作者の読み", "決まり字（初期）", "決まり字の字数",
        "【Wikisource】上の句", "【Wikisource】下の句", "【Wikisource】上の句（歴史的仮名遣い）",
        "【Wikisource】下の句（歴史的仮名遣い）", "【Wikisource】決まり字", "【Wikisource】作者", "【Wikisource】作者の読み",
    ])
    for no in range(1, 101):
        p, s = poems[no], WS[no]
        k = kimariji(no)
        w.writerow([
            no, p["kami"], p["shimo"], p["kamiYomi"], p["shimoYomi"], p["kamiKana"], p["shimoKana"], p["author"], p["authorYomi"],
            k, len(k), s["kami"], s["shimo"], s["kamiKanaHist"], s["shimoKanaHist"], s["kimarijiHist"], s["author"], s["authorKanaHist"],
        ])
print(out, sum(1 for _ in out.open(encoding="utf-8-sig")) - 1, "rows")
