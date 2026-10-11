export const meta = {
  name: 'karuta-modes-landscape-interval',
  description: '百人一首アプリ: モード選択・設定画面・画面の向き・横画面最適化（Opus max）と、読み始めのインターバル設定（エンジン Sonnet xhigh）',
  phases: [
    { title: 'Engine', detail: '開始前の時間・読みの間・タップで読み始める（game パッケージ）', model: 'sonnet' },
    { title: 'UI', detail: 'トップ/対戦設定/設定画面、向きの切り替え、横画面、インターバル表示', model: 'opus' },
  ],
}

const COMMON = `
あなたは Android アプリ「百人一首」（Kotlin + Jetpack Compose）の開発を分担するエンジニアです。
リポジトリ: /home/user/Hyakunin-Issyu （ブランチ claude/friendly-dijkstra-1nv3gz）。push は絶対にしないこと。
構成: Gradle 9.8.1 / AGP 9.4.1（Kotlin は AGP 組み込み）/ Kotlin 2.4.21 / Compose BOM 2026.09.00 / compileSdk 37.2 / targetSdk 36 / minSdk 26。
パッケージ: io.github.abonsan.hyakunin （app/src/main/java/io/github/abonsan/hyakunin/）。
ビルド環境:
- Gradle 実行前に必ず export ANDROID_HOME=/opt/android-sdk （worktree には local.properties が無い）。
- ./gradlew testDebugUnitTest / ./gradlew assembleDebug。Maven Central が 429 を返したら 30〜60 秒待って再実行。
- Robolectric 用の android-all-instrumented (SDK 36) は ~/.m2 に取得済み。Robolectric/Roborazzi テストは @Config(sdk = [36]) と @GraphicsMode(NATIVE) を使う（既存の app/src/test/.../ScreenshotSmokeTest.kt 参照）。
- 他のエージェントも同時に Gradle を使うので、gradle --stop や ~/.gradle の削除はしない。
共通の契約ファイル（読むだけ・変更禁止）:
- data/Poem.kt, data/Poems.kt, game/GameConfig.kt, game/MatchUiState.kt
- game/MatchViewModel.kt の公開API（クラス名・コンストラクタ MatchViewModel(config: GameConfig, random: Random = Random.Default)・uiState: StateFlow<MatchUiState>・MatchActions の実装）
契約の変更が必要だと判断したら、変更せずに出力の contractChangeRequests に書くこと。
コードスタイル: 既存ファイルに合わせる（4スペース、末尾カンマ、KDoc/コメントは簡潔な日本語）。
コミットする場合は次の形式（メッセージ本文は英語で簡潔に）:
git -c user.name="Claude" -c user.email="noreply@anthropic.com" commit -m "<summary>" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Jso9opi5opYjEmRv2cvUS3"
`

const CODE_SCHEMA = {
  type: 'object',
  properties: {
    worktreePath: { type: 'string' },
    branch: { type: 'string' },
    commit: { type: 'string' },
    summary: { type: 'string' },
    filesChanged: { type: 'array', items: { type: 'string' } },
    testsRun: { type: 'string' },
    testsPassed: { type: 'boolean' },
    contractChangeRequests: { type: 'array', items: { type: 'string' } },
    screenshots: { type: 'array', items: { type: 'string' } },
    notes: { type: 'array', items: { type: 'string' } },
  },
  required: ['worktreePath', 'branch', 'commit', 'summary', 'filesChanged', 'testsRun', 'testsPassed', 'contractChangeRequests', 'screenshots', 'notes'],
}


