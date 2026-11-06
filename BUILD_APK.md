# 画间 Android v1.3 · APK 编译与安装

## 当前状态

**本次交付完整 Android Studio 源码，尚未获得 Android SDK / Gradle 的实际编译结果，也没有已签名 APK 或真机验证记录。** 离线 Java 解析通过不能替代 `assembleDebug`。你只需要在手机应用界面输入 DashScope Key，源码内**没有**预置 Key。

## 开发环境

- JDK **17**、Android Studio（近期稳定版）
- Android SDK Platform **35** 和 Build Tools **35.0.0**
- Gradle **8.9**，Android Gradle Plugin **8.7.3**
- 最低设备 Android **8.0 / API 26**；CameraX **1.4.2**
- 需要访问 Google Maven / Maven Central 下载构建依赖

本工程提供 `gradle/wrapper/gradle-wrapper.properties` 和 `gradlew` 脚本，但**没有**官方 `gradle-wrapper.jar`。电脑上先安装 Gradle 8.9，运行 `gradle wrapper --gradle-version 8.9`，或在 Android Studio 指向本机 Gradle 8.9。不是每个 Android Studio 版本都自动提供本机 Gradle 安装。

## 方案 A：本机 Android Studio

1. 解压 `HuaJian_Android_v1.3_Qwen3_Emotion_SOURCE.zip`。
2. 在 Android Studio 选择 Open，选中解压后的 **`HuaJian_Android_v1.3_Qwen3_Emotion_SOURCE/`** 文件夹。
3. 配置 Gradle 8.9、SDK 35；Gradle 同步成功。
4. 菜单 `Build > Build Bundle(s) / APK(s) > Build APK(s)`；或者在终端执行：

```bash
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
# 或在补齐官方 gradle-wrapper.jar 后
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

5. 成功后文件应位于 `app/build/outputs/apk/debug/app-debug.apk`。用 USB 调试安装 `adb install -r app-debug.apk`，或者手动传到手机安装（按系统提示允许该来源安装应用）。

## 方案 B：GitHub Actions 编译

把整个工程内容**包含 `.github` 目录**上传至你自己的仓库**根目录**，打开 `Actions > Build Android Debug APK > Run workflow`。成功后在运行详情的 Artifacts 下载 **`HuaJian-v1.3-Qwen3-Emotion-debug-APK`**，解压得到 `app-debug.apk`。需要仓库允许 Actions 运行且构建网络可用。该步骤不能由本次对话代替执行。

## 首次安装与 API Key

1. 打开 App 首页的「03 Qwen3 语音情绪识别设置」，输入 API Key；选择北京/新加坡与 API Key 所属地域匹配；如有 Workspace ID 填写，否则留空。
2. 点「安全保存 Key」。Key 经 Android Keystore AES-GCM 加密，只留在手机，不出现在源码、APK 内置常量、导出 ZIP 或录音日志里。
3. 录制和导出完全不依赖 Key。只有进入回看页面勾选本次云端同意，并再次点击确认，才会以实时 WebSocket 协议发送从录音**提取的音频副本**。
4. 先用非儿童音频验证服务端 API 返回的情绪标签和 VAD 时间；如果 Key 地域、模型权限不正确，识别应失败但原录像仍能保存。

## 真机验收

请逐项执行 [docs/REAL_DEVICE_ACCEPTANCE.md](docs/REAL_DEVICE_ACCEPTANCE.md)，特别注意不同手机的音频解码格式、服务端 VAD 事件、语音段时间戳、WebSocket 断线、加密 Key 重启后解锁、情绪标签、多人交错讲话和 400 MB 多包验证。
