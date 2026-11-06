package com.huajian.fieldrecorder.export;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import com.huajian.fieldrecorder.SessionStore;
import com.huajian.fieldrecorder.core.Hashing;
import com.huajian.fieldrecorder.core.ZipPartitioner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** One independent ZIP per partition, hard-validated under 400,000,000 bytes. */
public final class PacketExporter {
    public interface Progress { void update(String description); }
    public static final class Result {
        public final int parts;
        public final File indexFile;
        Result(int parts, File indexFile) { this.parts = parts; this.indexFile = indexFile; }
    }
    private static final String PROMPT_ASSET = "GPT6_PROTOCOL.md";
    private static final String VIEWER_ASSET = "viewer.html";
    private static final String README = "画间 / GPT-6 观察资料包 v1.3\n\n" +
        "1. 按 PART_001_OF_NNN、PART_002_OF_NNN 顺序收齐全部 ZIP；缺包不要分析。\n" +
        "2. 先看 MANIFEST/global_index.json 与 part_manifest.json，确认时段与 SHA-256。\n" +
        "3. 音频位于 MEDIA/audio/，每段 AAC 原始编码样本封装为 M4A；视频抽帧位于 MEDIA/frames/，16帧总览位于 MEDIA/sheets/。\n" +
        "4. 原始 MP4 只保存在手机本地，不包含在 GPT 包。音画同步由 TIMELINE/part_media.json 的 start_ms / end_ms 与 VIEWER/index.html 实现。\n" +
        "5. 上传 ZIP 不代表模型能够直接解码 ZIP 内媒体。必要时解压，单独提交 M4A、JPG 和 JSON，并附 GPT_HANDOFF/01_GPT6_PROMPT.md。\n" +
        "6. SPEECH/ 目录包含本次成功的 Qwen3 段落/声音情绪标签；若未执行或调用失败则明确缺失。\n" +
        "7. 说话者 unknown 不能等同儿童/成人；算法情绪标签不是心理诊断，须核听原声。\n";

