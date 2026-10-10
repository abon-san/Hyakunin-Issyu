export const meta = {
  name: 'karuta-engine-ui',
  description: '百人一首アプリ: 対戦ロジック（Sonnet xhigh）と和風UI（Opus max）を worktree で並行実装',
  phases: [
    { title: 'Engine', detail: '決まり字・ルール・CPU・ViewModel・テスト', model: 'sonnet' },
    { title: 'UI', detail: '和風UI・画面・スクリーンショットテスト', model: 'opus' },
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

const RULES = `
【対戦の仕様（ユーザー確定事項）】
- 自陣（プレイヤー、画面下）と敵陣（CPU、画面上）に各5枚（GameConfig.CARDS_PER_SIDE）の取り札を置く。
- 空札（読まれるが場にない札）の枚数は GameConfig.emptyCardCount（0〜90、ユーザーが自由に変更）。
- 音声は無し。読み上げは「読み（現代仮名遣い）を仮名1文字ずつ画面に表示」して表現する。
- 決まり字は「読まれた札で変化」: その札が読まれた時点で、まだ読まれていない札（100首から既に読まれた札を除いたもの。場にあるか・空札かは問わない。プレイヤーにはどれが空札か分からないため）の中で、上の句の読みの先頭何文字で一意に決まるか。
- 札を取った後、上の句（と必要なら下の句）の読みを表示し、決まり字部分を緑、取った時点で決まり字を超えて読み上げられていた部分を赤で表示する。
- ルールは2種類から選択（GameConfig.ruleMode）:
  - COMPETITIVE（競技かるた準拠）: 自陣の札が先に0枚になった方が勝ち。敵陣の札を取ったら自陣から1枚を相手へ送る（送り札）。お手つき（出札と違う陣の札に触れる、または空札が読まれたときに札に触れる）をすると相手から1枚送られる。出札と同じ陣の違う札に触れた場合はお手つきにならない（ただしその読みではもう取れない）。両者がお手つきした場合は罰の送り札は相殺（移動なし）。送る札が足りない場合は持っている分だけ。いずれかの陣が0枚になった時点でその陣の側の勝ち。
  - SIMPLE（簡易）: 取った枚数が多い方が勝ち（同数は引き分け）。違う札に触れるとその読みでは取れなくなるだけで、札の移動は無い（お手つき数としては数える）。場の10枚がすべて読まれたら終了。
- 相手はコンピュータ（GameConfig.cpuLevel: 初級/中級/上級/名人）。
`

const DATA_RULES = `
【Poem の各フィールドの規則】（data/Poem.kt の KDoc も参照）
- 句の区切りはすべて全角スペース U+3000。kami と kamiYomi は3句、shimo / shimoYomi / shimoKana は2句。区切り位置は対応するフィールド間で一致させる。
- kami / shimo: 一般的な小倉百人一首の表記（漢字かな交じり・歴史的仮名遣い）。例「秋の田の　かりほの庵の　苫をあらみ」「わが衣手は　露にぬれつつ」。
- kamiYomi / shimoYomi: 読手が読み上げる音を現代仮名遣いのひらがなで（漢字・カタカナ・長音符「ー」は使わない）。
  変換規則: 語中・語尾のハ行→ワ行音（かりほ→かりお、いほ→いお、おもひ→おもい、こひ→こい、いにしへ→いにしえ、なほ→なお、にほひ→におい、あはぢ→あわじ）、ゐ→い、ゑ→え、助詞以外の「を」→お（をぐら→おぐら、をとめ→おとめ、惜し→おし）、ぢ→じ、づ→ず、くわ→か、
  長音化（てふ→ちょう、けふ→きょう、あふ（逢ふ）→おう、あふさか→おうさか、まうす→もうす 等）。
  助詞の「は」「へ」「を」はそのまま残す。助動詞の「む」「らむ」「けむ」は「む」のまま（「ん」にしない）。語頭のハ行はそのまま（はなのいろは）。
  例: 1番 kamiYomi「あきのたの　かりおのいおの　とまをあらみ」、2番 shimoYomi「ころもほすちょう　あまのかぐやま」、44番 kamiYomi は「おうことの　…」で始まる。
- shimoKana: 取り札に書かれる下の句（歴史的仮名遣いのひらがな）。例「ころもほすてふ　あまのかぐやま」「けふここのへに　にほひぬるかな」。
- author: 一般的な作者名表記（例: 天智天皇、柿本人麻呂、中納言家持、安倍仲麿、小野小町、蝉丸、参議篁、僧正遍昭、陽成院 など）。authorYomi: 現代仮名遣いのひらがな（例: てんじてんのう、かきのもとのひとまろ、ちゅうなごんやかもち）。
- 既存の app/src/main/java/io/github/abonsan/hyakunin/data/PoemsPart1.kt の1〜3番の書式（Kotlin の名前付き引数、末尾カンマ）に厳密に合わせる。
`

const DATA_SCHEMA = {
  type: 'object',
  properties: {
    file: { type: 'string' },
    poemsWritten: { type: 'number' },
    uncertainties: { type: 'array', items: { type: 'string' } },
  },
  required: ['file', 'poemsWritten', 'uncertainties'],
}

const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    file: { type: 'string' },
    fixes: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          no: { type: 'number' }, field: { type: 'string' },
          before: { type: 'string' }, after: { type: 'string' }, reason: { type: 'string' },
        },
        required: ['no', 'field', 'before', 'after', 'reason'],
      },
    },
    remainingConcerns: { type: 'array', items: { type: 'string' } },
    sourcesUsed: { type: 'array', items: { type: 'string' } },
  },
  required: ['file', 'fixes', 'remainingConcerns', 'sourcesUsed'],
}

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

