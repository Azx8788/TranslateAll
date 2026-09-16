package com.azx8788.translateall

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.aliucord.CollectionUtils
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.discord.api.commands.ApplicationCommandType
import com.discord.databinding.WidgetHomeBinding
import com.discord.databinding.WidgetChatListActionsBinding
import com.discord.models.message.Message
import com.discord.utilities.textprocessing.node.EditedMessageNode
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.home.WidgetHomeHeaderManager
import com.discord.widgets.home.WidgetHomeModel
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private data class TranslationEntry(val src: String, val tgt: String, val lang: String)

private class ChannelStats {
    var total = 0
    var foreign = 0
    val seen = ArrayDeque<Long>()
}

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class TranslateAll : Plugin() {

    companion object {
        const val NAME = "TranslateAll"

        /** 单例引用，设置页等地方要用 */
        @JvmStatic
        var instance: TranslateAll? = null

        private val menuButtonId = View.generateViewId()
        private val topBarId = View.generateViewId()

        fun parseBlacklist(s: String): Set<Long> =
            s.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    // ---------- 状态 ----------
    /** messageId -> 已翻译条目（仅内存） */
    private val translations = ConcurrentHashMap<Long, TranslationEntry>()
    /** 正在翻译的请求去重 */
    private val queue = ConcurrentHashMap.newKeySet<String>()
    /** 顶部按钮开启翻译模式的频道（会话级） */
    private val channelModes = ConcurrentHashMap.newKeySet<Long>()
    /** 每频道语言统计（顶部按钮判定用） */
    private val channelStats = ConcurrentHashMap<Long, ChannelStats>()
    /** AI 上下文：每频道最近已翻译消息（原文, 译文），最多 20 条 */
    private val channelHistory = ConcurrentHashMap<Long, ArrayDeque<Pair<String, String>>>()
    /** 每频道最近渲染的消息（兜底命令 / 顶部按钮批量翻译用），最多 50 条 */
    private val recentMessages = ConcurrentHashMap<Long, LinkedHashMap<Long, Message>>()

    private var chatListWidget: WidgetChatList? = null
    private var topBar: TextView? = null

    @Volatile
    var currentChannelId: Long = -1L
        private set

    lateinit var pluginIcon: Drawable
        private set

    private val cache = TranslationCache()
    val cacheSize: Int get() = cache.size

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cacheSaveScheduled = AtomicBoolean(false)

    private val mDraweeStringBuilder =
        SimpleDraweeSpanTextView::class.java.getDeclaredField("mDraweeSpanStringBuilder")
            .apply { isAccessible = true }

    init {
        instance = this
        settingsTab = SettingsTab(TranslateAllSettings::class.java).withArgs(settings)
    }

    // ---------- 配置读取 ----------
    private fun targetLang() = settings.getString("targetLang", "zh-CN")
    private fun autoOn() = settings.getBool("autoTranslate", false)
    private fun topBarOn() = settings.getBool("topBarButton", true)
    private fun menuOn() = settings.getBool("menuButton", true)
    private fun retranslateOnEdit() = settings.getBool("retranslateOnEdit", true)
    private fun appendOriginal() = settings.getBool("appendOriginal", false)
    private fun engine() = settings.getString("engine", "google")
    private fun isBlacklisted(ch: Long) = ch in parseBlacklist(settings.getString("blacklist", ""))
    private fun shouldAuto(ch: Long): Boolean = !isBlacklisted(ch) && (autoOn() || ch in channelModes)

    // ---------- 生命周期 ----------
    override fun load(context: Context) {
        pluginIcon = ContextCompat.getDrawable(context, R.e.ic_locale_24dp)!!
        Utils.threadPool.execute { cache.load() }
    }

    override fun start(context: Context) {
        runCatching { patchChatListReference() }
            .onFailure { Utils.log("TranslateAll patchChatListReference failed: $it") }
        runCatching { patchTopBar() }
            .onFailure { Utils.log("TranslateAll patchTopBar failed: $it") }
        runCatching { patchMessageList() }
            .onFailure { Utils.log("TranslateAll patchMessageList failed: $it") }
        runCatching { patchProcessText() }
            .onFailure { Utils.log("TranslateAll patchProcessText failed: $it") }
        runCatching { patchContextMenu() }
            .onFailure { Utils.log("TranslateAll patchContextMenu failed: $it") }
        runCatching { registerCommands() }
            .onFailure { Utils.log("TranslateAll registerCommands failed: $it") }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        runCatching { cache.save() }
        translations.clear()
        queue.clear()
        channelModes.clear()
        channelStats.clear()
        channelHistory.clear()
        recentMessages.clear()
    }

    // ================= Patch 1: 聊天列表实例（消息重渲染要用） =================
    private fun patchChatListReference() {
        patcher.after<WidgetChatList> {
            chatListWidget = it.thisObject as WidgetChatList
        }
    }

    // ================= Patch 2: 频道顶部「翻译」按钮 =================
    private fun patchTopBar() {
        patcher.after<WidgetHomeHeaderManager>(
            "configure",
            WidgetHome::class.java,
            WidgetHomeModel::class.java,
            WidgetHomeBinding::class.java,
        ) { p ->
            val home = p.args[0] as WidgetHome
            val toolbar = home.toolbar
            val root = toolbar.parent as? ViewGroup ?: return@after
            val ctx = toolbar.context

            // 移除旧按钮（同一 view id 只保留一个）
            root.findViewById<View>(topBarId)?.let(root::removeView)
            topBar = null

            val dp = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }
            val bar = TextView(ctx).apply {
                id = topBarId
                text = "翻译"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor("#5865F2"))
                }
                setPadding(dp(16), dp(6), dp(16), dp(6))
                visibility = View.GONE
                setOnClickListener { toggleChannelTranslate() }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
                bottomMargin = dp(2)
            }
            root.addView(bar, root.indexOfChild(toolbar) + 1, lp)
            topBar = bar
            updateTopBar()
        }
    }

    /** 顶部按钮可见性：频道大部分消息不是目标语言时出现 */
    private fun topBarVisibleFor(ch: Long): Boolean {
        if (!topBarOn()) return false
        if (ch in channelModes) return true // 模式已开，显示以便关闭
        if (autoOn()) return false // 全局自动翻译已开，不需要按钮
        val st = channelStats[ch] ?: return false
        return st.total >= 4 && st.total > 0 && st.foreign.toFloat() / st.total >= 0.5f
    }

    private fun updateTopBar() {
        val bar = topBar ?: return
        val ch = currentChannelId
        val visible = ch != -1L && topBarVisibleFor(ch)
        bar.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            bar.text = if (ch in channelModes) "已开启翻译 · 点击关闭" else "翻译"
        }
    }

    private fun toggleChannelTranslate() {
        val ch = currentChannelId
        if (ch == -1L) return
        if (ch in channelModes) {
            channelModes.remove(ch)
            Utils.showToast("已关闭本频道翻译")
        } else {
            channelModes.add(ch)
            Utils.showToast("已开启本频道翻译")
            // 把当前频道已渲染的消息全部补翻
            recentMessages[ch]?.values?.forEach { requestTranslate(it) }
        }
        updateTopBar()
        rerenderAll()
    }

    // ================= Patch 3: 消息渲染 —— 检测语言并触发翻译 =================
    private fun patchMessageList() {
        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChatListEntry::class.java,
        ) { p ->
            val message = (p.args[1] as? MessageEntry)?.message ?: return@after
            if (message.isLoading) return@after

            val ch = message.channelId
            currentChannelId = ch

            // 语言统计（同一条消息只计一次）
            val st = channelStats.getOrPut(ch) { ChannelStats() }
            if (!st.seen.contains(message.id)) {
                st.seen.addLast(message.id)
                while (st.seen.size > 30) st.seen.removeFirst()
                st.total++
                val content = message.content
                if (content.isNullOrBlank() || !Lang.isAlreadyTarget(content, targetLang())) {
                    st.foreign++
                }
            }

            // 记录频道最近消息
            val recent = recentMessages.getOrPut(ch) { LinkedHashMap() }
            recent[message.id] = message
            while (recent.size > 50) {
                val k = recent.keys.firstOrNull() ?: break
                recent.remove(k)
            }

            updateTopBar()

            if (shouldAuto(ch)) requestTranslate(message)
        }
    }

    // ================= Patch 4: 译文渲染 =================
    private fun patchProcessText() {
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "processMessageText",
            arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java),
            Hook { p ->
                val message = (p.args[1] as MessageEntry).message ?: return@Hook
                val t = translations[message.id] ?: return@Hook
                val text = message.content ?: return@Hook

                // 设置里换了目标语言 -> 旧的译作废重翻
                if (t.lang != targetLang()) {
                    translations.remove(message.id)
                    if (shouldAuto(message.channelId)) requestTranslate(message)
                    return@Hook
                }
                // 消息被编辑 -> 重翻
                if (t.src != text) {
                    translations.remove(message.id)
                    if (retranslateOnEdit() && shouldAuto(message.channelId)) {
                        requestTranslate(message)
                    }
                    return@Hook
                }

                val textView = p.args[0] as SimpleDraweeSpanTextView
                val builder = mDraweeStringBuilder.get(textView) as DraweeSpanStringBuilder?
                    ?: return@Hook

                builder.append("\n")
                var i0 = builder.length
                builder.append(t.tgt)
                builder.setSpan(RelativeSizeSpan(0.87f), i0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(
                    EditedMessageNode.Companion.`access$getForegroundColorSpan`(
                        EditedMessageNode.Companion,
                        textView.context,
                    ),
                    i0,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )

                if (appendOriginal()) {
                    val suffix = "  [原文: ${t.src.take(160)}]"
                    i0 = builder.length
                    builder.append(suffix)
                    builder.setSpan(RelativeSizeSpan(0.8f), i0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(
                        EditedMessageNode.Companion.`access$getForegroundColorSpan`(
                            EditedMessageNode.Companion,
                            textView.context,
                        ),
                        i0,
                        builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }

                textView.setDraweeSpanStringBuilder(builder)
            },
        )
    }

    // ================= Patch 5: 长按消息菜单「翻译为 XX」 =================
    private fun patchContextMenu() {
        val actionsClass = WidgetChatListActions::class.java
        val getBinding = actionsClass.getDeclaredMethod("getBinding").apply { isAccessible = true }

        // 在 configureUI 时绑定点击（能拿到 message 模型）
        patcher.patch(
            actionsClass,
            "configureUI",
            arrayOf(WidgetChatListActions.Model::class.java),
            Hook { p ->
                if (!menuOn()) return@Hook
                val menu = p.thisObject as WidgetChatListActions
                val model = p.args[0] as WidgetChatListActions.Model
                val message = model.message ?: return@Hook

                val binding = getBinding.invoke(menu) as WidgetChatListActionsBinding
                binding.root.findViewById<TextView>(menuButtonId)?.setOnClickListener {
                    Utils.threadPool.execute { translateNow(message) }
                    menu.dismiss()
                }
            },
        )

        // 塞入菜单项视图
        patcher.patch(
            actionsClass,
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { p ->
                if (!menuOn()) return@Hook
                val scroll = p.args[0] as NestedScrollView
                val linearLayout = scroll.getChildAt(0) as? LinearLayout ?: return@Hook
                val ctx = linearLayout.context
                val messageId =
                    WidgetChatListActions.`access$getMessageId$p`(p.thisObject as WidgetChatListActions)

                linearLayout.addView(
                    TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        id = menuButtonId
                        text = if (translations.containsKey(messageId)) {
                            "重新翻译"
                        } else {
                            "翻译为${Lang.displayName(targetLang())}"
                        }
                        setCompoundDrawablesRelativeWithIntrinsicBounds(pluginIcon, null, null, null)
                    },
                )
            },
        )
    }

    // ================= 命令 =================
    private fun registerCommands() {
        commands.registerCommand(
            "translate",
            "翻译文本，或翻译本频道最近一条非目标语言消息",
            listOf(
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "text",
                    "要翻译的文本（留空则翻译最近一条消息）",
                ),
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "to",
                    "目标语言代码（如 zh-CN / en / ja，默认用插件设置）",
                ),
                Utils.createCommandOption(
                    ApplicationCommandType.BOOLEAN,
                    "send",
                    "是否把译文发送到聊天中（默认 false，只显示给你）",
                ),
            ),
        ) { ctx ->
            val target = ctx.getStringOrDefault("to", targetLang()).ifBlank { targetLang() }
            val requested = ctx.getStringOrDefault("text", "")

            val srcText = when {
                requested.isNotBlank() -> requested
                else -> {
                    val recent = recentMessages[currentChannelId]
                    recent?.values?.toList()?.asReversed()?.firstOrNull { m ->
                        val c = m.content
                        !c.isNullOrBlank() && !Lang.isAlreadyTarget(c, target)
                    }?.content
                }
            }
            if (srcText.isNullOrBlank()) {
                return@registerCommand CommandsAPI.CommandResult(
                    "没有找到需要翻译的消息，请使用 /translate text 指定文本",
                    null,
                    false,
                )
            }

            when (val outcome = Translators.translate(engine(), srcText, target, emptyList(), settings)) {
                is TranslateOutcome.Success -> {
                    cache.put(srcText, outcome.text, target)
                    scheduleCacheSave()
                    CommandsAPI.CommandResult(
                        outcome.text,
                        null,
                        ctx.getBoolOrDefault("send", false),
                    )
                }
                is TranslateOutcome.Failure -> CommandsAPI.CommandResult(outcome.message, null, false)
            }
        }
    }

    // ================= 翻译调度 =================

    /** 手动翻译（长按菜单），错误用 Toast 提示 */
    private fun translateNow(message: Message) {
        val text = message.content ?: return
        if (text.isBlank()) return
        val target = targetLang()
        if (Lang.isAlreadyTarget(text, target)) {
            Utils.showToast("这条消息已是${Lang.displayName(target)}，无需翻译", true)
            return
        }

        val id = message.id
        val ch = message.channelId

        // 命中磁盘缓存 -> 秒回
        cache.get(text, target)?.let { e ->
            translations[id] = TranslationEntry(text, e.tgt, target)
            rerenderMessage(id)
            return
        }

        val key = "force:$ch:$id"
        if (!queue.add(key)) return
        try {
            when (val outcome =
                Translators.translate(engine(), text, target, contextFor(ch), settings)) {
                is TranslateOutcome.Success -> {
                    cache.put(text, outcome.text, target)
                    translations[id] = TranslationEntry(text, outcome.text, target)
                    addHistory(ch, text, outcome.text)
                    rerenderMessage(id)
                    scheduleCacheSave()
                }
                is TranslateOutcome.Failure -> Utils.showToast("翻译失败：${outcome.message}", true)
            }
        } finally {
            queue.remove(key)
        }
    }

    /** 自动翻译（静默失败，只写日志） */
    private fun requestTranslate(message: Message) {
        val text = message.content ?: return
        if (text.isBlank()) return
        val target = targetLang()
        if (Lang.isAlreadyTarget(text, target)) return

        val id = message.id
        val ch = message.channelId

        val existing = translations[id]
        if (existing != null && existing.src == text && existing.lang == target) return

        val key = "$ch:$id"
        if (!queue.add(key)) return

        Utils.threadPool.execute {
            try {
                val again = translations[id]
                if (again != null && again.src == text && again.lang == target) return@execute

                cache.get(text, target)?.let { e ->
                    translations[id] = TranslationEntry(text, e.tgt, target)
                    rerenderMessage(id)
                    return@execute
                }

                when (val outcome =
                    Translators.translate(engine(), text, target, contextFor(ch), settings)) {
                    is TranslateOutcome.Success -> {
                        cache.put(text, outcome.text, target)
                        translations[id] = TranslationEntry(text, outcome.text, target)
                        addHistory(ch, text, outcome.text)
                        rerenderMessage(id)
                        scheduleCacheSave()
                    }
                    is TranslateOutcome.Failure -> {
                        Utils.log("TranslateAll: ${outcome.message}")
                    }
                }
            } finally {
                queue.remove(key)
            }
        }
    }

    // ================= 工具 =================

    /** AI 上下文：该频道最近已翻译消息 */
    private fun contextFor(ch: Long): List<Pair<String, String>> {
        val dq = channelHistory[ch] ?: return emptyList()
        synchronized(dq) { return dq.toList() }
    }

    private fun addHistory(ch: Long, src: String, tgt: String) {
        val dq = channelHistory.getOrPut(ch) { ArrayDeque() }
        synchronized(dq) {
            if (dq.isNotEmpty() && dq.last.first == src) dq.removeLast()
            dq.addLast(src to tgt)
            while (dq.size > 20) dq.removeFirst()
        }
    }

    /** 单条消息重渲染（主线程） */
    private fun rerenderMessage(messageId: Long) {
        val widget = chatListWidget ?: return
        mainHandler.post {
            try {
                val adapter = WidgetChatList.`access$getAdapter$p`(widget)
                var idx = -1
                try {
                    val f = adapter.javaClass.getDeclaredField("internalData").apply { isAccessible = true }
                    val data = f.get(adapter) as List<*>
                    idx = CollectionUtils.findIndex(data) {
                        it is MessageEntry && it.message.id == messageId
                    }
                } catch (_: Exception) { }
                if (idx != -1) adapter.notifyItemChanged(idx) else adapter.notifyDataSetChanged()
            } catch (ex: Exception) {
                Utils.log("TranslateAll rerender failed: ${ex.message}")
            }
        }
    }

    /** 全量刷新当前列表 */
    private fun rerenderAll() {
        val widget = chatListWidget ?: return
        mainHandler.post {
            try {
                WidgetChatList.`access$getAdapter$p`(widget)?.notifyDataSetChanged()
            } catch (ex: Exception) {
                Utils.log("TranslateAll rerenderAll failed: ${ex.message}")
            }
        }
    }

    private fun scheduleCacheSave() {
        if (!cacheSaveScheduled.compareAndSet(false, true)) return
        Utils.threadPool.execute {
            Thread.sleep(5000)
            cacheSaveScheduled.set(false)
            runCatching { cache.save() }
        }
    }

    fun clearCache() {
        cache.clear()
        translations.clear()
        rerenderAll()
    }

    fun refreshAllVisible() {
        rerenderAll()
    }

    fun isChannelTranslated(ch: Long) = ch in channelModes
}