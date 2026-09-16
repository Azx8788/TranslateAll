package com.azx8788.translateall

import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.views.Button
import com.aliucord.views.Divider
import com.aliucord.views.TextInput
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager

/**
 * 设置页（仿 Telegram 翻译插件风格）。
 * 分组：选项 / 翻译 / AI 翻译 / 频道与缓存
 */
class TranslateAllSettings(private val settings: SettingsAPI) : SettingsPage() {

    private val textInputs = mutableListOf<Pair<TextInput, String>>()
    private val deeplKeyViews = mutableListOf<View>()
    private val aiViews = mutableListOf<View>()

    override fun onViewBound(view: View?) {
        super.onViewBound(view)
        setActionBarTitle("TranslateAll 翻译设置")
        val ctx = requireContext()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        fun sectionTitle(title: String) = TextView(ctx).apply {
            text = title
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#8E9BAB"))
            setPadding(dp(16), dp(14), 0, dp(4))
        }

        fun section(title: String) {
            Divider(ctx).also { addView(it) }
            addView(sectionTitle(title))
            Divider(ctx).also { addView(it) }
        }

        fun addSwitch(title: String, subtext: String?, key: String, default: Boolean): View =
            Utils.createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                title,
                subtext,
            ).apply {
                isChecked = settings.getBool(key, default)
                setOnCheckedListener { settings.setBool(key, it) }
                addView(this)
            }

        fun addTextInput(
            hint: String,
            key: String,
            defaultValue: String,
            multiline: Boolean = false,
        ): TextInput = TextInput(ctx, hint).apply {
            editText.setText(settings.getString(key, defaultValue))
            editText.setSingleLine(!multiline)
            if (multiline) {
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editText.minLines = 3
            }
            textInputs.add(this to key)
            addView(this)
        }

        fun rowText(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { onClick() }
            addView(this)
        }

        // ================= 选项 =================
        section("选项")
        addSwitch(
            "自动翻译",
            "新消息出现时，若不是目标语言则立即翻译（开启后无需手动操作）",
            "autoTranslate",
            false,
        )
        addSwitch(
            "显示「翻译」按钮",
            "在长按消息的菜单中显示「翻译为目标语言」",
            "menuButton",
            true,
        )
        addSwitch(
            "顶部翻译按钮",
            "频道大部分消息不是目标语言时，顶部显示「翻译」按钮，一键开启本频道翻译",
            "topBarButton",
            true,
        )
        addSwitch("追加原文", "在译文后附带原文片段，方便对照", "appendOriginal", false)
        addSwitch("编辑后重翻", "消息被编辑后自动重新翻译", "retranslateOnEdit", true)

