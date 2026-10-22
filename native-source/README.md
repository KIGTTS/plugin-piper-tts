# Piper 本地发音源码

`espeak_jni.cpp` 来自旧 KIGTTS（GPL-3.0-only），`piper-phonemize/` 是该工程实际编译的发音器源码（MIT，含 uni-algo 自带许可）。`upstream/` 保留 rhasspy/espeak-ng 提交 `0f65aa301e0d6bae5e172cc74197d32a6182200f` 的完整源码归档；补丁由 `patch_espeak.cmake` 给出。各许可在插件的 `licenses/`，随插件包分发。

首版 `.so` 是旧工程已有的 arm64 构建，保留旧 JNI 符号名便于迁移。独立 CMake 入口只构建 eSpeak，没有旧工程的 AEC、录音或其它引擎。使用 Android NDK 和 CMake 可重建，例如在 PowerShell 7 中：

```powershell
cmake -S engine-plugins/piper-tts/native-source -B build/piper-native-arm64 -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE="$env:ANDROID_NDK/build/cmake/android.toolchain.cmake" `
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static
cmake --build build/piper-native-arm64 --config Release
```

将产生的 `libespeak_jni.so`、`libespeak-ng.so` 放入此插件 `src/main/jniLibs/arm64-v8a/` 后重新打包。链接按 16 KiB 页对齐；新增 ABI 时要先编译并验证完整依赖，再同步修改插件构建和打包的 ABI 范围。当前环境未安装 NDK，因此本次使用已有库，未宣称这个独立构建入口已实编。
