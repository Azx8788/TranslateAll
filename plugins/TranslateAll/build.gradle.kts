version = "1.0.0" // Plugin version. Increment this to trigger an update
description = "实时自动翻译所有消息为指定语言，支持 Google/DeepL/AI 引擎与智能翻译缓存。Real-time auto translation for all messages with Google/DeepL/AI engines and smart caching."

aliucord {
    // Changelog of your plugin
    changelog.set(
        """
        # 1.0.0
        * 首次发布：实时自动翻译（非目标语言消息出现即翻译）
        * 长按消息「翻译为 XX」菜单按钮
        * 聊天顶部智能「翻译」按钮（频道大部分消息非目标语言时显示）
        * Google / DeepL / AI（OpenAI 兼容）三引擎
        * AI 上下文翻译、永久翻译缓存、编辑重翻、频道黑名单
        """.trimIndent(),
    )
    // Image or Gif that will be shown at the top of your changelog page
    // changelogMedia.set("https://cool.png")

    // Add additional authors to this plugin
    // author("Name", 0L, hyperlink = true)

    // Excludes this plugin from publishing and global plugin repositories.
    // Set this to false if the plugin is unfinished
    deploy.set(false)
}