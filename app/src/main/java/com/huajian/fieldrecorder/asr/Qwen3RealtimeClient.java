package com.huajian.fieldrecorder.asr;

import com.huajian.fieldrecorder.SessionStore;
import com.huajian.fieldrecorder.security.ApiKeyVault;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** WebSocket implementation of the official Qwen3-ASR-Flash-Realtime event protocol.
 * Replays existing audio at 1x speed, so CameraX keeps its sole ownership of the microphone.
 * This is NOT recording-time ASR; it uses a streaming API to process a saved recording.
 */
public final class Qwen3RealtimeClient {
    public interface Feedback { void update(String message); }
    private static final int INPUT_RATE = 16000;
    private static final int CHUNK_BYTES = 3200; // 100 ms of 16 kHz mono PCM16
    private static final String MODEL = "qwen3-asr-flash-realtime";

    public static String endpoint(String region, String workspace) {
        ApiKeyVault.validateWorkspace(workspace);
        String suffix = "singapore".equals(region)
                ? "ap-southeast-1.maas.aliyuncs.com"
                : "cn-beijing.maas.aliyuncs.com";
        String host = workspace == null || workspace.isEmpty()
                ? ("singapore".equals(region) ? "dashscope-intl.aliyuncs.com" : "dashscope.aliyuncs.com")
                : workspace + "." + suffix;
        return "wss://" + host + "/api-ws/v1/realtime?model=" + MODEL;
    }

    private static final class Span {
        final String itemId;
        Long startMs, endMs;
        String transcript = "", language = "", emotion = "";
        boolean complete = false;
        Span(String itemId) { this.itemId = itemId; }
        JSONObject json() throws Exception {
            JSONObject j = new JSONObject();
            j.put("item_id", itemId);
            j.put("start_ms", startMs == null ? JSONObject.NULL : startMs);
            j.put("end_ms", endMs == null ? JSONObject.NULL : endMs);
            j.put("transcript", transcript);
            j.put("language", language);
            j.put("emotion", emotion.isEmpty() ? JSONObject.NULL : emotion);
            j.put("speaker_id", "unknown"); // never infer identities from emotion
            j.put("emotion_is_observation_not_diagnosis", true);
            j.put("source", "qwen3-asr-flash-realtime");
            j.put("time_basis", "server_vad_ms_relative_to_stream_start");
            j.put("needs_review", true);
            j.put("completed", complete);
            return j;
        }
    }

    private final Map<String, Span> spans = new LinkedHashMap<>();
    private final CountDownLatch configured = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile String failure = null;
    private volatile boolean serverFinished = false;
    private WebSocketClient ws;
    private BufferedWriter audit;
    private Feedback callback;
    private final Object auditLock = new Object();
    private long audioDurationMs;
    private long sourceFirstPtsMs;

    private Span span(String id) {
        if (id.isEmpty()) id = "unidentified_" + (spans.size() + 1);
        return spans.computeIfAbsent(id, Span::new);
    }

    private void handle(JSONObject j) {
        String kind = j.optString("type");
        try {
            // The stored event log contains ONLY messages sent BY the server, never auth headers.
            if (kind.startsWith("input_audio_buffer.") ||
                kind.startsWith("conversation.item.input_audio_transcription.") ||
                kind.equals("error") || kind.equals("session.updated") || kind.equals("session.finished")) {
                synchronized (auditLock) {
                    audit.write(j.toString());
                    audit.newLine();
                }
            }
            if (kind.equals("session.updated")) {
                configured.countDown();
            } else if (kind.equals("input_audio_buffer.speech_started")) {
                Span s = span(j.optString("item_id"));
                long t = j.optLong("audio_start_ms", -1);
                if (t >= 0) s.startMs = t;
            } else if (kind.equals("input_audio_buffer.speech_stopped")) {
                Span s = span(j.optString("item_id"));
                long t = j.optLong("audio_end_ms", -1);
                if (t >= 0) s.endMs = t;
            } else if (kind.equals("conversation.item.input_audio_transcription.text")) {
                String preview = j.optString("text") + j.optString("stash");
                callback.update("实时转写预览 · " + (preview.length() > 46 ? preview.substring(0, 46) + "…" : preview));
            } else if (kind.equals("conversation.item.input_audio_transcription.completed")) {
                Span s = span(j.optString("item_id"));
                s.transcript = j.optString("transcript");
                s.language = j.optString("language");
                s.emotion = j.optString("emotion");
                s.complete = true;
                callback.update("✓ " + s.transcript + (s.emotion.isEmpty() ? "" : "  ·  " + s.emotion));
            } else if (kind.equals("conversation.item.input_audio_transcription.failed")) {
                JSONObject error = j.optJSONObject("error");
                failure = "某个语音段转写失败：" + (error == null ? "未知错误" : error.optString("code"));
                finished.countDown();
            } else if (kind.equals("error")) {
                JSONObject error = j.optJSONObject("error");
                failure = error == null ? "DashScope 服务端返回错误" :
                    "DashScope: " + error.optString("code") + " " + error.optString("message");
                configured.countDown(); finished.countDown();
            } else if (kind.equals("session.finished")) {
                serverFinished = true; finished.countDown();
            }
        } catch (Exception e) {
            failure = "模型事件处理失败：" + e.getClass().getSimpleName();
            configured.countDown(); finished.countDown();
        }
    }

