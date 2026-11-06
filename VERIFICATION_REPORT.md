# 画间 v1.3 离线验证记录

本文件在重新构建时生成，下面为**离线核验**，不是已完成的 Android SDK 构建、真机测试或 DashScope API 结果。

- Java 程序源文件已做不依赖 Android SDK 的语法检查（JDK 编译器词法/语法解析），不保证 Android API、三方库依赖的解析和 ABI 一定通过。
- 包含纯 Java 分包算法实测和说话者时间重叠分配单元测试。
- Python 模拟 ZIP 完整性、SHA-256/缺包检查。
- 静态检查 APK Manifest、资源 ID、XML 结构、网络权限与 Key 不进入导出设计。
- App 界面已经增加 API Key 输入/删除、可选地域和云端授权复选框，云端 ASR 使用录后 1x WebSocket 回放。
- Android `assembleDebug` / `testDebugUnitTest`：**未执行**（当前无 SDK/Gradle）。
- Qwen3 服务端正式转写/情绪标签：**未调用**（未获得用户的 Key）。
- sherpa-onnx **安卓原生推理及其模型权重未打包**；已有 JSON 导入、独立离线工具样板与关联算法。

运行命令：

```bash
bash scripts/run_offline_tests.sh
```

最后请阅读 `docs/REAL_DEVICE_ACCEPTANCE.md`，用非儿童音视频完成闭环验收后再开展儿童绘画测试。
