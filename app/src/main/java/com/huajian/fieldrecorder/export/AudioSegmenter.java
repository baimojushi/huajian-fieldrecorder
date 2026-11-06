package com.huajian.fieldrecorder.export;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** AAC samples are copied unchanged. Only the M4A container and local segment PTS are rewritten. */
public final class AudioSegmenter {
    private static final long SEGMENT_DURATION_US = 120_000_000L;
    private static final long MAX_ENCODED_PAYLOAD = 23_000_000L; // Target about 24 MB per M4A.

    public static final class Result {
        public final List<MediaAsset> audio = new ArrayList<>();
        public boolean hasAudio;
        public String warning = "";
    }

    public Result extract(File source, File destDir) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) throw new IOException("无法创建音频工作目录");
        MediaExtractor ex = new MediaExtractor();
        Result result = new Result();
        try {
            ex.setDataSource(source.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { audioTrack = i; format = f; break; }
            }
            if (audioTrack < 0) {
                result.warning = "源视频没有可识别的音轨；不会编造语音内容。";
                return result;
            }
            String mime = format.getString(MediaFormat.KEY_MIME);
            // M4A remux without transcoding is reliable for AAC, not for arbitrary source formats.
            if (!"audio/mp4a-latm".equals(mime)) {
                throw new IOException("原始音轨为 " + mime + "，无法无损封装为 M4A。请改用 AAC/MP4 录像后再导入；本程序不会静默重编码。");
            }
            result.hasAudio = true;
            ex.selectTrack(audioTrack);
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            long sampleDurationUs = Math.max(1, 1024_000_000L / Math.max(1, sampleRate));
            int capacity = 2 * 1024 * 1024;
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) capacity = Math.max(capacity, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) + 1024);
            ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
            SegmentWriter writer = null;
            int index = 0;
            for (;;) {
                long samplePtUs = ex.getSampleTime();
                if (samplePtUs < 0) break;
                long sampleBytes = ex.getSampleSize();
                if (sampleBytes > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int)Math.min(Integer.MAX_VALUE, sampleBytes + 1024L));
                buffer.clear();
                int len = ex.readSampleData(buffer, 0);
                if (len < 0) break;
                if (writer != null && writer.samples > 0 &&
                    ((samplePtUs - writer.basePtUs >= SEGMENT_DURATION_US) ||
                     (writer.rawBytes + len > MAX_ENCODED_PAYLOAD))) {
                    result.audio.add(writer.finish(sampleDurationUs));
                    writer = null;
                }
                if (writer == null) {
                    index++;
                    String name = String.format(Locale.US, "audio_%04d.m4a", index);
                    File path = new File(destDir, name);
                    writer = new SegmentWriter(path, "MEDIA/audio/" + name, format, samplePtUs);
                }
                int flags = ((ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                writer.write(buffer, len, flags, samplePtUs);
                if (!ex.advance()) break;
            }
            if (writer != null) result.audio.add(writer.finish(sampleDurationUs));
            if (result.audio.isEmpty()) result.warning = "发现音轨，但没有提取出有效的 AAC 样本。";
            return result;
        } catch (RuntimeException e) { throw new IOException("音轨封装失败：" + e.getMessage(), e); }
        finally { ex.release(); }
    }

    private static final class SegmentWriter {
        private final File file;
        private final String zipPath;
        private final MediaMuxer muxer;
        private final int track;
        final long basePtUs;
        long lastPtUs;
        long rawBytes;
        long samples;
        boolean started;

        SegmentWriter(File file, String zipPath, MediaFormat format, long basePtUs) throws IOException {
            this.file = file; this.zipPath = zipPath; this.basePtUs = basePtUs;
            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            track = muxer.addTrack(format);
            muxer.start(); started = true;
            lastPtUs = basePtUs;
        }
        void write(ByteBuffer buf, int n, int flags, long srcPtUs) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.set(0, n, Math.max(0, srcPtUs - basePtUs), flags);
            buf.position(0); buf.limit(n);
            muxer.writeSampleData(track, buf, info);
            lastPtUs = srcPtUs;
            rawBytes += n;
            samples++;
        }
        MediaAsset finish(long sampleDurationUs) throws IOException {
            try {
                if (started) muxer.stop();
            } catch (Exception e) { throw new IOException("结束音频封装失败", e); }
            finally { muxer.release(); started = false; }
            if (file.length() <= 0) throw new IOException("M4A 输出为空");
            MediaAsset out = new MediaAsset(file, zipPath, "audio_aac_remux",
                basePtUs / 1000L, (lastPtUs + sampleDurationUs) / 1000L);
            out.timingNote = "Original AAC encoded samples; segment M4A playback PTS restarts at zero. Map to global time via start_ms.";
            return out;
        }
    }
}
