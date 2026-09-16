package com.azx8788.translateall

/**
 * 无依赖的语言启发式检测 + 语言码表。
 * 目标：判断「这条消息是否已经是目标语言」，不是目标语言才需要翻译。
 */
object Lang {

    /**
     * 可选目标语言列表 name -> code。code 与 Google 翻译接口兼容。
     */
    val SUPPORTED_LANGUAGES: LinkedHashMap<String, String> = linkedMapOf(
        "中文 (简体)" to "zh-CN",
        "中文 (繁体)" to "zh-TW",
        "英语" to "en",
        "日语" to "ja",
        "韩语" to "ko",
        "俄语" to "ru",
        "法语" to "fr",
        "德语" to "de",
        "西班牙语" to "es",
        "葡萄牙语" to "pt",
        "意大利语" to "it",
        "荷兰语" to "nl",
        "波兰语" to "pl",
        "土耳其语" to "tr",
        "阿拉伯语" to "ar",
        "泰语" to "th",
        "越南语" to "vi",
        "印尼语" to "id",
        "马来语" to "ms",
        "印地语" to "hi",
        "乌克兰语" to "uk",
        "希腊语" to "el",
        "捷克语" to "cs",
        "瑞典语" to "sv",
        "丹麦语" to "da",
        "芬兰语" to "fi",
        "挪威语" to "no",
        "匈牙利语" to "hu",
        "罗马尼亚语" to "ro",
        "保加利亚语" to "bg",
        "希伯来语" to "he",
        "波斯语" to "fa",
        "菲律宾语" to "tl",
        "孟加拉语" to "bn",
        "泰米尔语" to "ta",
        "乌尔都语" to "ur",
        "塞尔维亚语" to "sr",
        "克罗地亚语" to "hr",
        "斯洛伐克语" to "sk",
        "立陶宛语" to "lt",
        "拉脱维亚语" to "lv",
    )

    /** DeepL 目标语言映射（不支持的语言 / 省略语言会自动回退到 Google 风格代码） */
    private val DEEPL_TARGETS = mapOf(
        "zh-CN" to "ZH",
        "zh-TW" to "ZH",
        "en" to "EN-US",
        "ja" to "JA",
        "ko" to "KO",
        "ru" to "RU",
        "fr" to "FR",
        "de" to "DE",
        "es" to "ES",
        "pt" to "PT-BR",
        "it" to "IT",
        "nl" to "NL",
        "pl" to "PL",
        "tr" to "TR",
        "uk" to "UK",
        "el" to "EL",
        "cs" to "CS",
        "sv" to "SV",
        "da" to "DA",
        "fi" to "FI",
        "no" to "NB",
        "hu" to "HU",
        "ro" to "RO",
        "bg" to "BG",
        "id" to "ID",
        "sk" to "SK",
        "lt" to "LT",
        "lv" to "LV",
    )

    fun deeplTarget(code: String): String = DEEPL_TARGETS[code] ?: code.split("-")[0].uppercase()

    /** 目标语言显示名（用于按钮 / 菜单文案） */
    fun displayName(code: String): String =
        SUPPORTED_LANGUAGES.entries.firstOrNull { it.value == code }?.key ?: code

    private enum class Script(val weights: Int) {
        CJK(3), KANA(4), HANGUL(3), CYRILLIC(2), ARABIC(3),
        THAI(2), DEVANAGARI(2), HEBREW(2), GREEK(1), LATIN(1), OTHER(0)
    }

    data class Analysis(val main: String, val mainRatio: Float, val total: Int)

