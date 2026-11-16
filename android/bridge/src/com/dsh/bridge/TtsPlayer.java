package com.dsh.bridge;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * 自己解码 MP3 → 重采样到 48kHz → AudioTrack 直接推。
 *
 * 为什么不用 MediaPlayer：
 *   1. MediaPlayer 会受系统混音器当前输出格式影响，
 *      我们的静音音轨一旦是非 48k，24k 的 TTS 就会被重采样，声音发闷、音色变化。
 *   2. MediaPlayer 对 32 位 WAV 支持很差（接口恰好返回 32 位）。
 * 自己解码 + 统一 48kHz 可以完全掌控。
 */
public class TtsPlayer {

    private static final int OUT_RATE = 0;   // 0 = 用源采样率，不重采样

    /** 播放 MP3 字节流，阻塞到播放结束。成功返回 true */
    public static boolean play(Context ctx, byte[] mp3) {
        File f = null;
        try {
            f = new File(ctx.getCacheDir(), "tts_src.mp3");
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(mp3); }

            short[] pcm;
            int srcRate;
            int srcCh;
            byte[] raw = decodeToPcm(f);
            if (raw == null || raw.length < 2) {
                BridgeService.log("解码失败，回退到 MediaPlayer");
                return false;
            }
            // 解码得到的格式由 decodeToPcm 通过静态字段回传
            srcRate = lastSampleRate;
            srcCh = lastChannels;
            pcm = bytesToShorts(raw);

            short[] out = toMono(pcm, srcRate, srcCh);
            int outRate = srcRate;
            try {
                android.media.AudioManager am = (android.media.AudioManager)
                        ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    BridgeService.log("系统输出采样率 "
                            + am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                            + "Hz，缓冲 "
                            + am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
                }
            } catch (Throwable ignored) { }
            BridgeService.log("PCM " + pcm.length + " 样本 @" + srcRate + "Hz/"
                    + srcCh + "ch → 单声道 " + out.length + " 样本");

            playPcm(out, outRate);
            return true;
        } catch (Throwable t) {
            BridgeService.log("TtsPlayer 异常：" + t);
            return false;
        } finally {
            if (f != null) f.delete();
        }
    }

    // ---------- 解码 ----------

    private static volatile int lastSampleRate = 24000;
    private static volatile int lastChannels = 1;

    private static byte[] decodeToPcm(File f) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(f.getAbsolutePath());
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat m = ex.getTrackFormat(i);
            String mime = m.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track = i;
                fmt = m;
                break;
            }
        }
        if (track < 0) { ex.release(); return null; }
        ex.selectTrack(track);

        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEos = false, sawOutputEos = false;
        int rate = 24000, ch = 1;

        while (!sawOutputEos) {
            if (!sawInputEos) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(inIdx);
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEos = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int outIdx = codec.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                ByteBuffer ob = codec.getOutputBuffer(outIdx);
                if (ob != null && info.size > 0) {
                    byte[] chunk = new byte[info.size];
                    ob.position(info.offset);
                    ob.limit(info.offset + info.size);
                    ob.get(chunk);
                    out.write(chunk);
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEos = true;
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
        }
        codec.stop();
        codec.release();
        ex.release();
        lastSampleRate = rate;
        lastChannels = ch;
        return out.toByteArray();
    }

    private static short[] bytesToShorts(byte[] b) {
        ShortBuffer sb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] s = new short[sb.remaining()];
        sb.get(s);
        return s;
    }

    // ---------- 重采样 ----------

    /** 只做声道归并，不改变采样率（避免任何重采样） */
    private static short[] toMono(short[] in, int srcRate, int srcCh) {
        if (srcCh <= 1) return in;
        int frames = in.length / srcCh;
        short[] out = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < srcCh; c++) sum += in[i * srcCh + c];
            out[i] = (short) (sum / srcCh);
        }
        return out;
    }

    // ---------- 播放 ----------

    /**
     * 用 MODE_STATIC 整段预载后播放。
     *
     * 之前用 MODE_STREAM 边写边播，后台还有网络线程抢占 CPU，
     * 一旦写入跟不上蓝牙缓冲就会断续/爆音 —— 表现为"有时正常有时不正常"。
     * MODE_STATIC 把整段音频一次性写入缓冲区，播放期间应用完全不参与，
     * 从原理上消除欠载。
     */
    private static void playPcm(short[] pcm, int rate) throws Exception {
        int bytes = pcm.length * 2;
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bytes)
                    .build();

            int written = track.write(pcm, 0, pcm.length);
            if (written <= 0) {
                BridgeService.log("AudioTrack 写入失败 " + written);
                return;
            }
            BridgeService.log("预载 " + written + " 样本（" + (bytes / 1024) + " KB），开始播放");
            track.play();

            long totalMs = (long) pcm.length * 1000L / rate;
            long start = System.currentTimeMillis();
            long timeout = totalMs + 5000;
            while (System.currentTimeMillis() - start < timeout) {
                if (track.getPlaybackHeadPosition() >= pcm.length) break;
                try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            }
            BridgeService.log("播放完成，实际用时 "
                    + ((System.currentTimeMillis() - start) / 1000.0) + " 秒");
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) { }
                try { track.release(); } catch (Throwable ignored) { }
            }
            BridgeService.log("播报结束");
        }
    }
}