const PARTS = [
  { from: 1, to: 50, file: 'app/src/main/java/io/github/abonsan/hyakunin/data/PoemsPart1.kt', varName: 'POEMS_1_TO_50' },
  { from: 51, to: 100, file: 'app/src/main/java/io/github/abonsan/hyakunin/data/PoemsPart2.kt', varName: 'POEMS_51_TO_100' },
]

const dataPrompt = (p) => `${COMMON}
${DATA_RULES}
【あなたの担当】小倉百人一首 ${p.from}〜${p.to}番のデータ作成。
/home/user/Hyakunin-Issyu/${p.file} を全面的に書き換え、internal val ${p.varName}: List<Poem> = listOf( ... ) に ${p.from}〜${p.to}番の ${p.to - p.from + 1} 首を歌番号順に入れる（ファイル先頭のコメントは「小倉百人一首 ${p.from}〜${p.to}番。」程度に変更）。
- このファイル以外は変更しない。Gradle は実行しない（他エージェントが同時にビルドしている）。git commit もしない。
- 正確さが最重要。ja.wikipedia.org にはこの環境から現在接続できない（後で別担当がウィキペディアと機械照合する）。知識に頼るだけでなく、WebSearch / WebFetch が使えるなら信頼できる出典（日本語版ウィキペディア「小倉百人一首」、各歌の記事、全日本かるた協会、時雨殿など）で、迷う箇所（漢字表記、歴史的仮名遣い、作者名）を確認すること。
- 書き終えたら python3 で自己チェック: 番号が連番か、各 Yomi/shimoKana がひらがなと U+3000 のみか、句数（上3・下2）が全フィールドで一致するか。
- 判断に迷った点（表記揺れなど）は uncertainties に書く。`

const verifyPrompt = (p, written) => `${COMMON}
${DATA_RULES}
【あなたの担当】小倉百人一首 ${p.from}〜${p.to}番のデータの敵対的検証と修正。
別のエージェントが /home/user/Hyakunin-Issyu/${p.file} を作成した（作成者のメモ: ${JSON.stringify(written && written.uncertainties || [])}）。誤りが必ず含まれている前提で、全首の全フィールドを1つずつ検証すること。
- 歌本文（kami/shimo）の語句・漢字表記・歴史的仮名遣いが標準的な小倉百人一首と一致するか。上の句・下の句の分け目と句の区切り。
- kamiYomi/shimoYomi が kami/shimo と同じ語を表し、上記の現代仮名遣い変換規則に正しく従っているか（特に上の句の先頭部分は決まり字の計算に使うので最重要）。
- shimoKana が正しい歴史的仮名遣いか（例: てふ、けふ、にほひ、ゐ、ゑ、を の使い分け）。
- author / authorYomi が一般的な表記・読みか。
- WebSearch / WebFetch が使えるなら、日本語版ウィキペディア「小倉百人一首」や各歌の記事など複数の出典で照合する（使えない場合はその旨を sourcesUsed に書く）。
- 誤りを見つけたらそのファイルを直接修正する（このファイル以外は変更しない。Gradle 実行・git commit はしない）。
- 修正後に python3 で自己チェック: 番号の連番、Yomi/shimoKana がひらがなと U+3000 のみ、句数の一致。
- 修正したものは fixes に、確信が持てず残った懸念は remainingConcerns に書く。`

