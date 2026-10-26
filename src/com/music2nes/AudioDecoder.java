package com.music2nes;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Decodes any audio the device supports into mono 16-bit PCM. */
final class AudioDecoder {
    static final class Pcm {
        final short[] samples;
        final int length;
        final int rate;

        Pcm(short[] samples, int length, int rate) {
            this.samples = samples;
            this.length = length;
            this.rate = rate;
        }
    }

    private AudioDecoder() {}

    static Pcm decode(Context ctx, Uri uri, RomBuilder.Progress p) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("No audio track found in this file.");
            ex.selectTrack(track);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : -1;
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            short[] out = new short[1 << 20];
            int len = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false;
            while (!outEos) {
                if (!inEos) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ii);
                        int size = ex.readSampleData(b, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ByteBuffer le = ob.slice().order(ByteOrder.LITTLE_ENDIAN);
                        boolean isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT;
                        int frames = info.size / ((isFloat ? 4 : 2) * channels);
                        if (len + frames > out.length)
                            out = Arrays.copyOf(out, Math.max(out.length * 2, len + frames));
                        for (int f = 0; f < frames; f++) {
                            float sum = 0;
                            for (int c = 0; c < channels; c++)
                                sum += isFloat ? le.getFloat() * 32767f : le.getShort();
                            float v = sum / channels;
                            out[len++] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    if (durUs > 0) p.update("Decoding", Math.min(1f, (float) info.presentationTimeUs / durUs));
                }
            }
            if (len == 0) throw new IOException("The file decoded to no audio.");
            return new Pcm(out, len, rate);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) { }
                codec.release();
            }
            ex.release();
        }
    }
}
