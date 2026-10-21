# リリースの手順

アプリは起動時（設定の「起動時に更新を確認」がオンのとき）と、設定の「今すぐ確認」で、
このリポジトリの GitHub Releases の最新のリリースを確かめます。新しい版があれば APK を落とし、
Android のインストール確認を経て更新します。

## 初回の準備

### 1. リポジトリを公開する

アプリは認証なしで GitHub API（`/repos/abon-san/Hyakunin-Issyu/releases/latest`）を読むので、
リポジトリが非公開のままでは新しい版を見つけられません。公開する前に、履歴に秘密の情報（鍵・パスワードなど）が
入っていないかを確かめてください。

### 2. 署名の鍵を作る

更新は、入っているアプリと同じ鍵で署名した APK でなければ入りません。鍵を失くすと、それ以降は更新できなくなります
（入れ直すしかなくなる）。鍵とパスワードは、リポジトリの外に必ず控えを取ってください。

```sh
keytool -genkeypair -v -keystore hyakunin-release.jks -alias hyakunin \
  -keyalg RSA -keysize 4096 -validity 36500
```

`*.jks` は `.gitignore` に入っているので、コミットされません。

### 3. GitHub の Secrets に登録する

リポジトリの Settings → Secrets and variables → Actions で、次の4つを登録します。

| 名前 | 値 |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 hyakunin-release.jks` の出力（macOS は `base64 -i hyakunin-release.jks`） |
| `KEYSTORE_PASSWORD` | 鍵ストアのパスワード |
| `KEY_ALIAS` | 鍵の別名（上の例では `hyakunin`） |
| `KEY_PASSWORD` | 鍵のパスワード |

### 4. 端末に最初のリリース版を入れる

Android Studio などで入れたデバッグ版は、署名が違うためリリース版に更新できません。
最初のリリースの APK だけは、デバッグ版をアンインストールしてから手で入れてください（記録した間違えた札や設定は消えます）。
以後は、アプリが新しい版を見つけて更新します。

## リリースの手順

1. `app/build.gradle.kts` の `versionCode` を1つ上げ、`versionName` を新しい版にする（`AppVersionTest` も合わせる）
2. コミットして push する
3. 注記つきのタグを打って push する。タグの名前は `v` と `versionName`（例: `v0.7.0`）。
   タグの注記がリリースの注記になり、アプリの「新しい版があります」にも先頭の数行が出ます

   ```sh
   git tag -a v0.7.0 -m "自動アップデート機能を足しました"
   git push origin v0.7.0
   ```

4. GitHub Actions の「Release」がテストを流し、署名したリリース版を作って GitHub Releases に上げる
   （タグと `versionName` が合わなければ失敗します）

## 手元で署名したリリース版を作る

環境変数で鍵を渡すと、`assembleRelease` が署名します（渡さなければ署名なしで作ります）。

```sh
HYAKUNIN_KEYSTORE_FILE=/path/to/hyakunin-release.jks \
HYAKUNIN_KEYSTORE_PASSWORD=... HYAKUNIN_KEY_ALIAS=hyakunin HYAKUNIN_KEY_PASSWORD=... \
  ./gradlew assembleRelease
```