const ENGINE_PROMPT = `${COMMON}
${RULES}
【あなたの担当】対戦ロジック（game パッケージ）と MatchViewModel の実装、および JVM ユニットテスト。あなたは独立した git worktree で作業している（カレントディレクトリ）。UI（ui パッケージ、MainActivity、res、AndroidManifest）は別のエージェントが実装するので触らない。data/PoemsPart*.kt も触らない（現在は1〜3番だけの暫定データ。別エージェントが100首に置き換え中）。
まず契約ファイル（game/GameConfig.kt、game/MatchUiState.kt、data/Poem.kt、game/MatchViewModel.kt のスタブ）を読むこと。

1. 仮名ユーティリティと決まり字（例: game/Kana.kt, game/Kimariji.kt）
- 読みの「仮名の数」は空白（U+3000 等）を除いて数える。上の句→下の句の通し番号。
- 比較用正規化: を→お、ゐ→い、ゑ→え、ぢ→じ、づ→ず（読み上げ音が同じため）。さらに、お段の仮名（お こ そ と の ほ も よ ろ ご ぞ ど ぼ ぽ ょ）の直後の「う」は「お」と同一視する（長音。「おう」と「おお」は同じ音）。例: 44番「おうことの」は60番「おおえやま」・95番「おおけなく」と2文字目まで同じ音なので決まり字は3（「おうこ」）。この規則で100首の初期決まり字の分布は一字7・二字42・三字37・四字6・五字2・六字6 になる（データ統合後に統合担当がテストを追加する）。
- kimarijiLength(target, unread): 正規化した上の句の読みについて、unread 内の他の歌の上の句の読みのどれとも先頭が一致しなくなる最小の文字数 k。直後の文字が小書き仮名（ゃゅょぁぃぅぇぉゎ）なら k を伸ばしてそれも含める（「ちょ」のように一音で読むため）。上の句全体の長さを上限とする。
- 初期（100首基準）の決まり字も同じ関数で計算できること。

2. 対戦エンジン（純粋な Kotlin、時間は外から与える。Random 注入で決定的にテストできること）
- 準備: 100首（poems を引数で受け取れるように。既定は Poems.all）をシャッフルし、先頭5枚を自陣、次の5枚を敵陣、次の emptyCardCount 枚を空札とする。読み札 = 場の10枚 + 空札 をシャッフル。totalReads = 10 + emptyCardCount。テスト用に配置と読み順を直接指定できる内部コンストラクタ/ファクトリを用意する。
- 読み上げタイミング（ms）: 次の読みまでの待ち（Waiting）1500ms → Reading。仮名を charMillis ごとに1文字ずつ表示。句の区切り（空白）の位置では charMillis 分の間を追加、上の句と下の句の間は charMillis×3 の間。
- 場の札が読まれた場合は上の句の後も下の句まで表示を続け、誰かが取るまで Reading のまま待つ。空札の場合は上の句を表示し終えてから 1200ms 後に読みを終える。
- プレイヤーが札に触れる（Reading 中、未ロックアウト時）:
  - 出札そのもの → プレイヤーが取る。revealedAtTake = その時点の revealedKana。決まり字が出る前でも正しい札なら取れる。
  - それ以外: COMPETITIVE では、出札が場にあり、触れた札が出札と同じ陣なら safeMiss（罰なし、この読みではロックアウト）、それ以外（違う陣、または空札の読み）はお手つき（ロックアウト、罰は読みの終了時に適用）。SIMPLE では常にロックアウト＋お手つき数のカウントのみ（札の移動なし）。
- CPU（CpuProfile をレベルごとに定義）: 読みの開始時に行動を決める。
  - 出札が場にある: 決まり字の最後の文字が表示された時刻 + 反応時間（乱数）に出札を取る。反応時間の目安: 初級 1400〜2600ms、中級 750〜1500ms、上級 350〜800ms、名人 150〜400ms。ただし遅くとも下の句を表示し終えてから 500ms 以内には取る。
  - お手つき: レベルごとの確率（初級 10%、中級 5%、上級 2%、名人 0%）で、出札以外の場の札のうち上の句の読みの共通接頭辞が最長（1文字以上）の札を、その共通部分を表示し終えた時点 + 反応時間の半分で触れる（「友札」に早く飛び出す誤り）。お手つき/セーフの判定はプレイヤーと同じ規則。触れた後はその読みでは取らない。
  - 空札では上記のお手つき以外は何もしない。
  - 早い方が取る。プレイヤーが先に取ったら CPU の行動はキャンセル。
- 両者ともロックアウトして誰も取らない場合: 下の句を表示し終えてから 1500ms 後に、出札を場から取り除き（取り手なし）、罰の送り札を適用。
- 読みの終了（取った／空札終了／誰も取らない）時の処理:
  - 取った側 X、札があった陣 Y: X の取り札数+1、Y から除去。
  - COMPETITIVE の送り札: X から相手へ送る枚数 = (Y が相手の陣なら1) + (相手がお手つきなら1)。X がお手つきした側から受け取ることは起こらない（X はロックアウトされていないため）。空札・取り手なしの場合: 片方だけお手つきなら、もう片方がお手つきした側へ1枚送る。両方なら相殺。
  - CPU が送る札は CPU の陣からランダムに選んで即座に移動。プレイヤーが送る場合は pendingPlayerSends に枚数を設定し、Result 中に onCardTap(Side.PLAYER, no) で自陣の札を1枚ずつ選ばせる（敵陣の札のタップは無視）。送られた札は FieldCard.isNew = true（次の読みの開始で false に戻す）。
  - 送る札が足りなければある分だけ。いずれかの陣が0枚になった時点でその側の勝ち（COMPETITIVE）。SIMPLE は場の10枚がすべて読まれたら取り札数で勝敗（同数は引き分け）。
  - 読み札が尽きても勝敗が付かない場合（理論上ほぼ無いが念のため）、COMPETITIVE は陣の残り枚数が少ない方の勝ち・同数は引き分け。
- フェーズ遷移: Waiting → Reading → Result。空札でお手つきが無い場合は Result を 1200ms 表示した後に自動で Waiting へ。それ以外の Result は onNext() で進む（pendingPlayerSends > 0 の間は onNext を無視）。勝敗が付いた読みでも、まず Result で結果（緑・赤の表示）を見せ、onNext() で Finished(winner) へ。
- ReadResult.message はプレイヤー視点の日本語（自陣＝プレイヤーの陣、敵陣＝CPUの陣、相手＝CPU）。例:「自陣の札を取りました」「敵陣の札を取りました。自陣から1枚送ってください」「相手が自陣の札を取りました」「空札でした」「お手つき！相手から1枚送られます」「相手のお手つき」。kimarijiLength は読み開始時点（その札を含む未読札で判定）の値。

3. MatchViewModel（game/MatchViewModel.kt のスタブを本実装に置き換える）
- 公開APIは変えない（コンストラクタに既定値付きの引数を random の後に追加するのは可。例: poems、時間定数）。生成と同時に試合を開始する。
- viewModelScope で 1 本のループを回す設計を推奨: 一定刻み（例 20ms）で delay し、一時停止中は経過時間を進めない。経過時間からの表示文字数・CPU の行動・自動遷移を判定する。
- setPaused(true) の間は時間が止まり、タップも無視。onRestart() は同じ設定で配置をやり直して最初から。
- uiState は MatchUiState の全フィールドを正しく埋める（reading は Result 中も直前の読みを保持し、取った時点で revealedKana を止める）。

4. テスト（app/src/test/java/io/github/abonsan/hyakunin/game/ 以下、JVM で動くもの。Robolectric は不要）
- 決まり字: 合成データでの境界ケース（小書き仮名の延長、を/お の同一視、読まれた札による短縮、例: 「あさぼらけ　あ…」「あさぼらけ　う…」「あさじうの…」で一方が読まれると他方が短くなる）。
- ルール: COMPETITIVE と SIMPLE の各ケース（自陣/敵陣の取り、送り札の選択、お手つきとセーフ、空札でのお手つき、両者お手つきの相殺、最後の1枚で勝ち、送る札が足りない場合、引き分け）。
- CPU: 反応時間が範囲内、名人はお手つきしない、取り合いで早い方が取る。
- ViewModel: kotlinx-coroutines-test（Dispatchers.setMain(StandardTestDispatcher)、advanceTimeBy）で、読み上げの進行、一時停止で時間が止まること、CPU が取ること、onNext/onRestart。合成の100首（重複しない読み）を poems 引数で渡す。
- ./gradlew testDebugUnitTest と ./gradlew assembleDebug を通すこと（既存の ScreenshotSmokeTest も通る状態を保つ）。

完了したら worktree 内でコミットし、worktreePath（pwd）、branch（git rev-parse --abbrev-ref HEAD）、commit（git rev-parse HEAD）を出力に含める。screenshots は空配列でよい。`

