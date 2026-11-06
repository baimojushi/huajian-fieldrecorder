package com.huajian.fieldrecorder.export;

import com.huajian.fieldrecorder.core.Hashing;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;

/** Files must be immutable after calculating hashes. */
public final class MediaAsset {
    public final File file;
    public final String zipPath;
    public final String type;
    public final long startMs;
    public final long endMs;
    public String sha256;
    public long bytes;
    public String timingNote = "";

    public MediaAsset(File file, String zipPath, String type, long startMs, long endMs) {
        this.file = file; this.zipPath = zipPath; this.type = type;
        this.startMs = startMs; this.endMs = endMs;
    }
    public MediaAsset scan() throws IOException {
        if (!file.isFile()) throw new IOException("媒体文件丢失：" + file.getName());
        bytes = file.length();
        sha256 = Hashing.sha256(file);
        return this;
    }
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("path", zipPath);
        o.put("type", type);
        o.put("start_ms", startMs);
        o.put("end_ms", endMs);
        o.put("bytes", bytes);
        o.put("sha256", sha256);
        if (!timingNote.isEmpty()) o.put("timing_note", timingNote);
        return o;
    }
}
