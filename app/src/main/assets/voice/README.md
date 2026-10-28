# 内置语音插件

这两个脚本来自 [SandyYuR/fcitx5-android](https://github.com/SandyYuR/fcitx5-android)
的 `app/src/main/assets/bundled/js-voice/`（2026-10-28 的 `cad226c`），随芝版内置：

- `qwen-asr.js` — 通义千问 / DashScope，Foxy 插件版本 1.6.2
- `doubao-asr.js` — 豆包 / 火山引擎 bigmodel，Foxy 插件版本 1.1.0

它们实现 Foxy 的 `voice.asr` 契约，由 `com.osfans.trime.data.voice` 里的 QuickJS 宿主加载。
原仓库以 LGPL-2.1-or-later 发布；芝版整体是 GPL-3.0-or-later，保留此来源说明。

插件只允许连接脚本自己声明的域名，密钥不会进入 JS 上下文。
