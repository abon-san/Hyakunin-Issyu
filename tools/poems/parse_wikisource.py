#!/usr/bin/env python3
"""Wikisource「小倉百人一首」の wikitext から100首を抽出して JSON に出力する。"""
import json
import re
import sys

src, out = sys.argv[1], sys.argv[2]
s = open(src, encoding="utf-8").read()
rows = re.split(r"\n\|-\n", s)
poems = []
for row in rows:
    m = re.search(r'<span id="([^"]+)">', row)
    if not m:
        continue
    cells = [c for c in row.split("\n|") if c]
    # cells[0]: 番号ヘッダ, [1]: 句, [2]: 作者, [3]: 出典, [4]: 画像
    def body(c):
        return c.split("|", 1)[1] if c.startswith("style=") else c
    ku = body(cells[1])
    author = body(cells[2])
    parts = ku.split("<br />")
    if len(parts) != 4:
        print("unexpected ku", m.group(1), parts, file=sys.stderr)
        continue
    kami, shimo, kana_kami, kana_shimo = parts
    kana_kami = kana_kami.strip().lstrip("（")
    kana_shimo = kana_shimo.strip().rstrip("）")
    bold = re.search(r"'''(.*?)'''", kana_kami)
    a_name, a_kana = author.split("<br />")
    a_name = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", a_name).strip()
    a_kana = a_kana.strip().strip("（）")
    poems.append({
        "no": len(poems) + 1,
        "id": m.group(1),
        "kami": kami.strip(),
        "shimo": shimo.strip(),
        "kamiKanaHist": kana_kami.replace("'''", ""),
        "shimoKanaHist": kana_shimo,
        "kimarijiHist": bold.group(1) if bold else None,
        "author": a_name,
        "authorKanaHist": a_kana,
    })
json.dump(poems, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(len(poems), "poems")
