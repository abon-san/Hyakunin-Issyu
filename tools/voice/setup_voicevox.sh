#!/bin/bash
# VOICEVOX CORE（オフライン音声合成）の実行環境を tools/voice-src/ にそろえる。冪等。
# 公式ダウンローダは api.github.com を使うため、リリースアセットの直接 URL だけで取得する。
# 使い方: tools/voice/setup_voicevox.sh [追加で取得する n.vvm の番号 ...]
set -euo pipefail

CORE_VER=0.17.0
ORT_VER=1.23.2
DIC_VER=1.11
DIC_TAG=1.11.1
VVM_VER=0.16.4
# 候補の声に必要なモデル（1:冥鳴ひまり 2:九州そら 4:剣崎雌雄 6:No.7 11:ナースロボ＿タイプＴ 12:雀松朱司/麒ヶ島宗麟 15:青山龍星）
VVMS="1 2 4 6 11 12 15 $*"

HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$HERE/../voice-src
mkdir -p "$SRC"
SRC=$(cd "$SRC" && pwd)
cd "$SRC"

get() { # get URL OUT
  [ -s "$2" ] && return 0
  for i in 1 2 3 4 5; do
    curl -sSfL -o "$2.part" "$1" && mv "$2.part" "$2" && return 0
    sleep $((3 * i))
  done
  echo "取得失敗: $1" >&2; return 1
}

# 1) venv + voicevox_core wheel
if ! "$SRC/venv/bin/python" -c "import voicevox_core" 2>/dev/null; then
  [ -x "$SRC/venv/bin/python" ] || python3 -m venv "$SRC/venv"
  WHL=voicevox_core-$CORE_VER-cp310-abi3-manylinux_2_34_x86_64.whl
  get "https://github.com/VOICEVOX/voicevox_core/releases/download/$CORE_VER/$WHL" "$SRC/$WHL"
  "$SRC/venv/bin/python" -m pip install --quiet "$SRC/$WHL" numpy
  rm -f "$SRC/$WHL"
fi

# 2) ONNX Runtime（VOICEVOX ビルド）
if ! ls "$SRC"/onnxruntime/lib/libvoicevox_onnxruntime.so* >/dev/null 2>&1; then
  T=voicevox_onnxruntime-linux-x64-$ORT_VER.tgz
  get "https://github.com/VOICEVOX/onnxruntime-builder/releases/download/voicevox_onnxruntime-$ORT_VER/$T" "$SRC/$T"
  rm -rf "$SRC/onnxruntime" && mkdir -p "$SRC/onnxruntime"
  tar -xzf "$SRC/$T" -C "$SRC/onnxruntime" --strip-components=1
  rm -f "$SRC/$T"
fi

# 3) OpenJTalk 辞書
if [ ! -d "$SRC/open_jtalk_dic" ]; then
  T=open_jtalk_dic_utf_8-$DIC_VER.tar.gz
  get "https://github.com/r9y9/open_jtalk/releases/download/v$DIC_TAG/$T" "$SRC/$T"
  mkdir -p "$SRC/open_jtalk_dic"
  tar -xzf "$SRC/$T" -C "$SRC/open_jtalk_dic" --strip-components=1
  rm -f "$SRC/$T"
fi

# 4) 音声モデル
mkdir -p "$SRC/models"
for n in $VVMS; do
  get "https://github.com/VOICEVOX/voicevox_vvm/releases/download/$VVM_VER/$n.vvm" "$SRC/models/$n.vvm"
done

du -sh "$SRC"
echo "OK: voicevox_core $CORE_VER / vvm $VVM_VER"
