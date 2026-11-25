# 更新日志

版本号遵循语义化版本；**每次出包升一个小版本**（patch +1），约定见
[project/版本与发布.md](project/版本与发布.md)。

## v0.1.3（2026-11-25）

**球 ↔ 框重做（真机截图反馈，按设计判断改过三处）**

- **球长大成框，不再球框并存**：同一个 `FrameLayout` 容器用 `ValueAnimator` 逐帧插值
  宽 / 高 / 位置 / `GradientDrawable` 圆角，球文字淡出、chips 淡入；展开时球 View 立刻
  `INVISIBLE`，收起反向缩回球。
- **时长 220ms（收起 180ms）**，不是用户提的「1 秒左右」——瞬时操作拖到 1 秒只会觉得卡。
- **删掉全部装饰**：标题「预设提示词」、蓝色竖条、`收起 ✕`、底部「点一条即插入 · 左右滑动看更多」。
  面板里只剩一排 chip。「可横滑」改由 `requiresFadingEdge` 右侧渐隐 + 被裁半颗的 chip 暗示。
- **✕ 删了但补上收起方式**：点框内任意空白处缩回球、点某条即插入并收起。
  **刻意不做「点框外收起」** —— 那需要全屏触摸层 / `FLAG_WATCH_OUTSIDE_TOUCH`，会吃掉用户
  点输入框的那一下，跟「不跟输入法抢」冲突。浮窗现在显式加 `FLAG_NOT_TOUCH_MODAL`，
  框外触摸穿透给输入框与键盘。
- **长按 chip → 就地预览全文**（不是弹窗）：同一个框长大成可上下滚动的预览态，
  点全文即插入、点空白即收起。弹窗要抢焦点或额外权限，跟核心冲突，故不采纳。
  `ScrollView` 给有界高度，长提示词在框内滚动而不是把框撑满屏。
- **宽度按内容自适应**：用 `Paint.measureText` 估算 chips 总宽，一条时是刚好的小框，
  多条才变宽，clamp 到屏宽-32dp；不再固定满屏宽留一大片空白。
- **主动发现并修**：主页面顶部和底部各有一个 `⋯`，删掉顶部，工具菜单统一走底部一个入口。
- `computePanelY()` 改用实际目标高度（含预览态），不再写死 150dp。
- 动画回调加身份判断，防止 `ValueAnimator.cancel()` 触发旧 `onEnd` 导致重复移除 / 递归。

**日志**
- 新增 `PANEL morph expand` / `morph collapse(reason=…)` / `preview open` / `长按第 N 条` 埋点，
  reason 含 `chip-tap` / `tap-blank` / `preview-tap` / `settings-refresh`，贴日志可验证交互路径。

**工程**
- `versionCode` 3 → 4，`versionName` 0.1.2 → 0.1.3；签名与包名不变 → **v0.1.2 可直接覆盖装**
- 删除不再引用的 `bg_ball_active.xml`；`docs/preview/ui_review_v4.png` 四态预览

## v0.1.2（2026-11-25）

**安装包命名（用户反馈：下载器里 `app-debug.apk` 太多，分不清谁是谁）**
- 命名规则改为 **软件名 + v版本号 + `.apk`** → `Citie-v0.1.2.apk`
- ⚠️ 实测：先试了 `词贴-v0.1.2.apk`，**GitHub Release 的 asset 文件名不支持中文**，
  上传时非 ASCII 前缀被吞、只剩 `-v0.1.2.apk`。故文件名用「词贴」的拉丁写法 `Citie`，
  中文名放 asset 的 `label`（页面显示「词贴 v0.1.2 安装包」，label 实测支持中文）
- CI 从 `versionName` 动态拼名；`upload-artifact` 与 Release 资产同步；发布后自动补中文 label
- v0.1.1 的 `citie.apk` 也重命名为 `Citie-v0.1.1.apk`，feed 里最近两版命名统一
- 约定写进 `project/版本与发布.md`，以后每版自动带上版本号

**订阅地址纠正（之前给错了）**
- 用户截图是「新建订阅 / RSS 2.0 · Atom · JSON Feed」输入框 → 该填
  `https://github.com/zjh1934/prompt-float/releases.atom`
- 上一轮给的**仓库地址**只对 Obtainium 这类自动抓包工具成立，对 feed 订阅框是错的
- 如实说明边界：GitHub 原生 `releases.atom` 只有版本通知、**不带 APK 附件**（无 `<enclosure>`），
  订阅后仍需自己去 Releases 下包；若工具需要从 feed 直接解析安装包，需另做带 enclosure 的 feed