    /**
     * 分析文本主要书写系统。
     * 返回脚本名：zh / ja / ko / ru / ar / th / hi / he / el / en / other
     */
    fun analyzeScript(text: String): Analysis {
        if (text.isBlank()) return Analysis("other", 0f, 0)
        var cjk = 0; var kana = 0; var hangul = 0; var cyr = 0; var arab = 0
        var thai = 0; var deva = 0; var heb = 0; var greek = 0; var latin = 0
        for (ch in text) {
            when {
                ch.code in 0x4E00..0x9FFF -> cjk++
                ch.code in 0x3040..0x30FF || ch.code in 0x31F0..0x31FF -> kana++
                ch.code in 0xAC00..0xD7AF || ch.code in 0x1100..0x11FF -> hangul++
                ch.code in 0x0400..0x04FF || ch.code in 0x0500..0x052F -> cyr++
                ch.code in 0x0600..0x06FF || ch.code in 0x0750..0x077F -> arab++
                ch.code in 0x0E00..0x0E7F -> thai++
                ch.code in 0x0900..0x097F -> deva++
                ch.code in 0x0590..0x05FF -> heb++
                ch.code in 0x0370..0x03FF -> greek++
                ch.code in 'A'.code..'Z'.code || ch.code in 'a'.code..'z'.code -> latin++
            }
        }
        val total = cjk + kana + hangul + cyr + arab + thai + deva + heb + greek + latin
        if (total == 0) return Analysis("other", 0f, 0)

        val main = when {
            kana > 0 && cjk >= kana / 2 -> "ja" // 假名+汉字 -> 日文
            kana > total / 4 -> "ja"
            cjk > 0 && cjk >= hangul && cjk >= cyr && cjk >= latin && cjk >= arab -> "zh"
            hangul > cjk && hangul >= latin && hangul >= cyr -> "ko"
            cyr > latin && cyr > cjk && cyr > hangul -> "ru"
            arab > latin && arab > cjk && arab > hangul -> "ar"
            thai >= total / 2 -> "th"
            deva >= total / 2 -> "hi"
            heb >= total / 2 -> "he"
            greek >= total / 2 -> "el"
            latin > 0 -> "en"
            else -> "other"
        }
        val mainCount = when (main) {
            "zh" -> cjk
            "ja" -> cjk + kana
            "ko" -> hangul
            "ru" -> cyr
            "ar" -> arab
            "th" -> thai
            "hi" -> deva
            "he" -> heb
            "el" -> greek
            else -> latin
        }
        return Analysis(main, mainCount.toFloat() / total, total)
    }

    /** 目标语言代码 -> 脚本名 */
    private fun targetScript(code: String): String? = when {
        code.startsWith("zh") -> "zh"
        code == "ja" -> "ja"
        code == "ko" -> "ko"
        code in setOf("ru", "uk", "bg", "sr", "mk", "be") -> "ru"
        code == "ar" -> "ar"
        code == "th" -> "th"
        code == "hi" -> "hi"
        code == "he" -> "he"
        code == "el" -> "el"
        code in setOf(
            "en", "fr", "de", "es", "pt", "it", "nl", "pl", "tr", "vi", "id",
            "ms", "cs", "sv", "da", "fi", "no", "hu", "ro", "sk", "lt", "lv",
            "tl", "ta", "ur", "fa", "bn", "hr", "sl", "et", "af"
        ) -> "en"
        else -> null
    }

    /**
     * 判断文本是否「已经是目标语言」。
     * 目标语言的脚本占消息主要文本的 60% 以上则视为无需翻译。
     */
    fun isAlreadyTarget(text: String, targetLang: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val a = analyzeScript(trimmed)
        // 纯符号 / 数字 / 链接等短内容不翻译
        if (a.main == "other" || a.total < 2) return true
        val ts = targetScript(targetLang) ?: return false
        // 拉丁字母目标：主要脚本是拉丁即可
        if (ts == "en") return a.main == "en" || a.main == "other"
        // 中文目标：日文（含假名）不算中文，但纯汉字算
        if (ts == "zh") {
            if (a.main == "zh") return true
            return false
        }
        return a.main == ts
    }

    /**
     * 中文判定（用于「频道大部分消息非中文」顶部按钮检测）。
     * 宽松判定：文本主体是纯汉字（无假名干扰）→ 中文。
     */
    fun isChineseOrTarget(text: String, targetLang: String): Boolean = isAlreadyTarget(text, targetLang)
    fun isChinese(text: String): Boolean = isAlreadyTarget(text, "zh-CN")
}