package com.huajian.fieldrecorder.asr;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decode only a *copy* of the audio; the source AAC in MP4 remains untouched. */
public final class Pcm16StreamDecoder {
    private static final int OUTPUT_RATE = 16000;
    public interface Progress { void onMessage(String text); }
    public static final class Result {
        public final long samples;
        public final long durationMs;
        public final long decodedFirstPtsMs;
        Result(long samples, long firstPtsMs) {
            this.samples = samples;
            this.durationMs = Math.round(samples * 1000.0 / OUTPUT_RATE);
            this.decodedFirstPtsMs = firstPtsMs;
        }
    }

    public Result decode(File mp4, File pcmOut, Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        long firstPts = -1;
        long samples = 0;
        boolean fedEOS = false, finishedEOS = false;
        int channels = -1, inputRate = -1, encoding = AudioFormat.ENCODING_PCM_16BIT;
        LinearResampler resampler = null;
        try {
            extractor.setDataSource(mp4.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; format = candidate; break; }
            }
            if (track < 0 || format == null) throw new IOException("录像中没有音轨，无法识别语音");
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            pcmOut.getParentFile().mkdirs();
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(pcmOut), 65536)) {
                int idle = 0;
                while (!finishedEOS) {
                    if (!fedEOS) {
                        int index = codec.dequeueInputBuffer(10000);
                        if (index >= 0) {
                            ByteBuffer in = codec.getInputBuffer(index);
                            if (in == null) throw new IOException("音频解码器没有输入缓存");
                            in.clear();
                            int n = extractor.readSampleData(in, 0);
                            if (n < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                fedEOS = true;
                            } else {
                                codec.queueInputBuffer(index, 0, n, Math.max(0, extractor.getSampleTime()), 0);
                                extractor.advance();
                            }
                        }
                    }
                    int index = codec.dequeueOutputBuffer(info, 10000);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat output = codec.getOutputFormat();
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        inputRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (channels <= 0 || channels > 8 || inputRate < 8000 || inputRate > 192000)
                            throw new IOException("不支持解码器输出采样率或声道数量");
                        if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                        if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)
                            throw new IOException("当前设备解码 PCM 格式不支持：" + encoding);
                        resampler = new LinearResampler(inputRate, OUTPUT_RATE);
                    } else if (index >= 0) {
                        if (info.size > 0) {
                            if (resampler == null) {
                                MediaFormat output = codec.getOutputFormat();
                                channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                                inputRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                                if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                    encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                                if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)
                                    throw new IOException("解码器输出不是 16-bit 或 float PCM");
                                resampler = new LinearResampler(inputRate, OUTPUT_RATE);
                            }
                            if (firstPts < 0) {
                                firstPts = Math.max(0, info.presentationTimeUs / 1000);
                                // Preserve the source audio's leading delay on the video timeline.
                                long leading = Math.max(0, Math.round(info.presentationTimeUs * OUTPUT_RATE / 1_000_000.0));
                                for (long k = 0; k < leading; k++) { out.write(0); out.write(0); }
                                samples += leading;
                            }
                            ByteBuffer buffer = codec.getOutputBuffer(index);
                            if (buffer == null) throw new IOException("音频解码器没有输出缓存");
                            buffer.position(info.offset); buffer.limit(info.offset + info.size);
                            ByteBuffer frame = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
                            int bytesPerSample = (encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2);
                            int frameSize = channels * bytesPerSample;
                            int frames = frame.remaining() / frameSize;
                            for (int i = 0; i < frames; i++) {
                                float mono = 0;
                                for (int c = 0; c < channels; c++) {
                                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) mono += frame.getFloat();
                                    else mono += frame.getShort() / 32768f;
                                }
                                mono /= channels;
                                samples += resampler.push(mono, out);
                            }
                        }
                        finishedEOS = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        codec.releaseOutputBuffer(index, false);
                        idle = 0;
                    } else if (++idle > 6000) {
                        throw new IOException("音轨解码超时，请重新导入视频");
                    }
                }
            }
        } catch (Exception ex) {
            if (pcmOut.exists()) pcmOut.delete();
            throw ex;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            extractor.release();
        }
        if (samples < OUTPUT_RATE / 10) throw new IOException("解码后有效音频过短");
        progress.onMessage("转码完成：" + (samples / OUTPUT_RATE) + " 秒音频 · 不修改原始 AAC");
        return new Result(samples, Math.max(0, firstPts));
    }

    /** Interpolate at true sample-clock positions; phase is retained across decoder buffers. */
    static final class LinearResampler {
        private final int inRate, outRate;
        private long inputIndex = -1, outputIndex = 0;
        private float previous;
        LinearResampler(int inRate, int outRate) {
            this.inRate = inRate;
            this.outRate = outRate;
        }
        int push(float current, BufferedOutputStream out) throws IOException {
            inputIndex++;
            int written = 0;
            if (inputIndex == 0) {
                previous = current;
                return 0;
            }
            while (outputIndex * (long)inRate <= inputIndex * (long)outRate) {
                double exactPosition = outputIndex * (double)inRate / outRate;
                double fraction = exactPosition - (inputIndex - 1);
                float value = (float)(previous + (current - previous) * fraction);
                int pcm = Math.max(-32768, Math.min(32767, Math.round(value * 32767)));
                out.write(pcm & 0xff);
                out.write((pcm >> 8) & 0xff);
                written++;
                outputIndex++;
            }
            previous = current;
            return written;
        }
    }
}
