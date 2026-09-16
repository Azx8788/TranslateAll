# TranslateAll

[Aliucord](https://github.com/Aliucord/Aliucord) 插件：实时自动翻译所有消息。

## 功能

- **实时自动翻译**：新消息若不是目标语言（默认简体中文），出现即翻译，译文以小字追加在原文下方
- **长按翻译**：长按消息菜单中的「翻译为 XX」按钮，手动翻译单条消息
- **顶部翻译按钮**：检测到频道大部分消息不是目标语言时，频道顶部出现「翻译」按钮，一键开启本频道翻译模式（再点关闭）
- **上下文 AI 翻译**：AI 引擎可携带最近消息的原文+译文作为上下文，保持术语一致
- **永久翻译缓存**：翻译过的文本保存在本地（`/Aliucord/TranslateAll/cache.json`），相同文本不再重复请求，省 token
- **编辑重翻**：消息被编辑后自动重新翻译
- **三引擎**：Google（免费免 Key）/ DeepL（需 Key）/ AI（OpenAI 兼容：DeepSeek 等）
- **频道黑名单**：不翻译指定频道
- **命令**：`/translate [text] [to] [send]`，无参时翻译本频道最近一条非目标语言消息

## 安装

1. 下载 `TranslateAll.zip`
2. 放入手机 `/storage/emulated/0/Aliucord/plugins/`
3. 打开 Aliucord → 设置 → 插件 → 启用 TranslateAll
4. 点插件右侧齿轮进入设置页，配置目标语言 / 引擎

## 用法建议

- **只想要 Telegram 式体验**：默认配置即可——把「自动翻译」关着，打开「顶部翻译按钮」，进外文频道时点顶部的「翻译」按钮
- **想要全自动**：在设置里打开「自动翻译」（注意 AI 引擎会消耗 token，翻译缓存可减少重复请求）
- **用 AI 引擎**：设置 → 翻译 → 选择「AI」，在「AI 翻译」里填 API URL / 模型 / Key，点「保存文本框设置」

## 构建

- 基于官方模板，GitHub Actions 自动构建（push 非 main 分支出 zip）
- `./gradlew :TranslateAll:make` 手动构建

## 说明

- 插件作者 Azx8788
- 目标 Discord 版本 126.21（126021）
- 与 Discord ToS 相关风险请自行评估，建议小号测试