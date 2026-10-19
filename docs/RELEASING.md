# 构建与发布安装包

当前正式版本：App **0.1.21 / versionCode 21**，Android Use 桌面插件 **0.2.0**。两者版本独立维护。App 的版本号定义在 `app/build.gradle.kts`，更新摘要在 `CHANGELOG.md`。

## 准备版本

1. 从已同步的 `main` 建立发布分支，更新 App 的 `versionName`、递增 `versionCode`，同步中英文 README、CHANGELOG 和相关使用说明。
2. 检查依赖和本地配置。签名配置放在未跟踪的 `local.properties`，密钥库不能提交到 Git。正式分发只使用 `release` 变体；debug 构建可能包含本地开发用模型默认值。
3. 运行相关验证：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s desktop/tests
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests
./gradlew :device:testDebugUnitTest :agent-core:test :app:assembleRelease
```

本版本已通过 `:agent-core:test`（188 项）、App 豆槽采样（6 项）、Kotlin 编译、发布工具 CLI（9 项）及正式 release 构建；APK 版本、签名、非调试属性和模型默认配置为空均已校验。本轮未新增整局真机验证。既有真机范围及 instrumentation/lint 限制见 [桌面控制验证](desktop-control.md#验证范围)。`:app:assembleRelease` 的 lint vital 检查与全量 lint 不是同一范围。

## 生成并校验产物

`scripts/package-release.py` 只校验和整理已经生成的 APK，不构建、不签名、不联网。以下示例使用 macOS/Linux Android SDK 的 `aapt`、`apksigner` 命令；修改 `--build-tools` 为本机 SDK 路径。

```bash
python3 scripts/package-release.py \
  --apk app/build/outputs/apk/release/app-release.apk \
  --build-tools /path/to/Android/sdk/build-tools/36.0.0 \
  --version 0.1.21 \
  --version-code 21 \
  --expected-cert b26b19de23bde953be9d1a9faa6a5b2cbf03bc07335c2c72badd4cd5958cad90 \
  --revision "$(git rev-parse HEAD)" \
  --output-dir build/releases/v0.1.21
```

此证书指纹与项目此前公开的 v0.1.20 安装包一致。独立 fork 应使用自己的发布证书；已有安装的覆盖升级要求签名连续一致，不能通过清除用户数据来掩盖签名不匹配。

工具拒绝包名、版本、版本码或证书不符，以及 debuggable、无效签名的 APK。成功生成：

| 文件 | 用途 |
| --- | --- |
| `app-release.apk` | 正式安装包；保持此文件名，供应用内更新识别 |
| `SHA256SUMS` | APK 的 SHA-256 摘要，格式兼容 `sha256sum -c` / `shasum -a 256 -c` |
| `release-info.json` | 包名、版本、SDK 范围、签名证书指纹、文件摘要及源码提交 |

打包时应使用干净且经过验证的源码。`sourceCommit` 来自显式 `--revision` 参数，不是从 APK 反推的源码证明。PR squash 合并后，若源码树与已验证构建一致，使用合并提交重新生成元数据，避免将候选分支或旧基线记为发布来源。

## 发布到 GitHub

仅在用户已经明确授权相关推送、PR、合并和发布操作后执行。先完成代码、检查和可审阅产物；遇到签名、系统安装或仓库权限限制时，不绕过限制。

1. 推送发布分支并创建 PR，记录实际测试范围；合并后同步本地 `main`，确认合并树与已验证源码一致。
2. 用最终合并提交重新生成发布目录，核对 `release-info.json` 的 `sourceCommit` 和 APK 摘要。
3. 将 `v0.1.21` 标签指向这个提交，推送标签后创建正式 Release。只上传上述三个发布文件，不上传 debug APK、密钥、本地配置或手机截图。
4. 下载已发布 APK，复核其 SHA-256 与本地一致，并检查 GitHub `releases/latest` 已指向新版本。
5. 在已授权的测试设备覆盖安装，核对版本、签名和数据保留；不要为通过安装而卸载原应用。

应用内更新读取 GitHub 最新正式 Release，从 assets 中选择 `.apk` 文件。保持单一正式 APK，并保留 `v` 前缀版本标签；将来更新版本时同步修改本指南中的版本参数。
