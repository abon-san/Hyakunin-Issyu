#!/usr/bin/env python3
"""Shippori Mincho をアプリで使う文字だけにサブセット化して res/font に出力する。

使い方: python3 tools/subset_fonts.py [出力名 ...]
アプリのソース（.kt / .xml）に新しい漢字を追加したら再実行すること。
出力名（例: shippori_mincho_semibold.ttf）を指定すると、そのフォントだけを作り直す。
元フォントは初回実行時に google/fonts から tools/fonts-src/ へダウンロードする。
"""
import pathlib
import sys
import urllib.request
from dataclasses import dataclass

from fontTools import subset

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "tools" / "fonts-src"
OUT = ROOT / "app" / "src" / "main" / "res" / "font"
BASE_URL = "https://raw.githubusercontent.com/google/fonts/main/ofl/shipporimincho/"

ASCII = (0x20, 0x7E)
CJK_SYMBOLS = (0x3000, 0x303F)
HIRAGANA = (0x3040, 0x309F)
KATAKANA = (0x30A0, 0x30FF)
FULLWIDTH = (0xFF01, 0xFF5E)
# 常に含める範囲: ASCII、CJK記号、ひらがな、カタカナ、全角英数記号
BASE_RANGES = (ASCII, CJK_SYMBOLS, HIRAGANA, KATAKANA, FULLWIDTH)
# 仮名だけを書くフォントの範囲: CJK記号、ひらがな、カタカナ
KANA_RANGES = (CJK_SYMBOLS, HIRAGANA, KATAKANA)


@dataclass(frozen=True)
class Subset:
    """元フォント source から、ranges の文字（app_chars ならアプリのソースに出てくる文字も）を残す。

    features は残す OpenType の機能（"*" はすべて）。
    """

    source: str
    output: str
    ranges: tuple[tuple[int, int], ...]
    app_chars: bool
    features: tuple[str, ...] = ("*",)


FONTS = [
    Subset("ShipporiMincho-Regular.ttf", "shippori_mincho_regular.ttf", BASE_RANGES, app_chars=True),
    Subset("ShipporiMincho-Bold.ttf", "shippori_mincho_bold.ttf", BASE_RANGES, app_chars=True),
    # 取り札の文字（ui/card/Torifuda.kt）。取り札には仮名しか書かないので仮名と記号だけにし、
    # 機能も縦書き用の字形（vert）だけを残す（異体の仮名や合成用の字形を含めると倍近く大きくなる）
    Subset(
        "ShipporiMincho-SemiBold.ttf",
        "shippori_mincho_semibold.ttf",
        KANA_RANGES,
        app_chars=False,
        features=("vert",),
    ),
]


def source_chars() -> set[str]:
    """アプリのソース（.kt / .xml）に出てくる文字。コメントやテストの文字も含む。"""
    chars: set[str] = set()
    src = ROOT / "app" / "src"
    for path in src.rglob("*"):
        if path.is_file() and path.suffix in {".kt", ".xml"}:
            chars.update(path.read_text(encoding="utf-8"))
    return chars


def collect_chars(font: Subset) -> set[str]:
    chars = {chr(c) for start, end in font.ranges for c in range(start, end + 1)}
    if font.app_chars:
        chars.update(source_chars())
    return {c for c in chars if c.isprintable() or c == "　"}


def ensure_source(name: str) -> pathlib.Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
    if not path.exists():
        urllib.request.urlretrieve(BASE_URL + name, path)
    return path


def main(outputs: list[str]) -> None:
    unknown = set(outputs) - {f.output for f in FONTS}
    if unknown:
        sys.exit(f"不明な出力名: {', '.join(sorted(unknown))}")
    OUT.mkdir(parents=True, exist_ok=True)
    for spec in FONTS:
        if outputs and spec.output not in outputs:
            continue
        text = "".join(sorted(collect_chars(spec)))
        options = subset.Options()
        options.layout_features = list(spec.features)  # 縦書き用グリフ(vert)などを残す
        options.name_IDs = ["*"]
        options.notdef_outline = True
        font = subset.load_font(str(ensure_source(spec.source)), options)
        subsetter = subset.Subsetter(options)
        subsetter.populate(text=text)
        subsetter.subset(font)
        out = OUT / spec.output
        subset.save_font(font, str(out), options)
        print(f"{out.relative_to(ROOT)}: {out.stat().st_size // 1024} KiB ({len(text)} chars)")


if __name__ == "__main__":
    main(sys.argv[1:])
