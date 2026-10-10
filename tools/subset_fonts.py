#!/usr/bin/env python3
"""Shippori Mincho をアプリで使う文字だけにサブセット化して res/font に出力する。

使い方: python3 tools/subset_fonts.py
アプリのソース（.kt / .xml）に新しい漢字を追加したら再実行すること。
元フォントは初回実行時に google/fonts から tools/fonts-src/ へダウンロードする。
"""
import pathlib
import urllib.request

from fontTools import subset

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "tools" / "fonts-src"
OUT = ROOT / "app" / "src" / "main" / "res" / "font"
BASE_URL = "https://raw.githubusercontent.com/google/fonts/main/ofl/shipporimincho/"
FONTS = {
    "ShipporiMincho-Regular.ttf": "shippori_mincho_regular.ttf",
    "ShipporiMincho-Bold.ttf": "shippori_mincho_bold.ttf",
}
# 常に含める範囲: ASCII、CJK記号、ひらがな、カタカナ、全角英数記号
BASE_RANGES = [(0x20, 0x7E), (0x3000, 0x303F), (0x3040, 0x309F), (0x30A0, 0x30FF), (0xFF01, 0xFF5E)]


def collect_chars() -> set[str]:
    chars = {chr(c) for start, end in BASE_RANGES for c in range(start, end + 1)}
    src = ROOT / "app" / "src"
    for path in src.rglob("*"):
        if path.is_file() and path.suffix in {".kt", ".xml"}:
            chars.update(path.read_text(encoding="utf-8"))
    return {c for c in chars if c.isprintable() or c == "　"}


def ensure_source(name: str) -> pathlib.Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
    if not path.exists():
        urllib.request.urlretrieve(BASE_URL + name, path)
    return path


def main() -> None:
    text = "".join(sorted(collect_chars()))
    OUT.mkdir(parents=True, exist_ok=True)
    for src_name, out_name in FONTS.items():
        options = subset.Options()
        options.layout_features = ["*"]  # 縦書き用グリフ(vert)などを残す
        options.name_IDs = ["*"]
        options.notdef_outline = True
        font = subset.load_font(str(ensure_source(src_name)), options)
        subsetter = subset.Subsetter(options)
        subsetter.populate(text=text)
        subsetter.subset(font)
        out = OUT / out_name
        subset.save_font(font, str(out), options)
        print(f"{out.relative_to(ROOT)}: {out.stat().st_size // 1024} KiB ({len(text)} chars)")


if __name__ == "__main__":
    main()