    /** The user must have explicitly opted in to send this recording to the cloud. */
    public JSONObject recognize(File pcm16, File analysisDir, String apiKey,
                                String region, String workspace, long firstPtsMs,
                                Feedback feedback) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IOException("请先填写并保存 API Key");
        if (!pcm16.isFile() || pcm16.length() < 3200 || (pcm16.length() % 2) != 0)
            throw new IOException("没有有效的 PCM16 识别输入");
        if (!analysisDir.exists() && !analysisDir.mkdirs()) throw new IOException("无法创建识别缓存目录");
        this.audioDurationMs = pcm16.length() * 1000L / (INPUT_RATE * 2L);
        this.sourceFirstPtsMs = firstPtsMs;
        this.callback = feedback;
        File eventsFile = new File(analysisDir, "qwen3_server_events.jsonl");
        File resultFile = new File(analysisDir, "qwen3_segments.json");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(eventsFile, false), StandardCharsets.UTF_8))) {
            this.audit = writer;
            ws = new WebSocketClient(new URI(endpoint(region, workspace))) {
                @Override public void onOpen(ServerHandshake handshake) {
                    connected.set(true);
                    try {
                        JSONObject turn = new JSONObject().put("type", "server_vad")
                                .put("threshold", 0.2).put("silence_duration_ms", 400);
                        JSONObject session = new JSONObject().put("modalities", new JSONArray().put("text"))
                                .put("input_audio_format", "pcm")
                                .put("sample_rate", INPUT_RATE)
                                .put("turn_detection", turn);
                        send(new JSONObject().put("event_id", eventId()).put("type", "session.update")
                                .put("session", session).toString());
                    } catch (Exception e) { failure = "无法初始化语音会话"; configured.countDown(); }
                }
                @Override public void onMessage(String message) {
                    try { handle(new JSONObject(message)); }
                    catch (Exception ignored) { /* Unparseable message cannot become evidence. */ }
                }
                @Override public void onClose(int code, String reason, boolean remote) {
                    connected.set(false);
                    if (!serverFinished) {
                        failure = "连接提前中断（" + code + "），识别可能不完整";
                        configured.countDown(); finished.countDown();
                    }
                }
                @Override public void onError(Exception ex) {
                    failure = "WebSocket 连接或网络错误";
                    configured.countDown(); finished.countDown();
                }
            };
            ws.addHeader("Authorization", "Bearer " + apiKey);
            ws.addHeader("OpenAI-Beta", "realtime=v1");
            try {
                feedback.update("连接 Qwen3 云端语音识别服务…");
                if (!ws.connectBlocking(18, TimeUnit.SECONDS) || !connected.get())
                    throw new IOException("连接超时。请检查 API Key、地域和网络。");
                if (!configured.await(22, TimeUnit.SECONDS)) throw new IOException("服务端未确认 session.update");
                if (failure != null) throw new IOException(failure);
                long startTime = System.nanoTime();
                long samplesSent = 0;
                byte[] chunk = new byte[CHUNK_BYTES];
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(pcm16), 65536)) {
                    while (true) {
                        if (failure != null) throw new IOException(failure);
                        if (!connected.get()) throw new IOException("识别过程中连接已断开");
                        int n = fill(in, chunk);
                        if (n == 0) break;
                        // Replay with real-time pacing. Sending entire file immediately can alter server VAD behavior.
                        long wantElapsed = samplesSent * 1_000_000_000L / INPUT_RATE;
                        while (System.nanoTime() - startTime < wantElapsed) {
                            long waitMs = (wantElapsed - (System.nanoTime() - startTime)) / 1_000_000L;
                            Thread.sleep(Math.min(40, Math.max(1, waitMs)));
                        }
                        JSONObject data = new JSONObject().put("type", "input_audio_buffer.append")
                                .put("event_id", eventId())
                                .put("audio", Base64.encodeToString(chunk, 0, n, Base64.NO_WRAP));
                        ws.send(data.toString());
                        samplesSent += n / 2;
                        if (samplesSent % (INPUT_RATE * 5L) < CHUNK_BYTES / 2L)
                            feedback.update("正在识别音频 " + samplesSent / INPUT_RATE + " / " + audioDurationMs / 1000 + " 秒");
                    }
                }
                if (failure != null) throw new IOException(failure);
                // Give VAD 1000ms of trailing silence so the final utterance can be committed.
                // Padding exists only in the ASR stream; source MP4, AAC and source timestamps remain unchanged.
                byte[] silence = new byte[CHUNK_BYTES];
                for (int tail = 0; tail < 10; tail++) {
                    long wantElapsed = samplesSent * 1_000_000_000L / INPUT_RATE;
                    while (System.nanoTime() - startTime < wantElapsed) {
                        long waitMs = (wantElapsed - (System.nanoTime() - startTime)) / 1_000_000L;
                        Thread.sleep(Math.min(40, Math.max(1, waitMs)));
                    }
                    ws.send(new JSONObject().put("type", "input_audio_buffer.append")
                            .put("event_id", eventId()).put("audio", Base64.encodeToString(silence, Base64.NO_WRAP)).toString());
                    samplesSent += CHUNK_BYTES / 2;
                }
                if (failure != null) throw new IOException(failure);
                ws.send(new JSONObject().put("type", "session.finish").put("event_id", eventId()).toString());
                if (!finished.await(65, TimeUnit.SECONDS)) throw new IOException("等待转写结束超时（服务端未返回 session.finished）");
                if (failure != null || !serverFinished)
                    throw new IOException(failure == null ? "服务器未完整完成会话" : failure);
                synchronized (auditLock) { writer.flush(); }
                JSONObject normalized = makeResult("completed", region, workspace);
                SessionStore.writeJson(resultFile, normalized);
                return normalized;
            } catch (Exception err) {
                synchronized (auditLock) { writer.flush(); }
                // Persist partial evidence with explicit FAILED status; never present it as finalized.
                JSONObject partial = makeResult("failed", region, workspace);
                partial.put("error", err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage());
                SessionStore.writeJson(resultFile, partial);
                throw err;
            } finally {
                if (ws != null) {
                    try { ws.closeBlocking(); } catch (Exception ignored) { try { ws.close(); } catch (Exception ignore) { } }
                }
            }
        }
    }

    private JSONObject makeResult(String status, String region, String workspace) throws Exception {
        JSONObject result = new JSONObject();
        result.put("schema", "huajian.qwen3.asr_emotion.v1.3");
        result.put("model", MODEL);
        result.put("status", status);
        result.put("region", region);
        result.put("workspace_configured", workspace != null && !workspace.isEmpty());
        result.put("time_zero", "source MP4 media start, audio includes source leading delay padding");
        result.put("audio_first_decoded_pts_ms", sourceFirstPtsMs);
        result.put("replayed_pcm_duration_ms", audioDurationMs);
        result.put("word_level_timestamps_available", false);
        result.put("speaker_diarization_performed", false);
        result.put("emotion_confidence_available", false);
        result.put("server_vad_tail_silence_padding_ms", 1000);
        result.put("emotions_are_acoustic_model_labels_not_psychological_facts", true);
        List<Span> ordered = new ArrayList<>(spans.values());
        ordered.sort(Comparator.comparingLong(s -> s.startMs == null ? Long.MAX_VALUE : s.startMs));
        JSONArray items = new JSONArray();
        for (Span item : ordered) if (item.complete) {
            if (item.startMs != null && item.startMs > audioDurationMs) continue;
            if (item.endMs != null && item.endMs > audioDurationMs) item.endMs = audioDurationMs;
            items.put(item.json());
        }
        result.put("segments", items);
        result.put("recognized_segments", items.length());
        return result;
    }

    private static String eventId() { return "ev_" + UUID.randomUUID().toString().replace("-", ""); }
    private static int fill(BufferedInputStream in, byte[] out) throws IOException {
        int n = 0;
        while (n < out.length) {
            int t = in.read(out, n, out.length - n);
            if (t < 0) break;
            if (t == 0) continue;
            n += t;
        }
        return n;
    }
}