const TASK = `${COMMON}
【あなたの担当】既に動いている「百人一首」アプリ（CPU対戦・和風UI・100首データ・テスト一式）の画面構成変更と横画面対応。Kotlin + Jetpack Compose。あなたは独立した git worktree で作業している（カレントディレクトリ）。
まず既存コードを読むこと: app/src/main/java/io/github/abonsan/hyakunin/ 以下の MainActivity.kt、ui/HyakuninApp.kt、ui/home/（HomeScreen.kt, GameConfigStore.kt）、ui/match/、ui/theme/、ui/components/、ui/card/、AndroidManifest.xml、および app/src/test 以下の UI テストとスクリーンショットテスト（Robolectric + Roborazzi）。
game/ と data/ は変更しない（GameConfig などの契約もそのまま使う）。

【ユーザー確定の要件】
1. 起動後のトップ画面: タイトル「百人一首」と、「競技モード」「簡易モード」の選択（それぞれ RuleMode.description 相当の短い説明付き）、および「設定」への入口。モードを選ぶと 2 へ進む。
2. モード選択後の「対戦設定」画面: 空札の枚数（0〜90、既存と同じスライダー＋増減ボタン）、CPUの強さ、読み上げ速度、「対戦開始」。選んだモードが分かる見出しと、トップへ戻る操作。
   対戦設定はモードごとに別々に SharedPreferences へ記憶し、次回そのモードを選んだときに復元する。既存の保存値（モード共通の旧キー）があれば、両モードの初期値として引き継ぐ。
3. トップ画面から開く「設定」画面: 「画面の向き」（自動・縦・横）と「敵陣の札を逆さに表示」。保存して次回起動時も反映。対戦開始時の GameConfig.flipOpponentCards はこの設定から作る。
4. 画面の向きの切り替え: AndroidManifest の縦固定をやめ、MainActivity で設定に応じて requestedOrientation を設定する（自動=ActivityInfo.SCREEN_ORIENTATION_USER（端末の自動回転設定に従う）、縦=SCREEN_ORIENTATION_USER_PORTRAIT、横=SCREEN_ORIENTATION_USER_LANDSCAPE）。起動時（setContent の前）と、設定を変えた直後の両方で反映する。設定画面の「自動」の説明には「端末の自動回転の設定に従います」と添える。
   回転（構成変更）で対戦が途切れないこと: MatchViewModel は保持され、画面遷移の状態・送り札の選択・ダイアログ・一時停止などの UI 状態も rememberSaveable 等で保持する。Android 16 以降の大画面では向きの指定が無視されることがあるので、どの向きでも正しく表示できることが前提。
5. 横画面向けの UI 最適化（最重要）: すべての画面（トップ、対戦設定、設定、対戦画面の Waiting/Reading/Result/送り札選択/お手つき/一時停止/終了、各ダイアログ）を横画面用にレイアウトし直す。向きの判定は Configuration.orientation だけに頼らず、利用可能な幅と高さ（BoxWithConstraints など）で決め、分割画面やタブレットでも破綻しないこと。
   - 対戦画面（横）: 高さが限られるので、例えば左右分割（片側に読み上げ／結果パネル、もう片側に敵陣（上）と自陣（下）の場）や、スコアを縦長のサイドバーにするなど、縦方向の無駄を減らす。札は指で押しやすい大きさ（目安として幅 48dp 以上）を保ち、送り札で片側が最大9枚程度になっても収まるように札の大きさ・段組みを計算する。結果パネルの緑（決まり字）・赤（読み過ぎ）表示は横画面でも読みやすく。
   - トップ・対戦設定・設定（横）: 2段組みなどで、スクロールなしかスクロール最小限で操作できるように。
   - 縦画面の見た目は、今の完成度を保つ（劣化させない）。
6. テスト: Robolectric + Roborazzi のスクリーンショットを、縦（既存）に加えて横（例: qualifiers "w891dp-h411dp-land-xxhdpi"）と小さめの横（例: "w640dp-h360dp-land-xhdpi"）で、上記すべての画面と対戦の主要状態（読み上げ中、結果（緑と赤の両方）、送り札選択、敵陣9枚・自陣1枚、試合終了、一時停止）について撮影する。本物の MatchViewModel（実データ）で横画面の読み上げ中も撮る。
   ほかに: 画面の向き設定 → requestedOrientation の対応のテスト、設定の保存・モードごとの記憶・旧キーからの引き継ぎのテスト、トップ→対戦設定→対戦／トップ→設定の遷移テスト、回転（Activity の再生成）で対戦状態と UI 状態が保たれるテスト。
7. 撮影した PNG は必ず Read ツールで見て、文字の欠け・はみ出し・重なり・縦書きの崩れ・余白の偏り・コントラストを確認し、横画面の見た目が縦画面と同等の完成度になるまで修正を繰り返す。
8. UI に新しい漢字を使ったら python3 tools/subset_fonts.py でフォントのサブセットを更新する（今回は並行作業者がいないので、フォントファイルの変更もコミットしてよい）。
9. ./gradlew testDebugUnitTest assembleDebug lintDebug をすべて通す。完了したら worktree 内でコミットし、worktreePath、branch、commit、撮影した PNG の絶対パス（screenshots）を出力に含める。`


