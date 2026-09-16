package com.azx8788.translateall

import com.aliucord.Http
import com.aliucord.api.SettingsAPI
import org.json.JSONArray
import org.json.JSONObject

sealed class TranslateOutcome {
    data class Success(val text: String, val detectedSource: String?) : TranslateOutcome()
    data class Failure(val message: String) : TranslateOutcome()
}

/**
 * 三引擎翻译器：Google（免费免 Key）/ DeepL / AI（OpenAI 兼容）。
 * 所有方法都是阻塞的，必须在后台线程调用（Utils.threadPool）。
 */
object Translators {

    private const val GOOGLE_URL = "https://translate.googleapis.com/translate_a/single"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4592.0 Safari/537.36"

    /** 把长文本切成 <= maxLen 的块（尽量在换行 / 句号处断开） */
    private fun splitText(text: String, maxLen: Int = 3000): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        val splits = text.split(Regex("(?<=[。！？!?\\n])"))
        fun flush() {
            if (sb.isNotEmpty()) {
                chunks.add(sb.toString())
                sb.clear()
            }
        }
        for (part in splits) {
            // 单个超长句子直接硬切
            var p = part
            while (p.length > maxLen) {
                if (sb.isNotEmpty()) flush()
                chunks.add(p.substring(0, maxLen))
                p = p.substring(maxLen)
            }
            if (sb.length + p.length > maxLen) flush()
            sb.append(p)
        }
        flush()
        return if (chunks.isEmpty()) listOf(text) else chunks
    }

    /** Google 免费网页接口（无需 Key） */
    private fun google(text: String, target: String): TranslateOutcome = try {
        var detected: String? = null
        val result = StringBuilder()
        for (chunk in splitText(text)) {
            val url = Http.QueryBuilder(GOOGLE_URL).apply {
                append("client", "gtx")
                append("sl", "auto")
                append("tl", target)
                append("dt", "t")
                append("q", chunk)
            }
            val res = Http.Request(url.toString(), "GET").apply {
                setHeader("Content-Type", "application/json")
                setHeader("User-Agent", UA)
            }.execute()
            if (!res.ok()) {
                return TranslateOutcome.Failure(
                    if (res.statusCode == 429) "Google 翻译被限流(429)，请稍后再试"
                    else "Google 翻译请求失败(${res.statusCode})",
                )
            }
            val json = JSONArray(res.text())
            if (detected == null) detected = json.optString(2).takeIf { it.isNotEmpty() }
            val sections = json.getJSONArray(0)
            for (i in 0 until sections.length()) {
                result.append(sections.getJSONArray(i).getString(0))
            }
        }
        TranslateOutcome.Success(result.toString(), detected)
    } catch (ex: Exception) {
        TranslateOutcome.Failure("Google 翻译异常: ${ex.message}")
    }

    /** DeepL 翻译（需要 API Key） */
    private fun deepl(text: String, target: String, settings: SettingsAPI): TranslateOutcome = try {
        val key = settings.getString("deeplKey", "").trim()
        if (key.isEmpty()) return TranslateOutcome.Failure("请先在插件设置中填写 DeepL API Key")
        val baseUrl = settings.getString("deeplUrl", "https://api-free.deepl.com/v2/translate")
            .trim().trimEnd('/')

        var detected: String? = null
        val result = StringBuilder()
        for (chunk in splitText(text, 4000)) {
            val res = Http.Request("$baseUrl/translate", "POST").apply {
                setHeader("Content-Type", "application/x-www-form-urlencoded")
                setHeader("User-Agent", UA)
            }.executeWithUrlEncodedForm(
                mapOf(
                    "auth_key" to key,
                    "text" to chunk,
                    "target_lang" to Lang.deeplTarget(target),
                ),
            )
            if (!res.ok()) {
                val msg = runCatching { res.text() }.getOrNull().orEmpty()
                return TranslateOutcome.Failure(
                    when (res.statusCode) {
                        403 -> "DeepL Key 无效或无权访问(403)"
                        429 -> "DeepL 额度已用尽或限流(429)"
                        456 -> "DeepL 已达到字符配额(456)"
                        else -> "DeepL 请求失败(${res.statusCode}) $msg"
                    },
                )
            }
            val json = JSONObject(res.text())
            val translations = json.optJSONArray("translations") ?: JSONArray()
            for (i in 0 until translations.length()) {
                val item = translations.optJSONObject(i) ?: continue
                result.append(item.optString("text"))
                if (detected == null) {
                    detected = item.optString("detected_source_language").takeIf { it.isNotEmpty() }
                }
            }
        }
        TranslateOutcome.Success(result.toString(), detected)
    } catch (ex: Exception) {
        TranslateOutcome.Failure("DeepL 翻译异常: ${ex.message}")
    }

    /** AI 翻译（OpenAI 兼容接口，DeepSeek 等均可） */
    private fun ai(
        text: String,
        target: String,
        contextLines: List<Pair<String, String>>,
        settings: SettingsAPI,
    ): TranslateOutcome = try {
        val apiUrl = settings.getString("aiUrl", "https://api.deepseek.com/chat/completions").trim()
        val key = settings.getString("aiKey", "").trim()
        val model = settings.getString("aiModel", "deepseek-chat").trim()
        if (apiUrl.isEmpty() || key.isEmpty()) {
            return TranslateOutcome.Failure("请先在插件设置的「AI 翻译」中填写 API URL 与 API Key")
        }

        val systemPrompt = settings.getString(
            "aiSystemPrompt",
            "你是一名专业的翻译引擎。请只输出译文本身，不要添加任何解释、引号或附加文字。保持原文的换行与格式，语气自然。",
        )
        val userPromptTemplate = settings.getString(
            "aiUserPrompt",
            "请将以下文本翻译成{target}：\n\n{text}",
        )
        val temperature = settings.getFloat("aiTemperature", 0.7f).toDouble()
        val useContext = settings.getBool("aiUseContext", true)
        val contextCount = settings.getInt("aiContextCount", 5)

        val sb = StringBuilder()
        if (useContext && contextLines.isNotEmpty()) {
            sb.append("【对话上下文】以下是最近消息的原文与译文，供你保持术语和语气一致：\n")
            for ((src, tgt) in contextLines.takeLast(contextCount)) {
                sb.append("- 原文: ").append(truncate(src, 400)).append('\n')
                sb.append("  译文: ").append(truncate(tgt, 400)).append('\n')
            }
            sb.append('\n')
        }
        sb.append(
            userPromptTemplate
                .replace("{target}", Lang.displayName(target))
                .replace("{text}", text),
        )

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", sb.toString()))
            })
        }

        val res = Http.Request(apiUrl, "POST").apply {
            setHeader("Authorization", "Bearer $key")
            setHeader("Content-Type", "application/json")
        }.executeWithBody(body.toString())

        if (!res.ok()) {
            val msg = runCatching { res.text() }.getOrNull().orEmpty()
            return TranslateOutcome.Failure(
                when (res.statusCode) {
                    401 -> "AI API Key 无效(401)"
                    429 -> "AI 接口限流或额度不足(429)"
                    else -> "AI 翻译请求失败(${res.statusCode}) ${truncate(msg, 200)}"
                },
            )
        }
        val json = JSONObject(res.text())
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return TranslateOutcome.Failure("AI 接口返回格式异常: ${truncate(json.toString(), 200)}")
        }
        val content = choices.getJSONObject(0)
            .optJSONObject("message")?.optString("content")
            ?.trim().orEmpty()
        if (content.isEmpty()) return TranslateOutcome.Failure("AI 接口未返回译文内容")
        TranslateOutcome.Success(content, null)
    } catch (ex: Exception) {
        TranslateOutcome.Failure("AI 翻译异常: ${ex.message}")
    }

    private fun truncate(s: String, n: Int) = if (s.length <= n) s else s.substring(0, n) + "…"

    /** 统一入口 */
    fun translate(
        engine: String,
        text: String,
        target: String,
        contextLines: List<Pair<String, String>>,
        settings: SettingsAPI,
    ): TranslateOutcome = when (engine) {
        "deepl" -> deepl(text, target, settings)
        "ai" -> ai(text, target, contextLines, settings)
        else -> google(text, target)
    }
}