    public Result export(Context ctx, SessionStore.Session session, Uri folderUri,
                         Progress progress) throws Exception {
        File source = session.rawVideo();
        if (!source.isFile()) throw new IOException("原始视频不存在，请先录制或导入");
        long durationMs = session.durationMs();
        if (durationMs <= 0) throw new IOException("没有有效时长，停止导出");
        File work = new File(session.dir, "work");
        File audioDir = new File(work, "audio");
        File frameDir = new File(work, "frames");
        File sheetDir = new File(work, "sheets");
        resetDir(work);
        progress.update("阶段 1/5 · 正在从原视频直接提取 AAC 音频（不重编码）…");
        AudioSegmenter.Result audio = new AudioSegmenter().extract(source, audioDir);
        JSONArray events = SessionStore.readEvents(session);
        File asrFile = new File(session.dir, "analysis/qwen3_segments.json");
        JSONObject asrData = asrFile.isFile() ? SessionStore.readJsonObject(asrFile) : null;
        boolean asrComplete = asrData != null && "completed".equals(asrData.optString("status"));
        File fusedFile = new File(session.dir, "analysis/fused_timeline.json");
        JSONObject fusedData = (asrComplete && fusedFile.isFile())
                ? SessionStore.readJsonObject(fusedFile) : null;
        progress.update("阶段 2/5 · 按 3 秒间隔与事件时刻抽帧（较长视频会耗时）…");
        List<MediaAsset> assets = new ArrayList<>(audio.audio);
        assets.addAll(new FrameSampler().sample(source, durationMs, events, frameDir, sheetDir));
        if (session.finalArt().isFile()) {
            MediaAsset artwork = new MediaAsset(session.finalArt(), "MEDIA/final_art.jpg", "final_art", durationMs, durationMs);
            artwork.timingNote = "Optional final artwork photo chosen by a human; not inferred from frames.";
            assets.add(artwork);
        }
        assets.sort(Comparator.comparingLong((MediaAsset a) -> a.startMs)
            .thenComparingInt(a -> a.type.startsWith("audio") ? 0 : 1).thenComparing(a -> a.zipPath));
        progress.update("阶段 3/5 · 正在计算每个媒体文件的 SHA-256 与 380 MB 容量计划…");
        List<ZipPartitioner.Item> weight = new ArrayList<>();
        Map<String,MediaAsset> lookup = new HashMap<>();
        for (MediaAsset a : assets) {
            a.scan();
            weight.add(new ZipPartitioner.Item(a.zipPath, a.bytes, a.startMs));
            lookup.put(a.zipPath, a);
        }
        List<List<ZipPartitioner.Item>> planned = ZipPartitioner.plan(weight);
        List<List<MediaAsset>> parts = new ArrayList<>();
        for (List<ZipPartitioner.Item> group : planned) {
            List<MediaAsset> resolved = new ArrayList<>();
            for (ZipPartitioner.Item item : group) resolved.add(lookup.get(item.path));
            parts.add(resolved);
        }
        String prefix = session.id() + "_GPT6_";
        JSONObject global = new JSONObject();
        global.put("schema", "huajian.gpt6.global.v1.3");
        global.put("session_id", session.id());
        global.put("expected_parts", parts.size());
        global.put("qwen3_asr_complete", asrComplete);
        global.put("qwen3_emotion_included", asrComplete);
        global.put("speaker_fusion_included", fusedData != null);
        global.put("cloud_audio_opt_in_required", true);
        global.put("api_credentials_in_packet", false);
        global.put("duration_ms", durationMs);
        global.put("audio_present", audio.hasAudio && !audio.audio.isEmpty());
        global.put("audio_warning", audio.warning);
        global.put("frame_sampling_interval_ms", FrameSampler.DEFAULT_INTERVAL_MS);
        global.put("packet_limit_bytes_strict_less_than", ZipPartitioner.ABSOLUTE_LIMIT_BYTES);
        global.put("source_original_mp4_not_in_packet", true);
        global.put("audio_source_policy", "AAC encoded packets copied without decoding/resampling; M4A PTS reset per segment. Time axis maps segment start_ms to source media time.");
        global.put("frame_source_policy", "Frames represent requested timestamps, not guaranteed exact decoded source PTS.");
        JSONArray plannedIndex = new JSONArray();
        for (int i = 0; i < parts.size(); i++) {
            List<MediaAsset> p = parts.get(i);
            JSONObject summary = new JSONObject();
            summary.put("part_number", i + 1);
            summary.put("filename", partName(prefix, i + 1, parts.size()));
            summary.put("start_ms", minStart(p));
            summary.put("end_ms", maxEnd(p));
            summary.put("assets", p.size());
            summary.put("has_audio", containsAudio(p));
            summary.put("has_final_art", containsArt(p));
            plannedIndex.put(summary);
        }
        global.put("parts", plannedIndex);
        String sourceHash = Hashing.sha256(source);
        global.put("source_video_sha256", sourceHash);
        global.put("source_video_bytes", source.length());
        File packetDir = session.packetDir();
        if (!packetDir.exists() && !packetDir.mkdirs()) throw new IOException("无法创建本机分包目录");
        String protocol = readAsset(ctx, PROMPT_ASSET);
        String viewerTemplate = readAsset(ctx, VIEWER_ASSET);
        JSONArray hashes = new JSONArray();
        List<File> completed = new ArrayList<>();
        progress.update("阶段 4/5 · 正在生成独立 ZIP、时间轴与离线音画播放器…");
        for (int i = 0; i < parts.size(); i++) {
            List<MediaAsset> assigned = parts.get(i);
            String fileName = partName(prefix, i + 1, parts.size());
            File target = new File(packetDir, fileName);
            File temporary = new File(packetDir, fileName + ".partial");
            if (temporary.exists() && !temporary.delete()) throw new IOException("无法清理部分 ZIP");
            JSONObject partManifest = makePartManifest(session, i + 1, parts.size(), assigned, global);
            JSONArray partEvents = selectEvents(events, minStart(assigned), maxEnd(assigned));
            JSONArray itemJson = new JSONArray();
            for (MediaAsset asset : assigned) itemJson.put(asset.toJson());
            JSONObject timeData = new JSONObject();
            timeData.put("schema", "huajian.media_timeline.v1.3");
            timeData.put("source_media_duration_ms", durationMs);
            timeData.put("part_start_ms", minStart(assigned));
            timeData.put("part_end_ms", maxEnd(assigned));
            timeData.put("media", itemJson);
            timeData.put("events", partEvents);
            timeData.put("audio_warning", audio.warning);
            JSONArray asrSegments = asrComplete ? selectSpeech(asrData.optJSONArray("segments"),
                    minStart(assigned), maxEnd(assigned)) : new JSONArray();
            JSONArray fusedSegments = fusedData != null ? selectSpeech(fusedData.optJSONArray("segments"),
                    minStart(assigned), maxEnd(assigned)) : new JSONArray();
            timeData.put("speech_segments", fusedData != null ? fusedSegments : asrSegments);
            timeData.put("qwen3_asr_complete", asrComplete);
            timeData.put("speech_emotions_are_model_labels_not_diagnoses", true);
            String viewerJson = timeData.toString().replace("<", "\\u003c")
                .replace("&", "\\u0026").replace("\u2028", "\\u2028");
            String html = viewerTemplate.replace("__TIMELINE_JSON__", viewerJson);
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(temporary))) {
                // AAC / JPEG already use compression: avoid wasting CPU re-encoding bytes in the ZIP.
                zip.setLevel(Deflater.NO_COMPRESSION);
                putText(zip, "README_FIRST.txt", README);
                putText(zip, "MANIFEST/global_index.json", global.toString(2));
                putText(zip, "MANIFEST/part_manifest.json", partManifest.toString(2));
                putText(zip, "MANIFEST/source_session.json", session.metadata.toString(2));
                putText(zip, "TIMELINE/part_media.json", timeData.toString(2));
                putText(zip, "TIMELINE/events_all.json", events.toString(2));
                if (asrComplete) {
                    JSONObject speech = new JSONObject(asrData.toString());
                    speech.put("segments", asrSegments);
                    speech.put("packets_contain_partition_subset", true);
                    speech.put("raw_websocket_log_not_in_packet", true);
                    putText(zip, "SPEECH/qwen3_asr_emotion.json", speech.toString(2));
                }
                if (fusedData != null) {
                    JSONObject speech = new JSONObject(fusedData.toString());
                    speech.put("segments", fusedSegments);
                    speech.put("packets_contain_partition_subset", true);
                    putText(zip, "SPEECH/speaker_fusion.json", speech.toString(2));
                }
                putText(zip, "SPEECH/README.txt",
                    "Qwen3 ASR Flash Realtime: one optional cloud recognition call performed AFTER recording.\n"
                    + "Output has VAD segment times, NOT word timestamps.\n"
                    + "Emotion labels are acoustic hypotheses; no confidence is supplied.\n"
                    + "Diarization imported from local file only if provided; S1/S2 are not ages or identities.\n"
                    + "API key is encrypted locally and never included.\n");
                putText(zip, "GPT_HANDOFF/01_GPT6_PROMPT.md", protocol);
                putText(zip, "VIEWER/index.html", html);
                for (MediaAsset asset : assigned) putFile(zip, asset.zipPath, asset.file);
            }
            validateZip(temporary);
            if (temporary.length() >= ZipPartitioner.ABSOLUTE_LIMIT_BYTES)
                throw new IOException("安全检查：单 ZIP 达到 400 MB，拒绝输出。请减少事件帧密度重新导出。文件：" + fileName);
            if (target.exists() && !target.delete()) throw new IOException("无法替换旧分包 " + fileName);
            if (!temporary.renameTo(target)) throw new IOException("无法完成分包提交 " + fileName);
            String hash = Hashing.sha256(target);
            JSONObject line = new JSONObject();
            line.put("filename", target.getName());
            line.put("sha256", hash);
            line.put("bytes", target.length());
            line.put("part_number", i + 1);
            hashes.put(line);
            completed.add(target);
            progress.update("阶段 4/5 · 已完成 " + (i + 1) + "/" + parts.size() + " 包；全部小于 400 MB");
        }
        JSONObject index = new JSONObject();
        index.put("schema", "huajian.gpt6.export_index.v1.3");
        index.put("session_id", session.id());
        index.put("parts", hashes);
        index.put("expected_parts", parts.size());
        index.put("global_index", global);
        index.put("instructions", "Use tools/verify_packets.py from the project to check all ZIPs before uploading.");
        File indexFile = new File(packetDir, session.id() + "_PACKET_INDEX.json");
        try (FileOutputStream out = new FileOutputStream(indexFile)) {
            out.write(index.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        progress.update("阶段 5/5 · 正在保存到所选文件夹并比对每份 ZIP 的 SHA-256…");
        DocumentFile folder = DocumentFile.fromTreeUri(ctx, folderUri);
        if (folder == null || !folder.isDirectory() || !folder.canWrite())
            throw new IOException("所选文件夹不可写，请换一个系统文件夹");
        for (int i = 0; i < completed.size(); i++) {
            File f = completed.get(i);
            copyDocumentVerified(ctx, folder, f, "application/zip");
            progress.update("阶段 5/5 · 已保存并验证 " + (i + 1) + "/" + completed.size() + " ZIP");
        }
        copyDocumentVerified(ctx, folder, indexFile, "application/json");
        progress.update("完成：全部 " + completed.size() + " 个 ZIP 已保存、大小检查及 SHA-256 校验通过。");
        return new Result(completed.size(), indexFile);
    }

    private static JSONObject makePartManifest(SessionStore.Session session, int n, int total,
                                               List<MediaAsset> assigned, JSONObject global) throws Exception {
        JSONObject p = new JSONObject();
        p.put("schema", "huajian.gpt6.part.v1.3");
        p.put("session_id", session.id());
        p.put("part_number", n);
        p.put("total_parts", total);
        p.put("start_ms", minStart(assigned));
        p.put("end_ms", maxEnd(assigned));
        p.put("source_media_duration_ms", session.durationMs());
        p.put("source_video_sha256", global.getString("source_video_sha256"));
        p.put("time_zero", "start of source MP4 media timeline");
        p.put("encoded_audio_passthrough", true);
        p.put("video_reencoded_or_included", false);
        p.put("speaker_identity_is_not_inferred_from_emotion", true);
        p.put("speaker_model_included_in_base_apk", false);
        JSONArray list = new JSONArray();
        for (MediaAsset asset : assigned) list.put(asset.toJson());
        p.put("assets", list);
        return p;
    }
    private static JSONArray selectSpeech(JSONArray input, long min, long max) throws Exception {
        JSONArray result = new JSONArray();
        if (input == null) return result;
        for (int i = 0; i < input.length(); i++) {
            JSONObject segment = input.optJSONObject(i);
            if (segment == null) continue;
            long start = segment.optLong("start_ms", -1);
            long end = segment.optLong("end_ms", -1);
            if (start >= 0 && (end < 0 || end >= min) && start <= max)
                result.put(segment);
            else if (start < 0 && min == 0) result.put(segment);
        }
        return result;
    }
    private static JSONArray selectEvents(JSONArray events, long min, long max) throws Exception {
        JSONArray selected = new JSONArray();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            long ms = e.optLong("media_time_ms");
            if (ms >= min && ms <= max) selected.put(e);
        }
        return selected;
    }
    private static long minStart(List<MediaAsset> p) {
        long min = Long.MAX_VALUE;
        for (MediaAsset a : p) min = Math.min(min, a.startMs);
        return min == Long.MAX_VALUE ? 0 : min;
    }
    private static long maxEnd(List<MediaAsset> p) {
        long max = 0;
        for (MediaAsset a : p) max = Math.max(max, a.endMs);
        return max;
    }
    private static boolean containsAudio(List<MediaAsset> p) {
        for (MediaAsset a : p) if (a.type.startsWith("audio")) return true;
        return false;
    }
    private static boolean containsArt(List<MediaAsset> p) {
        for (MediaAsset a : p) if (a.type.equals("final_art")) return true;
        return false;
    }
    private static String partName(String prefix, int num, int count) {
        return prefix + String.format(Locale.US, "PART_%03d_OF_%03d.zip", num, count);
    }
    private static void putText(ZipOutputStream out, String path, String content) throws IOException {
        ZipEntry e = new ZipEntry(path);
        out.putNextEntry(e);
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
    private static void putFile(ZipOutputStream out, String path, File file) throws IOException {
        out.putNextEntry(new ZipEntry(path));
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        out.closeEntry();
    }
    private static String readAsset(Context ctx, String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) output.write(buf, 0, n);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static void validateZip(File f) throws IOException {
        try (ZipFile zip = new ZipFile(f)) {
            java.util.Enumeration<? extends ZipEntry> e = zip.entries();
            byte[] buf = new byte[65536];
            while (e.hasMoreElements()) {
                ZipEntry item = e.nextElement();
                try (InputStream in = zip.getInputStream(item)) {
                    while (in.read(buf) != -1) { /* Force CRC verification. */ }
                }
            }
        }
    }
    private static void copyDocumentVerified(Context ctx, DocumentFile folder, File local, String mime) throws IOException {
        DocumentFile old = folder.findFile(local.getName());
        if (old != null && !old.delete()) throw new IOException("目标文件夹中同名文件不能覆盖：" + local.getName());
        DocumentFile doc = folder.createFile(mime, local.getName());
        if (doc == null) throw new IOException("所选文件夹无法创建：" + local.getName());
        try {
            try (OutputStream output = ctx.getContentResolver().openOutputStream(doc.getUri(), "w")) {
                if (output == null) throw new IOException("无法写入目标文件：" + local.getName());
                SessionStore.copyFile(local, output);
            }
            try (InputStream reRead = ctx.getContentResolver().openInputStream(doc.getUri())) {
                if (reRead == null || !Hashing.sha256(reRead).equals(Hashing.sha256(local)))
                    throw new IOException("保存后校验值不一致：" + local.getName());
            }
        } catch (IOException err) { doc.delete(); throw err; }
    }
    private static void resetDir(File directory) throws IOException {
        if (directory.exists()) deleteRecursive(directory);
        if (!directory.mkdirs()) throw new IOException("无法清理导出缓存工作区");
    }
    private static void deleteRecursive(File root) throws IOException {
        File[] content = root.listFiles();
        if (content != null) for (File f : content) deleteRecursive(f);
        if (!root.delete()) throw new IOException("无法删除旧工作文件：" + root.getName());
    }
}
