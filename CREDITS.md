# 来源与致谢（CREDITS）

> 这个仓库**完全开源**（MIT），也**完全自己写**。下面把「自己写的」和「指向上游的」分清楚，
> 免得共创的同学误以为哪段代码是从别的项目搬来的。

## 一、本仓库的代码

以下文件全部由本项目从零编写，**未复制任何第三方项目的源代码**：

| 文件 | 说明 |
|---|---|
| `core/PasteAccessibilityService.kt` | 悬浮球 / 展开面板 / 焦点定位 / 三级插入兜底 |
| `ui/MainActivity.kt` | 权限状态、提示词管理、导入导出 |
| `data/PromptRepository.kt` | SharedPreferences + org.json 持久化 |
| `PromptApp.kt` | Application 单例 |
| `res/**` 全部布局与 drawable | 原生 View / XML |
| `docs/preview/logo.png`、`ui_review_*.png` | 用 Pillow 脚本绘制的 LOGO 与 UI 预览 |
| `.github/workflows/*` | 自建 CI |

**依赖面为零**：`dependencies { }` 是空的。没有 AndroidX、没有 Compose、没有 Room、
没有 EasyFloat、没有 Retrofit、没有任何网络库。所有能力都来自 Android 框架自带 API
（`WindowManager`、`AccessibilityService`、`AccessibilityNodeInfo`、`ClipboardManager`、
`SharedPreferences`、`org.json`）。

因此本仓库采用 **MIT** 协议，不受任何上游 copyleft 协议传染；也正因如此，欢迎任意 fork、
改、商用、再分发，只要保留版权声明。

## 二、借鉴了思路、并指向上游的项目

这些项目**只提供了设计参考和 API 用法参考**，没有一行代码进入本仓库。按用户要求，
链接全部指回上游，方便后来者去看原始实现：

| 上游 | 借了什么 | 本项目的处理 |
|---|---|---|
| [J-Pster/pster-voice](https://github.com/J-Pster/pster-voice) | 无障碍服务用 `TYPE_ACCESSIBILITY_OVERLAY` 自己画悬浮球；`ACTION_PASTE` 优先、`ACTION_SET_TEXT` 兜底的链路 | 只借 API 组合思路；它是语音转写工具，我们是提示词列表，UI 与数据流完全不同 |
| [RMBLR（DEV 长文）](https://dev.to/pastdaking/how-i-built-a-floating-dictation-orb-on-android-and-why-the-accessibility-service-is-the-whole-app-1gbd) | `FOCUS_INPUT` 与 `FOCUS_ACCESSIBILITY` 的区别、`FLAG_NOT_FOCUSABLE` 的必要性、PASTE 优先的理由、`node.recycle()` | 同样只借结论，代码独立实现 |
| [AmanRawat2001/FloatingClipboard](https://github.com/AmanRawat2001/FloatingClipboard) | 「悬浮服务画球 + 主 App 管条目 + 本地持久化」的分层 | **没照抄**它「只复制到剪贴板、还要用户自己粘」那一步；本项目直接写入输入框 |
| [Divyanshverma2804/UtilityBox](https://github.com/Divyanshverma2804/UtilityBox) | 无障碍 + overlay 的工程组织方式 | 仅参考结构 |
| [EasyFloat](https://github.com/princekin-f/EasyFloat) | 「可拖拽 + 松手贴边」的交互手感 | 未引入依赖，用 `OnTouchListener` 自己写了几十行 |
| [PRosenb/AdbClipboard](https://github.com/PRosenb/AdbClipboard) | 悬浮球贴边 / 隐藏 / 恢复的交互语义 | 同上，未引入依赖 |
| [Android 官方文档：AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) / [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo) | API 语义与常量定义 | 一手依据 |

> 另外，公开介绍里提到的 `jev-chat/jev-chat-jarvis` 用 `ACTION_SET_TEXT` 回填草稿、
> 失败退回剪贴板，也验证了三级兜底这个方向是通的；同样**未使用其代码**。

## 三、为什么没有直接用上面任何一个

用户的核心诉求是「**不换输入法，只干粘贴提示词这一件事**」：

- Kboard / Fossify Keyboard / HeliBoard / FlorisBoard / Pastille 都是**输入法或类输入法**，
  要么要切换键盘，要么功能太多；
- Pster Voice / RMBLR 是**语音转写**，FloatingClipboard 只复制到剪贴板还要手动粘；
- 它们都不满足「悬浮球点一下直接进当前输入框 + 主页面管条目 + 中文界面」这一组合。

所以本项目选择自己写，但把上游的**正确做法**留在这里，方便共创时对照。

## 四、想共创？

见 [CONTRIBUTING.md](CONTRIBUTING.md)。特别欢迎：

- 更多输入框类型的兼容性实测（Flutter / WebView / 各家 AI App）
- `{选中文本}` 之类的变量占位符
- 分组 / 多行面板、SAF 文件导入导出
- 更聪明的键盘弹出时避让逻辑
