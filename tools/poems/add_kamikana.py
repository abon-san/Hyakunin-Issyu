#!/usr/bin/env python3
"""Wikisource の上の句の歴史的仮名遣い（kamiKanaHist）から kamiKana を生成し、PoemsPart1/2.kt に入れる。

- 踊り字（ゝ は直前の仮名、ゞ は直前の仮名の濁音）を展開する。
- 半角空白（句の区切り）を全角スペース（U+3000）にする。
- ひらがなと U+3000 のみ・3句であることを確認する。
- 既に kamiKana がある行は置き換える（何度実行しても同じ結果になる）。
使い方: add_kamikana.py <wikisource.json> <repo>
"""
import json
import pathlib
import re
import sys

WS = {w["no"]: w for w in json.load(open(sys.argv[1], encoding="utf-8"))}
DATA = pathlib.Path(sys.argv[2]) / "app/src/main/java/io/github/abonsan/hyakunin/data"
SP = "　"
VOICED = dict(zip("かきくけこさしすせそたちつてとはひふへほ", "がぎぐげござじずぜぞだぢづでどばびぶべぼ"))
HIRAGANA_PHRASES = re.compile(f"[ぁ-ゖ]+({SP}[ぁ-ゖ]+){{2}}")


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


def kami_kana(no: int) -> str:
    value = expand_odoriji(WS[no]["kamiKanaHist"]).replace(" ", SP)
    if not HIRAGANA_PHRASES.fullmatch(value):
        sys.exit(f"{no}: kamiKana must be 3 hiragana phrases separated by U+3000: {value!r}")
    return value


count = 0
for name in ["PoemsPart1.kt", "PoemsPart2.kt"]:
    path = DATA / name
    text = path.read_text(encoding="utf-8")
    # 既存の kamiKana の行を取り除いてから、shimoKana の直前に入れる
    text = re.sub(r'^ *kamiKana = "[^"]*",\n', "", text, flags=re.M)

    def insert(m: re.Match) -> str:
        global count
        block = m.group(0)
        no = int(re.search(r"no = (\d+)", block).group(1))
        count += 1
        return re.sub(r"^( *)shimoKana = ", lambda s: f'{s.group(1)}kamiKana = "{kami_kana(no)}",\n{s.group(1)}shimoKana = ', block, count=1, flags=re.M)

    text = re.sub(r"Poem\(\n.*?\n    \)", insert, text, flags=re.S)
    path.write_text(text, encoding="utf-8")
print(count, "poems updated")
