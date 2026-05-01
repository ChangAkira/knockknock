package com.example.testppppp.remote

import com.example.testppppp.audio.SectorDirection

data class RemoteDemoSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val authToken: String = "",
    val topAction: RemotePcAction = RemotePcAction.PPT_PREVIOUS,
    val rightAction: RemotePcAction = RemotePcAction.SHOW_DESKTOP,
    val bottomAction: RemotePcAction = RemotePcAction.PPT_NEXT,
) {
    fun actionForSector(sector: SectorDirection): RemotePcAction =
        when (sector) {
            SectorDirection.TOP -> topAction
            SectorDirection.RIGHT -> rightAction
            SectorDirection.BOTTOM -> bottomAction
        }
}
