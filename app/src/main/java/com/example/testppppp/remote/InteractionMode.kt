package com.example.testppppp.remote

/**
 * 模式1：PPT 与桌面控制（固定扇区映射）。
 * 模式2：扇区仅作空间锚点，需用户输入后由 PC 侧 Ollama 生成文本。
 */
enum class InteractionMode(val displayLabel: String) {
    MODE1_PPT("模式1 · PPT/桌面"),
    MODE2_LLM("模式2 · 语言(Ollama)"),
    ;

    companion object {
        fun fromPrefsString(raw: String?): InteractionMode =
            when (raw) {
                "MODE2_LLM" -> MODE2_LLM
                else -> MODE1_PPT
            }

        fun toPrefsString(mode: InteractionMode): String = mode.name
    }
}
