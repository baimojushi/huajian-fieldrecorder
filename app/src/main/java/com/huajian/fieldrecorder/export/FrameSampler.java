package com.huajian.fieldrecorder.export;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** A sampled video is evidence, not a continuous reconstruction of the source video. */
public final class FrameSampler {
    public static final long DEFAULT_INTERVAL_MS = 3_000L;

    public List<MediaAsset> sample(File source, long durationMs, JSONArray events,
                                   File framesDir, File sheetsDir) throws Exception {
        if (!framesDir.exists() && !framesDir.mkdirs()) throw new IOException("无法创建抽帧目录");
        if (!sheetsDir.exists() && !sheetsDir.mkdirs()) throw new IOException("无法创建总览图目录");
        TreeSet<Long> wanted = new TreeSet<>();
        for (long t = 0; t < durationMs; t += DEFAULT_INTERVAL_MS) wanted.add(t);
        wanted.add(Math.max(0L, durationMs - 200L));
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            long t = event.optLong("media_time_ms");
            for (long delta : new long[]{-1000L, 0L, 1000L}) {
                long candidate = Math.max(0L, Math.min(Math.max(0L, durationMs - 200L), t + delta));
                wanted.add(candidate);
            }
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        List<MediaAsset> out = new ArrayList<>();
        List<MediaAsset> frameOnly = new ArrayList<>();
        try {
            retriever.setDataSource(source.getAbsolutePath());
            int sourceWidth = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 1280);
            int sourceHeight = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 720);
            int rotate = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            if (rotate == 90 || rotate == 270) { int temp = sourceWidth; sourceWidth = sourceHeight; sourceHeight = temp; }
            int mx = Math.max(sourceWidth, sourceHeight);
            int dstWidth = Math.max(1, sourceWidth * 1280 / Math.max(1280, mx));
            int dstHeight = Math.max(1, sourceHeight * 1280 / Math.max(1280, mx));
            int count = 0;
            for (Long t : wanted) {
                Bitmap bitmap;
                if (Build.VERSION.SDK_INT >= 27) {
                    bitmap = retriever.getScaledFrameAtTime(t * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST, dstWidth, dstHeight);
                } else {
                    Bitmap b = retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (b != null && Math.max(b.getWidth(), b.getHeight()) > 1280) {
                        int maxEdge = Math.max(b.getWidth(), b.getHeight());
                        bitmap = Bitmap.createScaledBitmap(b, b.getWidth() * 1280 / maxEdge,
                            b.getHeight() * 1280 / maxEdge, true);
                        b.recycle();
                    } else bitmap = b;
                }
                if (bitmap == null) continue;
                String name = String.format(Locale.US, "frame_%05d_%010d.jpg", ++count, t);
                File file = new File(framesDir, name);
                try (FileOutputStream output = new FileOutputStream(file)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 76, output)) throw new IOException("抽帧写入失败");
                } finally { bitmap.recycle(); }
                MediaAsset asset = new MediaAsset(file, "MEDIA/frames/" + name, "frame_requested",
                    t, t);
                asset.timingNote = "Requested frame time. MediaMetadataRetriever may return a nearby decoded frame; exact frame PTS is not measured.";
                frameOnly.add(asset);
                out.add(asset);
            }
            if (frameOnly.isEmpty()) throw new IOException("视频无法解码出图像帧；导出已停止，避免空白报告。");
            for (int i = 0; i < frameOnly.size(); i += 16) {
                List<MediaAsset> group = frameOnly.subList(i, Math.min(frameOnly.size(), i + 16));
                int sheetNum = i / 16 + 1;
                String name = String.format(Locale.US, "sheet_%04d.jpg", sheetNum);
                File path = new File(sheetsDir, name);
                drawSheet(path, group);
                MediaAsset sheet = new MediaAsset(path, "MEDIA/sheets/" + name, "contact_sheet",
                    group.get(0).startMs, group.get(group.size() - 1).startMs);
                sheet.timingNote = "Overview thumbnails; for inspection only. See individual frames for detail.";
                out.add(sheet);
            }
            return out;
        } finally { retriever.release(); }
    }

    private static int parseInt(String s, int fallback) {
        try { return s == null ? fallback : Integer.parseInt(s); }
        catch (NumberFormatException e) { return fallback; }
    }
    private static String timestamp(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
    private static void drawSheet(File output, List<MediaAsset> frames) throws IOException {
        final int cw = 300, ch = 208, margin = 8, labelHeight = 34;
        Bitmap sheet = Bitmap.createBitmap(cw * 4, ch * 4, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(sheet);
        canvas.drawColor(Color.rgb(248, 244, 237));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.rgb(22, 60, 59));
        labelPaint.setTextSize(23f);
        for (int i = 0; i < frames.size(); i++) {
            int x = (i % 4) * cw, y = (i / 4) * ch;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 3;
            Bitmap thumb = BitmapFactory.decodeFile(frames.get(i).file.getAbsolutePath(), options);
            if (thumb == null) continue;
            try {
                Rect src = centerCropRect(thumb.getWidth(), thumb.getHeight(), cw - 2 * margin, ch - labelHeight - 2 * margin);
                RectF dest = new RectF(x + margin, y + margin,
                    x + cw - margin, y + ch - labelHeight - margin);
                canvas.drawBitmap(thumb, src, dest, paint);
                canvas.drawText(timestamp(frames.get(i).startMs), x + margin + 5,
                    y + ch - 11, labelPaint);
            } finally { thumb.recycle(); }
        }
        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (!sheet.compress(Bitmap.CompressFormat.JPEG, 78, stream)) throw new IOException("总览图保存失败");
        } finally { sheet.recycle(); }
    }
    private static Rect centerCropRect(int w, int h, int dstW, int dstH) {
        float desired = (float) dstW / Math.max(1, dstH);
        float have = (float) w / Math.max(1, h);
        if (have > desired) { int cropW = Math.max(1, Math.round(h * desired)); int start = (w - cropW) / 2; return new Rect(start, 0, start + cropW, h); }
        int cropH = Math.max(1, Math.round(w / desired)); int start = (h - cropH) / 2;
        return new Rect(0, start, w, start + cropH);
    }
}
