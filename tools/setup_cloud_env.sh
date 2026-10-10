#!/bin/bash
# クラウド環境（使い捨てコンテナ）で Android のビルドとテストに必要なものをそろえる。
set -euo pipefail
SDK=${ANDROID_HOME:-/opt/android-sdk}
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp=$(mktemp -d)
  curl -sSfL -o "$tmp/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
  echo "e025545c62a8e64c7559119566a569fb1dec5f60  $tmp/clt.zip" | sha1sum -c -
  unzip -q "$tmp/clt.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-37.2" "build-tools;36.1.0" >/dev/null

# Robolectric の実行時 Jar（Maven Central が 429 を返すことがあるため事前に取得）
V=16-robolectric-13921718-i7
D=$HOME/.m2/repository/org/robolectric/android-all-instrumented/$V
if [ ! -f "$D/android-all-instrumented-$V.jar" ]; then
  mkdir -p "$D"
  B=https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/$V/android-all-instrumented-$V
  for ext in pom jar; do
    for i in 1 2 3 4 5 6; do
      curl -sSf -o "$D/android-all-instrumented-$V.$ext" "$B.$ext" && break
      sleep $((10 * i))
    done
  done
fi

python3 -c "import fontTools" 2>/dev/null || python3 -m pip install --quiet --break-system-packages fonttools
echo "sdk.dir=$SDK" > "$(cd "$(dirname "$0")/.." && pwd)/local.properties"
echo "ready: $SDK"