- Release 说明里内置两类订阅地址，方便直接复制

**工程**
- `versionCode` 2 → 3，`versionName` 0.1.1 → 0.1.2
- 无功能代码改动，签名与包名不变 → **v0.1.1 可直接覆盖装 v0.1.2**

## v0.1.1（2026-11-25）

**修核心 bug**
- **「点悬浮球展开、面板立马自动收起」已修**。根因：自己 `addView()` 添加
  `TYPE_ACCESSIBILITY_OVERLAY` 面板时系统会派发 `TYPE_WINDOW_STATE_CHANGED`，
  旧逻辑收到就 `collapsePanel()`，把刚展开的面板自己收掉了；键盘弹出同样会误收。
  现在**彻底不用无障碍事件收起面板**，收起只发生在用户明确操作（收起 ✕ / 点提示词 / 再点球 /
  服务销毁 / 设置刷新）。事件订阅收窄到 window/focus 两类。

**新增：一键导出开发者日志（MD）**
- 主页面底部「导出日志」：生成 Markdown → 写私有文件 → 复制剪贴板 → 打开分享，可直接贴给维护者
- 内容含环境快照（版本/包名/机型/API/无障碍状态/悬浮服务实例）、错误汇总、完整事件时间线、
  隐私说明、复现步骤占位
- 埋点覆盖 `APP / SERVICE / A11Y / BALL / PANEL / INSERT / UI / DATA`；
  `collapse(reason=…)`、`paste=…`、`setText=…` 都带上了，贴一份日志就能定位
- 右上角 `⋯` 可清空日志（先留快照再清）、另存 `.md`（系统文件选择器）
- **隐私红线**：不记提示词正文、输入框文本、剪贴板内容、窗口文字，只记事件/布尔值/数量/长度

**界面：砍掉冗余**
- 删除品牌头、LOGO、副标题、版本标签、用法长句、状态解释行、底部提示
- 现在只有：一行状态（圆点 + 文字 + 按钮）、提示词列表、底部三个动作
- 文案全改短词；LOGO 只用于桌面图标，不在页面里重复展示

**固定包名与签名（免卸载重装）**
- 包名 `com.yuanbao.promptfloat` 永久固定，**没改**（改了反而更得卸载）
- 真正让覆盖安装失败的是 CI 每次随机生成 debug 签名 → 现在把一把**公开的开发签名**
  `app/debug.keystore` 入库，debug / release 构建共用
- ⚠️ **一次性代价**：v0.1.0 是旧随机签名包且密钥已随 runner 销毁，
  `v0.1.0 → v0.1.1` 仍需卸载一次；**从 v0.1.1 起往后永久免卸载**
- CI 增加签名 SHA-256 校验打印

**工程**
- 新增 `project/` 目录：项目管理文件与 `app/` 源码分目录、同仓库
  （需求与决策 / 版本与发布 / 开发者日志说明 / 待办与已知限制）
- `versionCode` 1 → 2，`versionName` 0.1.0 → 0.1.1
- CI 不再硬编码 `v0.1.0`，改为从 `versionName` 读版本生成 tag
- APK 文件名固定 `citie.apk`，保证下载工具订阅稳定

## v0.1.0（2026-11-25）

首个开源版本。

**核心**
- 悬浮球（可拖拽、松手贴边、不会停在键盘区），点一下展开成横向提示词面板
- 不替换系统输入法，浮窗全程 `FLAG_NOT_FOCUSABLE`
- 通过 `AccessibilityService.findFocus(FOCUS_INPUT)` 定位当前输入框
- 三级插入兜底：`ACTION_PASTE` → `ACTION_SET_TEXT`（按选区拼接，不覆盖草稿）→ 剪贴板
- 球在屏幕下半部时，面板自动翻到球上方
- 球展开时变深蓝激活态

**主页面**
- 状态卡、提示词增删改、上下排序、JSON 导入导出、空状态引导

**工程**
- MIT 协议，零第三方依赖，零 `uses-permission`
- GitHub Actions 编译 + 自动发 Release 挂 APK
- `CREDITS.md` 写清自写部分与上游参考链接

**已知限制**
- 需要用户手动开启一次无障碍服务
- 密码框 / Flutter / 部分自绘输入框可能拒绝自动插入，已兜底到剪贴板
- 未做真机兼容性矩阵