const UI_PROMPT = `${COMMON}
${RULES}
【あなたの担当】和風デザインの UI 全体（ui パッケージ、MainActivity、res、AndroidManifest）と Robolectric + Roborazzi によるスクリーンショットテスト。あなたは独立した git worktree で作業している（カレントディレクトリ）。対戦ロジック（game パッケージの GameConfig.kt / MatchUiState.kt 以外のファイル、MatchViewModel の中身）は別のエージェントが実装するので触らない。data/ も触らない（暫定で1〜3番のみ）。
まず契約ファイル（game/GameConfig.kt、game/MatchUiState.kt、data/Poem.kt、game/MatchViewModel.kt のスタブ）を読むこと。UI は MatchUiState と MatchActions だけに依存させ、ステートレスな Composable にする。

画面と機能:
1. ホーム（対戦設定）: タイトル「百人一首」、ルール（競技かるた/簡易、RuleMode.description を表示）、空札の枚数（0〜90、スライダー＋増減ボタン＋数値表示）、CPUの強さ（CpuLevel）、読み上げ速度（ReadingSpeed）、「敵陣の札を逆さに表示」（flipOpponentCards）。設定は SharedPreferences に保存・復元。「対戦開始」ボタン。
2. 対戦画面: ステートレスな MatchScreen(state: MatchUiState, actions: MatchActions, onExit: () -> Unit) と、MatchRoute(config, onExit)（viewModel { MatchViewModel(config) } を対戦ごとに生成、collectAsStateWithLifecycle、ライフサイクル ON_STOP/ON_START で setPaused、BackHandler で「対戦をやめますか？」確認ダイアログ）。
   - 上: 敵陣（CPU の札）、中央: 読み上げ表示、下: 自陣（プレイヤーの札）。各陣は1段最大5枚で折り返し（送り札で最大9枚程度になりうる）。札の幅は画面幅から計算（5枚＋間隔が収まる幅、上限あり）。
   - 取り札（Torifuda）: 縦横比 約 52:73、生成り色の札面と細い枠。shimoKana から空白を除き3列の縦書き（右の列から、1列 ceil(n/3) 文字）。縦書きは1文字ずつ縦に並べ、fontFeatureSettings = "vert" で小書き仮名などを縦書き用字形にする。flipOpponentCards のとき敵陣の札は180度回転。isNew の札は金色の縁などで強調。
   - 読み上げ表示（Reading/Waiting）: 「第 n 首 / 全 m 首」と、ReadingState の読み（上の句 kamiYomi、下の句 shimoYomi）を revealedKana 文字まで表示。未表示の文字は透明で描いてレイアウトが動かないようにする。大きめの明朝体。Waiting 中は「次の札を読みます」等。
   - 自分がロックアウト中（playerLockedOut）は自陣・敵陣の札を少し暗くし「お手つき」または「この札はもう取れません」を表示。
   - 結果表示（Result）: lastResult を使い、上の句と下の句の読みを全文表示して、仮名の通し番号（空白を除く）が [0, kimarijiLength) の文字を緑、[kimarijiLength, revealedAtTake) を赤、それ以外を薄墨色で表示（revealedAtTake が null なら決まり字だけ緑、残りは通常の墨色）。凡例（緑＝決まり字、赤＝決まり字を超えて読まれた部分）、漢字表記の歌（kami/shimo）、歌番号、作者、message、お手つき・送り札の情報も表示。revealedAtTake < kimarijiLength なら「決まり字が出る前に取りました」と添える。
     pendingPlayerSends > 0 のときは「送る札を自陣から選んでください（あと n 枚）」と表示し、自陣の札を選択可能に強調、タップで actions.onCardTap(Side.PLAYER, no)。pendingPlayerSends == 0 で「次へ」ボタン（actions.onNext）。
     結果表示は対戦の盤面を隠しすぎないよう、中央の読み上げ欄を置き換えるかボトムシート風のパネルにする（送る札を選ぶとき自陣が見えること）。
   - 上部のスコア: COMPETITIVE は自陣/敵陣の残り枚数、SIMPLE は取った札の枚数。お手つき数、読み n/全 m。一時停止ボタン（ダイアログ: 再開 / やめる）。
   - Finished: 勝ち/負け/引き分けと成績（取った枚数、お手つき数）、「もう一度」（actions.onRestart）と「ホームへ」（onExit）。
3. MainActivity: enableEdgeToEdge、ホーム ⇔ 対戦の簡単な画面遷移（Navigation ライブラリは不要）。縦画面固定。
4. 和風デザイン（Material 3 を土台に独自配色、ダイナミックカラーは使わない）:
   - 色の目安: 和紙の生成り #F4ECD8（背景）、墨 #2B2621、薄墨 #8A8178、朱 #C8452D（主ボタン・アクセント）、金茶 #B08D3C（縁）、畳 #C9C08A 系（陣の背景、細い畳目の線を Canvas で描くと良い）、紺 #1F3A5F。決まり字の緑と読み過ぎの赤は生成り背景で十分なコントラスト（4.5:1 以上）を確保（例: 緑 #2E7D32 付近、赤 #C3272B 付近）。色名は Color.kt に定数化。
   - フォント: res/font/shippori_mincho_regular.ttf と shippori_mincho_bold.ttf（Shippori Mincho のサブセット）を FontFamily にし、Typography 全体に使う。UI に新しい漢字を使ったら python3 tools/subset_fonts.py を実行してサブセットを更新する（スクリーンショット確認用）。ただしフォントファイルの変更はコミットしない（コミット前に git checkout -- app/src/main/res/font で戻す。統合時に再生成する）。
   - 起動時の背景: res/values/themes.xml に android:Theme.Material.Light.NoActionBar を親としたテーマ（windowBackground を生成り色）を作り、マニフェストで使う。
   - アプリアイコン: アダプティブアイコン（mipmap-anydpi-v26/ic_launcher.xml と ic_launcher_round.xml、背景は朱、前景は生成りの取り札に墨の縦線3本などのベクター、monochrome も用意）。マニフェストの icon / roundIcon に設定。
5. スクリーンショットテスト（app/src/test/java/io/github/abonsan/hyakunin/ui/ 以下、Robolectric + Roborazzi、@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi") など一般的なスマホサイズ）:
   偽の MatchUiState（data の1〜3番や、テスト内で作った Poem を使う）で次を撮影し、app/build/outputs/roborazzi/ に PNG を出力: ホーム、読み上げ中、結果（緑と赤の両方が出る例: kimarijiLength=3, revealedAtTake=7 など）、送る札の選択中、お手つきでロックアウト中、敵陣9枚・自陣1枚の盤面、試合終了。
   撮影した PNG は Read ツールで実際に見て、文字の欠け・はみ出し・重なり・縦書きの崩れ・色のコントラストを確認し、見た目が良くなるまで修正を繰り返すこと（デザインの完成度を重視）。不要になった ScreenshotSmokeTest.kt は削除してよい。
6. ./gradlew testDebugUnitTest と ./gradlew assembleDebug を通すこと。

完了したら（フォントファイルを戻したうえで）worktree 内でコミットし、worktreePath（pwd）、branch、commit、撮影した PNG の絶対パス（screenshots）を出力に含める。`


const [engineResult, uiResult] = await Promise.all([
  agent(ENGINE_PROMPT, { label: 'engine', phase: 'Engine', schema: CODE_SCHEMA, isolation: 'worktree', model: 'sonnet', effort: 'xhigh' }),
  agent(UI_PROMPT, { label: 'ui', phase: 'UI', schema: CODE_SCHEMA, isolation: 'worktree', model: 'opus', effort: 'max' }),
])
return { engine: engineResult, ui: uiResult }
