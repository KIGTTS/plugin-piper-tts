# Piper 插件来源

本插件移植旧 KIGTTS Engines.kt 的 Piper ONNX 推理、词典发音及 eSpeak 多语言近似适配，保留 GPL-3.0-only（LICENSE）。按解析、语音包存储、发音、推理、接口分离；不包含 AEC、录音、声纹或宿主 UI。

预编译 arm64 eSpeak JNI/eSpeak NG 库来自原工程 Debug 输出；JNI 符号 namespace 在插件内部保留用于 ABI 兼容。依赖源：espeak-ng 固定提交 0f65aa301e0d6bae5e172cc74197d32a6182200f（https://github.com/rhasspy/espeak-ng），piper-phonemize（https://github.com/rhasspy/piper-phonemize）。原工程使用 16 KiB 页面链接选项；源码与构建参考见 native-source/。

ONNX Runtime Android 1.24.3（MIT）和 XZ 1.9（公共领域）仅打进本插件。中文 pypinyin 词典及日文 IPADIC 的许可位于资产目录，打包时完整保留。语音模型单独导入，许可依模型发布者提供的内容。
