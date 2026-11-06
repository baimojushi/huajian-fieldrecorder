package com.huajian.fieldrecorder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** The raw video is always kept locally, OUTSIDE GPT packet ZIPs. */
public final class SessionStore {
    public static final String[] EVENT_TYPES = {
        "自选主题", "情绪表达", "主动求助", "成人提问", "成人引导",
        "画面修改", "停顿思考", "互动回应", "其他事件"
    };
    public static final class Session {
        public final File dir;
        public final JSONObject metadata;
        Session(File dir, JSONObject metadata) { this.dir = dir; this.metadata = metadata; }
        public String id() { return metadata.optString("session_id"); }
        public File rawVideo() { return new File(dir, "source/original.mp4"); }
        public File finalArt() { return new File(dir, "source/final_art.jpg"); }
        public File packetDir() { return new File(dir, "packets"); }
        public long durationMs() { return metadata.optLong("duration_ms", 0); }
    }

    private SessionStore() { }

    public static Session create(Context ctx, String participants, String origin) throws IOException, JSONException {
        JSONArray people = parseParticipants(participants);
        String id = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_" + UUID.randomUUID().toString().substring(0, 6);
        File dir = new File(ctx.getFilesDir(), "sessions/" + id);
        if (!new File(dir, "source").mkdirs()) throw new IOException("无法创建会话目录");
        JSONObject meta = new JSONObject();
        meta.put("schema", "huajian.session.v1.3");
        meta.put("session_id", id);
        meta.put("created_at_unix_ms", System.currentTimeMillis());
        meta.put("participants", people);
        meta.put("origin", origin);
        meta.put("guardian_consent", true);
        meta.put("all_adults_consent", true);
        meta.put("child_assent", true);
        meta.put("no_automatic_upload", true);
        meta.put("duration_ms", 0L);
        writeJson(new File(dir, "session.json"), meta);
        writeJson(new File(dir, "events.json"), new JSONArray());
        return new Session(dir, meta);
    }

    public static JSONArray parseParticipants(String raw) throws JSONException {
        JSONArray out = new JSONArray();
        if (raw == null) throw new JSONException("请填写参与者代号");
        String[] entries = raw.split("[,，\\n]");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (String item : entries) {
            String[] pair = item.trim().split("[:：]", 2);
            if (pair.length != 2) throw new JSONException("参与者格式示例 C1:儿童,A1:成人");
            String id = pair[0].trim().toUpperCase(Locale.US);
            String role = pair[1].trim();
            if (!id.matches("[CA][0-9]{1,3}") || !(role.equals("儿童") || role.equals("成人")))
                throw new JSONException("参与者只接受 C1:儿童 或 A1:成人 等匿名格式");
            if ((id.startsWith("C") && !role.equals("儿童")) || (id.startsWith("A") && !role.equals("成人")))
                throw new JSONException("参与者代号前缀与身份不符");
            if (!ids.add(id)) throw new JSONException("参与者代号重复：" + id);
            JSONObject p = new JSONObject(); p.put("id", id); p.put("role_hint", role); out.put(p);
        }
        if (out.length() == 0) throw new JSONException("至少填写一位参与者");
        return out;
    }

    public static void setVideoReady(Session s, String sourceKind) throws IOException, JSONException {
        File video = s.rawVideo();
        if (!video.isFile() || video.length() < 1024) throw new IOException("没有找到有效的录像文件");
        long duration = readDurationMs(video);
        if (duration <= 0) throw new IOException("视频时长读取失败，请检查文件格式");
        s.metadata.put("origin", sourceKind);
        s.metadata.put("duration_ms", duration);
        s.metadata.put("video_bytes", video.length());
        s.metadata.put("video_file", "source/original.mp4");
        s.metadata.put("ready_at_unix_ms", System.currentTimeMillis());
        writeJson(new File(s.dir, "session.json"), s.metadata);
    }

