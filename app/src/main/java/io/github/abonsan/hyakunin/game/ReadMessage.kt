package io.github.abonsan.hyakunin.game

/** プレイヤーから見た陣の呼び名。自陣＝プレイヤーの陣、敵陣＝CPU の陣。 */
private fun Side.label(): String = if (this == Side.PLAYER) "自陣" else "敵陣"

/**
 * 1回の読みの結果の説明文（プレイヤー視点）。相手＝CPU。
 *
 * @param transfers この読みで既に行われた札の移動（CPU が送った札）
 * @param pendingPlayerSends プレイヤーがこれから送る札の枚数
 */
internal fun buildResultMessage(
    isEmptyCard: Boolean,
    takenBy: Side?,
    takenFrom: Side?,
    faults: Set<Side>,
    safeMisses: Set<Side>,
    transfers: List<Transfer>,
    pendingPlayerSends: Int,
): String {
    val parts = mutableListOf<String>()
    parts += when {
        isEmptyCard -> "空札でした"
        takenBy == Side.PLAYER && takenFrom != null -> "${takenFrom.label()}の札を取りました"
        takenBy == Side.CPU && takenFrom != null -> "相手が${takenFrom.label()}の札を取りました"
        else -> "誰も取れませんでした"
    }

    val received = transfers.count { it.to == Side.PLAYER }
    val bothFaulted = faults.size == 2
    if (Side.PLAYER in faults) {
        parts += if (received > 0) "お手つき！相手から${received}枚送られます" else "あなたのお手つき"
    }
    if (Side.CPU in faults) parts += "相手のお手つき"
    if (bothFaulted) parts += "お互いのお手つきなので札は動きません"
    if (Side.PLAYER !in faults && received > 0) parts += "相手から${received}枚送られます"
    if (pendingPlayerSends > 0) parts += "自陣から${pendingPlayerSends}枚送ってください"
    if (Side.PLAYER in safeMisses) parts += "お手つきにはなりませんが、この読みでは取れません"
    if (Side.CPU in safeMisses) parts += "相手は同じ陣の別の札に触れました"
    return parts.joinToString("。")
}