        // ================= 翻译 =================
        section("翻译")
        rowText("目标语言：${Lang.displayName(settings.getString("targetLang", "zh-CN"))}") {
            val names = Lang.SUPPORTED_LANGUAGES.keys.toTypedArray()
            val codes = Lang.SUPPORTED_LANGUAGES.values.toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle("选择目标语言")
                .setItems(names) { _, i ->
                    settings.setString("targetLang", codes[i])
                    showToastMsg("目标语言已设为：${names[i]}")
                    close()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 引擎单选组
        val engines = listOf(
            "Google（免费，无需 Key）" to "google",
            "DeepL（需要 API Key）" to "deepl",
            "AI（OpenAI 兼容，支持上下文）" to "ai",
        )
        val radios = engines.map { (title, _) ->
            Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.RADIO, title, null)
        }
        val radioManager = RadioManager(radios)
        val currentEngine = settings.getString("engine", "google")
        val radioGroup = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        radios.forEachIndexed { i, radio ->
            radio.setOnCheckedListener {
                settings.setString("engine", engines[i].second)
                radioManager.a(radio)
                updateDynamicSections(engines[i].second)
            }
            radioGroup.addView(radio)
            if (engines[i].second == currentEngine) radioManager.a(radio)
        }
        addView(radioGroup)

        addTextInput("DeepL API Key（仅 DeepL 引擎需要）", "deeplKey", "")
            .also { deeplKeyViews.add(it) }

        // ================= AI 翻译 =================
        val aiSectionHeader = sectionTitle("AI 翻译（OpenAI 兼容接口）")
        aiViews.add(aiSectionHeader)
        aiViews.add(Divider(ctx).also { addView(it) })
        addView(aiSectionHeader)

        addTextInput("API URL", "aiUrl", "https://api.deepseek.com/chat/completions")
            .also { aiViews.add(it) }
        addTextInput("模型名", "aiModel", "deepseek-chat")
            .also { aiViews.add(it) }
        addTextInput("API Key", "aiKey", "")
            .also { aiViews.add(it) }
        addTextInput(
            "系统提示词",
            "aiSystemPrompt",
            "你是一名专业的翻译引擎。请只输出译文本身，不要添加任何解释、引号或附加文字。保持原文的换行与格式，语气自然。",
            multiline = true,
        ).also { aiViews.add(it) }
        addTextInput(
            "用户提示词（{target}=目标语言，{text}=待翻译文本）",
            "aiUserPrompt",
            "请将以下文本翻译成{target}：\n\n{text}",
            multiline = true,
        ).also { aiViews.add(it) }

        aiViews.add(
            addSwitch(
                "使用上下文",
                "翻译时携带最近消息的原文与译文，保持术语一致",
                "aiUseContext",
                true,
            ),
        )

        // 上下文消息数
        var ctxCountHolder: TextView? = null
        val ctxCountView = rowText("上下文消息数：${settings.getInt("aiContextCount", 5)} 条") {
            val edit = android.widget.EditText(ctx).apply {
                setText(settings.getInt("aiContextCount", 5).toString())
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            AlertDialog.Builder(ctx)
                .setTitle("上下文消息数（1-20）")
                .setView(edit)
                .setPositiveButton("保存") { _, _ ->
                    val n = (edit.text.toString().toIntOrNull() ?: 5).coerceIn(1, 20)
                    settings.setInt("aiContextCount", n)
                    ctxCountHolder?.text = "上下文消息数：$n 条"
                }
                .setNegativeButton("取消", null)
                .show()
        }
        ctxCountHolder = ctxCountView
        aiViews.add(ctxCountView)

        // 温度 SeekBar
        val tempVal = settings.getFloat("aiTemperature", 0.7f)
        val tempLabel = TextView(ctx).apply {
            text = "严谨与想象（temperature）：${"%.1f".format(tempVal)}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(16), dp(14), dp(16), 0)
        }
        aiViews.add(tempLabel)
        addView(tempLabel)
        val seekBar = SeekBar(ctx).apply {
            max = 20
            progress = (tempVal * 10).toInt()
            setPadding(dp(16), 0, dp(16), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = progress / 10f
                    settings.setFloat("aiTemperature", v)
                    tempLabel.text = "严谨与想象（temperature）：${"%.1f".format(v)}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) { }
                override fun onStopTrackingTouch(seekBar: SeekBar) { }
            })
        }
        aiViews.add(seekBar)
        addView(seekBar)

        // ================= 保存文本设置 =================
        section("保存")
        addView(Button(ctx).apply {
            text = "保存文本框设置（Key / API / 提示词等）"
            setOnClickListener {
                for ((input, key) in textInputs) {
                    settings.setString(key, input.editText.text.toString())
                }
                showToastMsg("已保存")
                close()
            }
        })

        // ================= 频道与缓存 =================
        section("频道与缓存")
        addView(Button(ctx).apply {
            text = "把当前频道加入黑名单（不翻译）"
            setOnClickListener {
                val ch = TranslateAll.instance?.currentChannelId ?: -1L
                if (ch == -1L) {
                    showToastMsg("请先打开一个频道")
                    return@setOnClickListener
                }
                val set = TranslateAll.parseBlacklist(settings.getString("blacklist", "")).toMutableSet()
                if (set.contains(ch)) {
                    set.remove(ch)
                    showToastMsg("已把当前频道移出黑名单")
                } else {
                    set.add(ch)
                    showToastMsg("已把当前频道加入黑名单")
                }
                settings.setString("blacklist", set.joinToString(","))
                close()
            }
        })
        addView(Button(ctx).apply {
            val n = TranslateAll.parseBlacklist(settings.getString("blacklist", "")).size
            text = "清空黑名单（当前 $n 个频道）"
            setOnClickListener {
                settings.setString("blacklist", "")
                showToastMsg("黑名单已清空")
                close()
            }
        })
        addView(Button(ctx).apply {
            text = "清空翻译缓存（当前 ${TranslateAll.instance?.cacheSize ?: 0} 条）"
            setOnClickListener {
                TranslateAll.instance?.clearCache()
                showToastMsg("翻译缓存已清空")
                close()
            }
        })

        // 初始可见性
        updateDynamicSections(currentEngine)
    }

    private fun updateDynamicSections(engine: String) {
        val aiVisible = engine == "ai"
        val deeplVisible = engine == "deepl"
        for (v in aiViews) v.visibility = if (aiVisible) View.VISIBLE else View.GONE
        for (v in deeplKeyViews) v.visibility = if (deeplVisible) View.VISIBLE else View.GONE
    }

    private fun showToastMsg(msg: String) = Utils.showToast(msg)
}