package com.music2nes;

import java.io.*;
import java.nio.*;
import java.nio.file.*;

/** Desktop check: TestMain in.s16le rate out.nes "artist" "title" */
public class TestMain {
    public static void main(String[] a) throws Exception {
        byte[] raw = Files.readAllBytes(Paths.get(a[0]));
        ShortBuffer sb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] pcm = new short[sb.remaining()]; sb.get(pcm);
        long t = System.currentTimeMillis();
        RomBuilder.Result r = RomBuilder.build(pcm, pcm.length, Integer.parseInt(a[1]), a[3], a[4], 512,
                (s, f) -> {});
        Files.write(Paths.get(a[2]), r.rom);
        System.out.printf("%d bytes, %.1f Hz, %.2f s audio, built in %d ms%n",
                r.rom.length, r.sampleRate, r.seconds, System.currentTimeMillis() - t);
    }
}
