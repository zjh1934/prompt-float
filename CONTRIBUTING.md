# 欢迎共创 🤝

**词贴 / PromptFloat** 是 MIT 完全开源项目，没有商业版、没有内购、没有广告、没有账号、
没有统计、不联网。任何人都可 fork、修改、再分发。

## 先说规矩

1. **不要提交任何私有密钥。** 仓库里不出现 API Key、token、私有 keystore、私有签名密码。
   CI 用 GitHub Actions 内置的 `GITHUB_TOKEN`，不需要也不接受任何 PAT 入库。
   **唯一例外**：`app/debug.keystore` 是随仓库提交的**公开开发签名**，
   用途只是让各次构建签名一致、能覆盖安装，不是安全凭证（见 `SECURITY.md`）。
   改它会导致所有用户必须卸载重装，**绝对不要动**。
2. **保持零第三方依赖。** `app/build.gradle.kts` 的 `dependencies { }` 是空的，这是刻意设计
   （CI 稳定、审计简单、无供应链面）。要加依赖请先开 issue 讨论。
3. **不收集数据。** 任何会读取、上传用户输入内容的 PR 都会被拒。这条是红线。
4. **只填不发。** 不替用户点发送、不碰转账/红包/验证码类场景。

## 怎么开始

```bash
git clone https://github.com/zjh1934/prompt-float.git
cd prompt-float
```

用 Android Studio（Giraffe 或更新）打开根目录即可。工程只需要：

- JDK 17
- Android SDK Platform 34
- Gradle 8.7（Android Studio 会自动生成 wrapper）

命令行编译：

```bash
gradle wrapper --gradle-version 8.7 --distribution-type bin
./gradlew :app:assembleDebug
# Gradle 原生产物：app/build/outputs/apk/debug/app-debug.apk
# CI 发布时会改名成「词贴-v${versionName}.apk」，别把原生产物名直接传出去
```

## 提交前自查

- [ ] 改动没有引入新的权限声明（`AndroidManifest.xml` 里应保持零 `uses-permission`）
- [ ] 浮窗仍然带 `FLAG_NOT_FOCUSABLE`（抢焦点会让输入框失焦，功能直接失效）
- [ ] `AccessibilityNodeInfo` 用完后 `recycle()`
- [ ] 插入顺序仍是 `ACTION_PASTE` → `ACTION_SET_TEXT`（按选区拼接）→ 剪贴板兜底
- [ ] 中文文案同步更新 `res/values/strings.xml`，不要硬编码在代码里
- [ ] 改了 UI 就更新 `docs/preview/` 里的预览图
- [ ] 改了需求 / 版本约定 / 排 bug 流程就更新 [`project/`](project/README.md)（项目文件，与 `app/` 源码分开）
- [ ] 出包前 `versionName` patch +1、`versionCode` +1，补 [CHANGELOG.md](CHANGELOG.md)
- [ ] 上游思路有新增就补进 [CREDITS.md](CREDITS.md)

## 真机测试清单（最缺这个）

不同 App 的输入框实现差别很大，欢迎把实测结果贴到 issue：

| 场景 | 预期 | 当前 |
|---|---|---|
| 原生 `EditText`（短信、备忘录） | 直接插入 | 待验证 |
| Chrome / WebView 网页输入框 | 直接插入或剪贴板兜底 | 待验证 |
| 各家 AI App 对话输入框 | 直接插入 | 待验证 |
| 密码框 | 不写入，走剪贴板兜底提示 | 待验证 |
| Flutter 自绘输入框 | 可能两动作都拒绝 | 待验证 |

## 提 issue / PR

- Bug：用 `.github/ISSUE_TEMPLATE/bug_report.md` 模板，务必写清机型、Android 版本、
  目标 App 名称和「插入成功 / 走兜底 / 完全失败」。
- 功能：先开 `feature_request.md` 讨论，别直接甩一个大 PR。
- PR 标题用中文或英文都行，正文说清「改了什么、为什么、怎么测的」。

## 行为准则

正常、友善、就事论事。技术判断有分歧欢迎直接怼，但对人不对事的不欢迎。
