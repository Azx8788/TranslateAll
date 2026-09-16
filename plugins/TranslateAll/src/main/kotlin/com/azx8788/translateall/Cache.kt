package com.azx8788.translateall

import com.aliucord.Constants
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 永久翻译缓存：原文 -> 译文（带目标语言）。
 * 命中缓存直接返回译文，避免重复调用翻译 API（省 token / 请求次数）。
 *
 * 存储：/storage/emulated/0/Aliucord/TranslateAll/cache.json
 * 结构：{ "v": 1, "pairs": { "<sha1(原文)>": { "s": 原文, "t": 译文, "l": 目标语言, "ts": 时间戳 } } }
 */
class TranslationCache {

    companion object {
        const val MAX_ENTRIES = 5000

        private fun sha1(text: String): String {
            val md = MessageDigest.getInstance("SHA-1")
            return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }

    data class Entry(val src: String, val tgt: String, val lang: String, val ts: Long = System.currentTimeMillis())

    private val pairs = LinkedHashMap<String, Entry>()
    private val file: File = File(Constants.BASE_PATH, "TranslateAll/cache.json")
    private var dirty = false

    /** 缓存条数 */
    val size: Int get() = pairs.size

    @Synchronized
    fun load() {
        try {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            val jsonPairs = json.optJSONObject("pairs") ?: return
            val keys = jsonPairs.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entryObj = jsonPairs.optJSONObject(key) ?: continue
                val src = entryObj.optString("s")
                val tgt = entryObj.optString("t")
                val lang = entryObj.optString("l")
                if (src.isEmpty() || tgt.isEmpty()) continue
                pairs[key] = Entry(src, tgt, lang, entryObj.optLong("ts", System.currentTimeMillis()))
            }
        } catch (ex: Exception) {
            com.aliucord.Utils.log("TranslateAll cache load failed: ${ex.message}")
        }
    }

    /** 查询缓存：原文字面匹配且目标语言一致才命中。 */
    @Synchronized
    fun get(src: String, targetLang: String): Entry? {
        val e = pairs[sha1(src)] ?: return null
        return if (e.lang == targetLang) e else null
    }

    @Synchronized
    fun put(src: String, tgt: String, targetLang: String) {
        if (src.isBlank() || tgt.isBlank()) return
        pairs.remove(sha1(src))
        pairs[sha1(src)] = Entry(src, tgt, targetLang)
        while (pairs.size > MAX_ENTRIES) {
            val oldest = pairs.entries.firstOrNull() ?: break
            pairs.remove(oldest.key)
        }
        dirty = true
    }

    @Synchronized
    fun clear() {
        pairs.clear()
        dirty = false
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    /** 持久化（防抖：调用方自己控制频率，写入走调用方线程即可，这里直接落盘） */
    @Synchronized
    fun save() {
        if (!dirty) return
        try {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            json.put("v", 1)
            val jsonPairs = JSONObject()
            for ((key, e) in pairs) {
                jsonPairs.put(
                    key,
                    JSONObject()
                        .put("s", e.src)
                        .put("t", e.tgt)
                        .put("l", e.lang)
                        .put("ts", e.ts),
                )
            }
            json.put("pairs", jsonPairs)
            file.writeText(json.toString())
            dirty = false
        } catch (ex: Exception) {
            com.aliucord.Utils.log("TranslateAll cache save failed: ${ex.message}")
        }
    }
}