package com.example.testppppp.remote

/**
 * 与 pc-remote/app.py 中 `ACTIONS` 的键一致。
 */
enum class RemotePcAction(val jsonName: String, val label: String) {
    PPT_PREVIOUS("ppt_previous", "PPT 上一页 (Page Up)"),
    PPT_NEXT("ppt_next", "PPT 下一页 (Page Down)"),
    SHOW_DESKTOP("show_desktop", "显示桌面 (Win+D)"),
    ;

    companion object {
        fun fromJsonName(name: String): RemotePcAction? =
            entries.firstOrNull { it.jsonName == name }
    }
}
