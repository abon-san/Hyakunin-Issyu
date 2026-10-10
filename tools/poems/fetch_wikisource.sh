#!/bin/bash
# Wikisource「小倉百人一首」の表を取得して JSON にする（照合用。リポジトリには含めない）。
# 使い方: tools/poems/fetch_wikisource.sh <出力ディレクトリ>
set -euo pipefail
OUT=${1:?output dir}
mkdir -p "$OUT"
curl -sSf -A "HyakuninIssyuAppDataCheck/0.1 (https://github.com/abon-san/Hyakunin-Issyu)" -o "$OUT/ws.wiki" \
  "https://ja.wikisource.org/w/index.php?title=%E5%B0%8F%E5%80%89%E7%99%BE%E4%BA%BA%E4%B8%80%E9%A6%96&action=raw"
python3 -I "$(dirname "$0")/parse_wikisource.py" "$OUT/ws.wiki" "$OUT/wikisource.json"