const UI_EXTRA = `
【追加要件（ユーザー確定。上の要件と合わせて実装する）】読み始めまでのインターバル設定
- 契約に追加済み（game/GameConfig.kt と game/MatchUiState.kt を読むこと）: ReadStartMode（AUTO=自動 / TAP=タップで読み始める）、MemorizeTime（開始前の札を覚える時間: 10秒/30秒/1分/2分）、ReadInterval（読みの間: 2秒/4秒/6秒/10秒）、GameConfig.readStartMode / memorizeTime / readInterval、MatchUiState.waitRemainingMillis（自動で読み始めるまでの残りms。TAP 待ちや Waiting 以外は null）と isMemorizing（まだ1枚も読んでいない Waiting なら true）、MatchActions.onStartReading()。
- エンジン側（MatchEngine / MatchViewModel の onStartReading 実装、待ち時間の反映）は別のエージェントが同時に実装中。あなたは game/ を触らない。UI テストでは偽の MatchUiState と MatchActions を使う。
- 対戦設定画面に「読み始め方」（自動 / タップで読み始める）を追加。自動のときは「開始前の時間」と「読みの間」を選べるようにし、タップのときはそれらを隠すか無効化する。モードごとに記憶する（他の対戦設定と同じ扱い）。
- 対戦画面の Waiting 表示: 自動のときは「札を覚える時間」（isMemorizing）または「次の札まで」と残り秒数のカウントダウン（waitRemainingMillis）と「すぐ読む」ボタン（actions.onStartReading()）。タップのときは目立つ「読み始める」ボタン（同じく onStartReading）。Waiting の目的は場の札を確認することなので、自陣・敵陣の札がしっかり見えるようにし、送られてきた札（FieldCard.isNew）が分かる表示を保つ。縦・横の両方で。
- スクリーンショット: 札を覚える時間のカウントダウン、読みの間（送られた札あり）、タップ待ちを、縦と横の両方で撮影する。
`

const ENGINE_TASK = `${COMMON}
【あなたの担当】既に動いている「百人一首」アプリの対戦ロジック（game パッケージ）に、読み始めまでのインターバル設定を実装する。あなたは独立した git worktree で作業している（カレントディレクトリ）。ui/ と res、MainActivity は別のエージェントが同時に変更中なので触らない。data/ も触らない。
契約（変更済み・変更禁止）: game/GameConfig.kt の ReadStartMode / MemorizeTime / ReadInterval と GameConfig.readStartMode / memorizeTime / readInterval、game/MatchUiState.kt の MatchUiState.waitRemainingMillis / isMemorizing と MatchActions.onStartReading()。まずこれらと既存の game/（MatchEngine.kt, MatchViewModel.kt, ReadTiming.kt ほか）とそのテストを読むこと。
仕様:
- AUTO: 最初の読みの前の Waiting は memorizeTime.millis、2枚目以降の Waiting は readInterval.millis 待ってから Reading へ（現在の固定 1500ms を置き換える）。
- TAP: Waiting は自動では終わらず、onStartReading() で Reading へ。
- どちらのモードでも Waiting 中の onStartReading() は待ちを飛ばして直ちに Reading へ。Waiting 以外・一時停止中は無視。
- snapshot（MatchUiState）に waitRemainingMillis（AUTO の Waiting 中の残りms、それ以外は null）と isMemorizing（まだ1枚も読んでいない Waiting なら true）を正しく入れる。一時停止中は残り時間が減らない。onRestart() では再び札を覚える時間から始まる。
- 空札で Result を自動で閉じた後や、Result で onNext() した後の Waiting にも readInterval（または TAP 待ち）を適用する。
- MatchViewModel に onStartReading() を実装（一時停止中は無視）。
- 既存テストのうち固定 1500ms の待ちに依存するものは新仕様に合わせて直し、新しい仕様（AUTO の開始前/読みの間、TAP の無期限待ち、すぐ読む、一時停止中のカウントダウン停止、onRestart、snapshot の各フィールド）のテストを追加する。
- ./gradlew testDebugUnitTest assembleDebug を通す。完了したら worktree 内でコミットし、worktreePath、branch、commit を出力に含める（screenshots は空配列）。`

const [engine, ui] = await Promise.all([
  agent(ENGINE_TASK, { label: 'engine-interval', phase: 'Engine', schema: CODE_SCHEMA, isolation: 'worktree', model: 'sonnet', effort: 'xhigh' }),
  agent(TASK + UI_EXTRA, { label: 'ui-landscape', phase: 'UI', schema: CODE_SCHEMA, isolation: 'worktree', model: 'opus', effort: 'max' }),
])
return { engine, ui }
