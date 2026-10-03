# 第三方组件

- **Lucide 0.468.0**：从官方固定标签 `https://github.com/lucide-icons/lucide/tree/0.468.0/icons` 按需导入 arrow-left、arrow-up、bot、brain-circuit、chevron-down、chevron-right、circle-check、database、ellipsis、external-link、file-text、globe、info、keyboard、maximize-2、mic、minimize-2、monitor、moon、palette、panel-left、paperclip、plus、search、settings、sliders-horizontal、sparkles、square、square-pen、sun、user-round、x，转换为 `app/src/main/res/drawable/lucide_*.xml`。保留 24×24 坐标、2px 描边、圆角端点；rect/circle/line 等价转换为路径。完整 ISC 与 Feather 派生 MIT 文本见 [licenses/lucide-LICENSE](licenses/lucide-LICENSE)。应用包内同样附带许可证。

- **libsu 6.0.0**：通过 Gradle 引用 `com.github.topjohnwu.libsu:service`（包含 core），用于 RootService / Binder 生命周期。许可证 Apache-2.0。来源：https://github.com/topjohnwu/libsu/tree/6.0.0 。
- **scrcpy 4.1**：`DisplayAdapter.kt` 的虚拟屏创建方式和标志参考并适配了 `wrappers/DisplayManager.java`、`video/NewDisplayCapture.java`，未打包整个 scrcpy server。许可证 Apache-2.0，完整文本见 [licenses/scrcpy-LICENSE](licenses/scrcpy-LICENSE)。来源：https://github.com/Genymobile/scrcpy/tree/v4.1 。原始文件版权信息按上游 LICENSE 保留；改动包括固定画面尺寸、只支持 Android 14+、使用 libsu 提供的 Context、隐藏虚拟屏 IME，并省略显示池、旋转和版本回退。
- **OkHttp 4.12.0**：通过 Gradle 引用，用于 OpenAI 兼容模型的 HTTP 请求和超时控制。许可证 Apache-2.0。来源：https://github.com/square/okhttp/tree/parent-4.12.0 。
- **kotlinx.serialization JSON 1.7.3**：通过 Gradle 引用，用于生成模型请求和解析受限 JSON 决策。许可证 Apache-2.0。来源：https://github.com/Kotlin/kotlinx.serialization/tree/v1.7.3 。
- **jsoup 1.23.2**：通过 Gradle 引用，用于在客户端解析搜索结果与提取公开网页正文。许可证 MIT。来源：https://github.com/jhy/jsoup/tree/jsoup-1.23.2 。
- **AndroidSVG 1.4**：通过 Gradle 引用 `com.caverock:androidsvg-aar`，用于把受限的静态 SVG 渲染到 Android Canvas。许可证 Apache-2.0。来源：https://github.com/BigBadaboom/androidsvg/tree/v1.4 。
- **Square gifencoder 0.10.1**：通过 Gradle 引用 `com.squareup:gifencoder`，用于把逐帧 SVG 的栅格结果编码为 GIF89a；该组件不支持透明 GIF，因此工具在 GIF 输出时使用不透明背景。许可证 Apache-2.0。来源：https://github.com/square/gifencoder/tree/gifencoder-0.10.1 。
- **Coil 2.7.0**：通过 Gradle 引用 Compose、GIF 与 SVG 组件，用于在聊天页从受控产物 URI 解码 JPG、PNG、GIF 和 SVG。许可证 Apache-2.0。来源：https://github.com/coil-kt/coil/tree/2.7.0 。

其他 AndroidX、Kotlin 等依赖由 Gradle 版本目录固定。当前聊天版本不提供任意 shell 调用接口。
