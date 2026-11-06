# 画间 · Android v1.3 Qwen3 声音情绪版

> **交付类型：可导入 Android Studio 的源码工程，不是 APK。** 本环境缺少 Android SDK/Gradle 和网络构建条件，尚未完成安卓 `assembleDebug`、真机和真实 DashScope API 调用验收。请先用非儿童录音测试，勿把静态测试误认为真实模型识别成功。

## 1. 功能与边界

| 功能 | 本版状态 |
|---|---|
| CameraX 取景、原声 MP4 录像，系统相机或相册导入 | 沿用 v1.2 |
| 参与者匿名代号、九类现场事件、长按备注、录像回看与作品照片 | 沿用 v1.2 |
| AAC 原始编码包直接提取、分段封装 M4A、3 秒抽帧与事件加密抽帧 | 沿用 v1.2 |
| 严格小于 400,000,000 字节的多 ZIP 分包、外置索引、SHA-256、时间轴与离线播放器 | 沿用 v1.2 |
| App 内手动输入 DashScope API Key，选择北京/新加坡，选填 Workspace ID | **新增，已实现源码** |
| API Key 使用 Android Keystore AES-GCM 加密存储、支持清除 | **新增，已实现源码** |
| **录制结束后**将 MP4 的音轨解码为临时 16 kHz 单声道 PCM16，沿 WebSocket 按真实速度送入 Qwen3 | **新增，已实现源码；非录制过程中在线 ASR** |
| 单次 `qwen3-asr-flash-realtime` 语音转写、7 类声音情绪与服务端 VAD 句段时间 | **新增，待真实 API 验收** |
| 独立开源声纹时间段 JSON 导入、与 ASR 段落保守合并为 S1/S2/unknown | **新增；需要另行运行本地声纹模型，不等同于手机内置声纹推理** |
| 完整模型情绪、转写、时间轴导出到 GPT-6 ZIP 内 `SPEECH/` | **新增，已实现源码** |
| 手机内置 sherpa-onnx 推理与模型权重 | **本版未包含**；提供离线生成结果的工具和导入接口 |
| 麦克风录制过程中实时识别 | **本版未包含**；当前优先保护 CameraX 原始录像稳定性 |

## 2. 为什么先采用“录制后调用 Realtime API”

用户希望保留原始手机录音、时间轴、作品及多人的不确定性。CameraX 当前已占用麦克风。为避免 Android 并发采集造成录像音轨丢失，本版先从保存好的录像解码**临时副本**，使用实时 WebSocket 协议**按 100 ms 一包、每秒约发送一秒音频**。这种调用能验证 Qwen3 的实际转写、情绪标签、服务端 VAD 时间与集成代码，**但不提供现场同步文字或声纹结果**。

不增加第二次云端 ASR。Qwen3 当前没有字词级时间戳；`start_ms/end_ms` 取自服务端 VAD 的 `audio_start_ms/audio_end_ms`，是句段时间，不伪造字级对齐。音频第一帧存在原始 PTS 偏移时，临时识别 PCM 会补开头静音，以匹配原视频时间零点。结束后额外发送 1 秒静音只用于提交最后语句，不改变原音频。

## 3. 安装编译

查看 [BUILD_APK.md](BUILD_APK.md)。需要 Android Studio、JDK 17、Android SDK 35、Gradle 8.9；minSdk 26。工程包含 `.github/workflows/android-debug.yml` 可在你自己的仓库中自动构建 Debug APK。**当前没有已编译 APK。**

## 4. App 上的实际使用步骤

1. 打开「画间」，在首页「Qwen3 语音情绪识别设置」输入自己的 **DashScope API Key**（密码框不回显），选择**与 Key 相对应的地域**，按需填写 Workspace ID，点 **「安全保存 Key」**。不要把 Key 发到聊天、APK 源码或截图里。
2. 输入匿名参与者代号并确认必要同意；录像、系统相机及相册导入均不要求填写 API Key。录制现场原声与画面、随时点击九宫格标记事件。
3. 完成录像、打开回看。在「语音转写与情绪标签」勾选**本次云端音频发送授权**，点击 **「识别本次音频」**，确认二次弹窗；此时才发送音频至 DashScope。识别过程中保持 App 前台；过程通常至少接近录音时长。**原视频不上传**。
4. 识别成功后可在回看页看到每个语音段的文本、时间与情绪标签；点击可跳转本机视频核听。情绪是音色分类，**不是当事人的真实心理状态或诊断**。模型可能识错台词与情绪，必须人工核对。
5. 如果需要 S1/S2：在电脑上使用自愿成人测试音频及开源 sherpa-onnx 或其他可信离线模型生成说话者时间段 JSON（参见下文）；拷入手机，点「导入本地声纹分离 JSON」关联声纹结果。没有声纹文件时所有语音段保留 `unknown`。
6. 点击「打包 GPT-6 资料」选择一个手机文件夹。资料包含 M4A、抽帧 JPG、人工事件 JSON、完整性索引，以及**识别成功时**的 `SPEECH/qwen3_asr_emotion.json`；合并了声纹文件才额外出现 `SPEECH/speaker_fusion.json`。每个 ZIP 严格小于十进制 400 MB。
7. 在电脑上先运行 `python3 tools/verify_packets.py /path/to/exported/folder`，再人工把相关音视频、JSON 和提示词交给 GPT-6；模型能上传 ZIP **不代表**一定能解码 ZIP 中全部媒体，必要时解压后分开提交。

