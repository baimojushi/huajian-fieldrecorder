# 画间 Android v1.3 · GPT-6 证据包格式

## 概览

v1.3 延续 v1.2 的可追溯轻量导出：在手机本地保存连续原始 MP4；从源视频抽取画面，AAC 音轨不转码分段封装 M4A；人工事件与媒体共享全局毫秒时间轴；使用约 380,000,000 字节预算计划分包，确保每个 ZIP **严格小于 400,000,000 字节**。外部保存 `SESSION_PACKET_INDEX.json`，避免把 ZIP 自身 SHA-256 放进 ZIP 的自引用问题。

## 新增语音分析字段

只有 Qwen3 会话确实成功完成 (`status=completed`)，分包才包括 `SPEECH/qwen3_asr_emotion.json`。它记录模型名、地域、识别状态及本包时间段的 `segments`，其主要字段：

| 字段 | 含义 |
|---|---|
| `item_id` | 服务端音频项目编号，用于配对 VAD 与文字 |
| `start_ms`, `end_ms` | **源 MP4 全局时间轴**中的服务端 VAD 句段起止毫秒；可能为空，不能虚构字级时间 |
| `transcript` | Qwen3 的语音转写（须人工核听） |
| `emotion` | Qwen3 模型输出七类声音情绪之一，可能缺失；**不是心理结论** |
| `speaker_id` | 没有离线声纹融合时为 `unknown` |
| `completed`, `needs_review` | 转写成功事件、人工复核需求 |
| `source` | `qwen3-asr-flash-realtime` |

若人工导入了可信的离线声纹时段，系统可生成 `SPEECH/speaker_fusion.json`；其 `speaker_id` 仍然只是 S1/S2/unknown 候选、**不等于儿童/成人身份**。`TIMELINE/part_media.json` 另有 `speech_segments` 便于离线 HTML 播放器跳转。多人同时说话可能无法被单次 ASR 完整辨识。没有字词时间戳；所有“字级精确”声称都不符合此版本。

## 示例目录

```text
SESSION_GPT6_PART_001_OF_002.zip
  README_FIRST.txt
  MANIFEST/global_index.json
  MANIFEST/part_manifest.json
  MANIFEST/source_session.json
  TIMELINE/part_media.json
  TIMELINE/events_all.json
  SPEECH/README.txt
  SPEECH/qwen3_asr_emotion.json          # 可选；仅状态 completed
  SPEECH/speaker_fusion.json           # 可选；导入声纹时段后
  MEDIA/audio/*.m4a                   # 来源 AAC 编码样本，重新封装不重编码
  MEDIA/frames/*.jpg                  # 请求时刻的抽帧
  MEDIA/sheets/*.jpg                  # 16 帧总览
  MEDIA/final_art/*.jpg               # 可选作品终稿
  VIEWER/index.html                   # HTML 离线同步播放器
  GPT_HANDOFF/01_GPT6_PROMPT.md
SESSION_GPT6_PART_002_OF_002.zip
SESSION_PACKET_INDEX.json             # ZIP 外；分包 SHA-256/size
```

`start_ms`/`end_ms` 与 session 录像播放时刻一致；音轨分段重新封装时 M4A 时间轴从局部起点开始，但索引保存了其在源视频里的位置。JPEG 时间为请求的目标时间，实际解码帧时间可能附近偏移；抽帧间没有连续动作证据。`SPEECH/` 在每包只包含该包对应时间段的语音子集。

## 完整性与隐私

每个分包有媒体 SHA-256、ZIP CRC 与外部整个 ZIP 的 SHA-256/bytes；使用 `python3 tools/verify_packets.py /path/to/export/folder` 进行本地校验。ZIP 自身无加密，不能直接公开分享儿童声音及图像。

API Key **不会**进入源会话 JSON、导出包、离线播放器、索引或模型分析提示词。原始 WebSocket 服务器事件与临时 PCM 同样不包含于 ZIP；PCM 在处理后删除，JSONL 服务器事件可能留在本机私有存储以供复核。云端只发送录音的解码音频副本，不上传原始视频。

## 边界条件

- 不支持非 AAC 音轨在导出时伪装为“原样保存”：会抛出错误，请先判断是否可做研究测试。
- 没有音轨的源视频可以导出画面及标记，但不能调用语音识别。
- 模型请求失败时输出不会伪装为已完成的语音结果。
- 400 MB 仅为本工程 ZIP 大小规则，不保证当前 ChatGPT/GPT-6 对 ZIP 内媒体的解码能力。必要时单独上传 M4A/JPG/JSON。
- 代码规划跨包时不主动生成重新编码的合成 MP4；需要音画合成可使用视频外部工具依统一时间轴离线重建。