    public static long readDurationMs(File video) throws IOException {
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(video.getAbsolutePath());
            String d = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? -1L : Long.parseLong(d);
        } catch (Exception e) { throw new IOException("无法读取视频时长", e); }
        finally { mr.release(); }
    }

    public static void addEvent(Session s, String type, long mediaTimeMs, String note, String basis) throws JSONException, IOException {
        if (mediaTimeMs < 0) mediaTimeMs = 0;
        JSONArray events = readJsonArray(new File(s.dir, "events.json"));
        JSONObject e = new JSONObject();
        e.put("event_id", "ev_" + UUID.randomUUID().toString().substring(0, 10));
        e.put("media_time_ms", mediaTimeMs);
        e.put("event_type", type);
        e.put("observer_note", note == null ? "" : note.trim());
        e.put("time_basis", basis);
        e.put("written_at_unix_ms", System.currentTimeMillis());
        e.put("author", "现场观察者");
        events.put(e);
        writeJson(new File(s.dir, "events.json"), events);
    }

    public static JSONArray readEvents(Session s) throws IOException, JSONException {
        return readJsonArray(new File(s.dir, "events.json"));
    }

    public static Session latestReady(Context ctx) {
        File parent = new File(ctx.getFilesDir(), "sessions");
        File[] dirs = parent.listFiles(File::isDirectory);
        if (dirs == null) return null;
        Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
        for (File d : dirs) {
            try {
                File video = new File(d, "source/original.mp4");
                if (video.isFile() && video.length() > 1024) {
                    Session s = new Session(d, readJsonObject(new File(d, "session.json")));
                    if (s.durationMs() <= 0) setVideoReady(s, s.metadata.optString("origin", "recovered"));
                    return s;
                }
            } catch (Exception ignored) { /* Seek older recoverable sessions. */ }
        }
        return null;
    }

    /** Explicit destructive action: removes this local session including raw MP4 and cached ZIPs. */
    public static void deleteSession(Session session) throws IOException {
        if (session == null || !session.dir.exists()) return;
        deleteRecursive(session.dir);
    }
    private static void deleteRecursive(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        if (!file.delete()) throw new IOException("无法删除：" + file.getName());
    }

    public static void copyUri(Context ctx, Uri src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("无法创建文件目录");
        try (InputStream in = ctx.getContentResolver().openInputStream(src)) {
            if (in == null) throw new IOException("无法打开选择的文件");
            try (OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
    }

    public static void copyFile(File src, OutputStream out) throws IOException {
        try (InputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    public static void saveArt(Context ctx, Uri selected, Session s) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(selected)) {
            if (in == null) throw new IOException("无法打开作品图片");
            BitmapFactory.decodeStream(in, null, opts);
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw new IOException("图片无法解码");
        int max = Math.max(opts.outWidth, opts.outHeight);
        opts.inSampleSize = 1;
        while (max / opts.inSampleSize > 2200) opts.inSampleSize *= 2;
        opts.inJustDecodeBounds = false;
        Bitmap b;
        try (InputStream in = ctx.getContentResolver().openInputStream(selected)) {
            if (in == null) throw new IOException("图片读取失败");
            b = BitmapFactory.decodeStream(in, null, opts);
        }
        if (b == null) throw new IOException("图片解码失败");
        Bitmap scaled = b;
        int biggest = Math.max(b.getWidth(), b.getHeight());
        if (biggest > 2000) {
            scaled = Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() * 2000 / biggest),
                    Math.max(1, b.getHeight() * 2000 / biggest), true);
        }
        try (FileOutputStream out = new FileOutputStream(s.finalArt())) {
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)) throw new IOException("保存 JPEG 失败");
        } finally { if (scaled != b) scaled.recycle(); b.recycle(); }
    }

    public static JSONObject readJsonObject(File f) throws IOException, JSONException {
        return new JSONObject(readAll(f));
    }
    public static JSONArray readJsonArray(File f) throws IOException, JSONException {
        return new JSONArray(readAll(f));
    }
    private static String readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    public static void writeJson(File f, JSONObject data) throws IOException, JSONException { writeUtf8(f, data.toString(2)); }
    public static void writeJson(File f, JSONArray data) throws IOException, JSONException { writeUtf8(f, data.toString(2)); }
    private static void writeUtf8(File f, String json) throws IOException {
        File temp = new File(f.getAbsolutePath() + ".new");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!temp.renameTo(f)) { // Some filesystems don't replace existing destinations.
            if (f.exists() && !f.delete()) throw new IOException("无法覆盖：" + f.getName());
            if (!temp.renameTo(f)) throw new IOException("无法完成原子写入：" + f.getName());
        }
    }
}