**重要：重复识别会重新发送录音并可能产生费用；网络错误、模型不支持或未得到最终会话完成事件时结果标为 failed，导出时不会假冒 completed。**

## 5. 本地声纹结果接口

```json
{
  "engine": "local-sherpa-onnx",
  "segments": [
    {"start_ms": 20000, "end_ms": 22300, "speaker_id": "S1"},
    {"start_ms": 22400, "end_ms": 24600, "speaker_id": "S2"}
  ]
}
```

这是**格式示例**，不是实际儿童录音的识别结果。`start_ms/end_ms` 必须是**原视频从 0 开始的毫秒时间**；如果模型处理的是单独 M4A/截取片段，应把其源时间偏移加回去。工具脚本 `tools/sherpa_diarization.py` 提供电脑离线 JSON 生成样板：需自行安装兼容的 `sherpa-onnx`、`soundfile`、`numpy` 并下载受许可的分段和声纹模型文件，**源码包不包括权重**。安卓侧的匹配算法有保守拒判规则，弱重叠与多人同时讲话标为 `unknown`；S1 不能直接认定为儿童。

## 6. 导出结构

```text
session_GPT6_PART_001_OF_002.zip
  MANIFEST/global_index.json
  MANIFEST/part_manifest.json
  TIMELINE/part_media.json       # media / events / speech_segments 同一时间线
  TIMELINE/events_all.json
  MEDIA/audio/audio_0001.m4a     # 原 AAC 样本直接封装
  MEDIA/frames/*.jpg             # 视频抽帧
  MEDIA/sheets/*.jpg             # 16 帧总览
  SPEECH/qwen3_asr_emotion.json  # 仅当成功识别
  SPEECH/speaker_fusion.json     # 仅当导入并融合声纹
  SPEECH/README.txt
  GPT_HANDOFF/01_GPT6_PROMPT.md
  VIEWER/index.html
session_GPT6_PART_002_OF_002.zip
session_PACKET_INDEX.json       # ZIP 外部，避免整包哈希自引用
```

源视频不进轻量 GPT ZIP，长期保存在手机 App 的私有会话文件夹，必要时选择「单独另存原始录像」。临时 PCM 在识别结束或失败时清除。服务端原始事件日志保留在私有会话本地 `analysis/qwen3_server_events.jsonl`，**不进入导出包**；API Key 只在 Android Keystore 加密存储。

## 7. 测试与保密限制

- `bash scripts/run_offline_tests.sh`：JVM ZipPartitioner 与声纹重叠算法、Python ZIP 验证器、Java 语法解析、Android 资源静态核验。**不是 Android SDK 编译。**
- 手机私有目录中的儿童原始视频、可读的转写内容和模型原始事件记录依然具有隐私风险。不要把云端 ASR 误称为“完全本地处理”。正式用于儿童资料前须查核参与者同意、云端处理地域、期限、数据条款和相应法规。
- Qwen3 输出“愤怒/恐惧”等属于声音分类线索，而非儿童内在感受或稳定人格结论；GPT-6 报告需要声音、画面、情境与人工复核共同支撑。
- 多人重叠语音可能导致单次 ASR 漏词、错误情绪归属；该工程不具备独立分离出两路重叠语音波形的能力。

参见 [EXPORT_FORMAT.md](EXPORT_FORMAT.md)、[PRIVACY.md](PRIVACY.md)、[docs/REAL_DEVICE_ACCEPTANCE.md](docs/REAL_DEVICE_ACCEPTANCE.md)、[VERIFICATION_REPORT.md](VERIFICATION_REPORT.md)。

## 8. 官方技术文档

- [DashScope Qwen3 ASR Realtime 文档](https://help.aliyun.com/zh/model-studio/real-time-speech-recognition-user-guide)
- [sherpa-onnx Android / Speaker Diarization](https://github.com/k2-fsa/sherpa-onnx)
