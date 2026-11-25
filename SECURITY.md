# 安全说明

## 这个 App 能碰到什么

词贴必须开启无障碍服务才能工作。理论上无障碍服务能读到屏幕上的控件树，
所以这里把边界写死，方便任何人审计：

- **只读「当前聚焦的输入框」这一个节点**：`findFocus(FOCUS_INPUT)`，
  不遍历窗口、不读聊天记录、不读屏幕上其他文字。
- **只写入、不读取**：写入的是你自己预设的提示词；代码里唯一读 `node.text` 的地方是
  `ACTION_SET_TEXT` 兜底时，为了把新文本按选区拼接到已有草稿上，拼完立即写回，不保存、不外发。
- **不联网**：`AndroidManifest.xml` 零 `uses-permission`，没有 `INTERNET`，没有网络库。
- **不替代发送**：不点发送、不碰验证码 / 转账 / 红包类场景。
- **不保留剪贴板**：插入成功后还原原剪贴板；只有两个插入动作都失败时，
  才把提示词留在剪贴板供你手动粘贴。

## 仓库与 CI

- 仓库内**不得出现**任何 API Key、token、keystore、签名密码。
- CI 只使用 GitHub Actions 内置的 `GITHUB_TOKEN`（`permissions: contents: write`），
  不需要、也不接受个人 PAT 写进文件。
- 若需要 PAT，请放仓库 Settings → Secrets，且用最小权限、用完轮换。

## 报告安全问题

请**不要**开公开 issue。直接通过 GitHub 的私有漏洞报告
（仓库 Security → Report a vulnerability）或私信维护者，说明复现路径即可。
