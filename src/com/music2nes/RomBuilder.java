package com.music2nes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns mono PCM into a UxROM (mapper 2) NES ROM that streams the audio
 * through the APU's $4011 DAC. Audio is stored as 4-bit delta codes, two
 * samples per byte, chosen by a Viterbi search. Pure Java, no Android deps.
 */
public final class RomBuilder {
    public interface Progress {
        void update(String stage, float fraction);
    }

    public static final class Result {
        public final byte[] rom;
        public final double sampleRate;
        public final double seconds;

        Result(byte[] rom, double sampleRate, double seconds) {
            this.rom = rom;
            this.sampleRate = sampleRate;
            this.seconds = seconds;
        }
    }

    static final int CPU_HZ = 1789773;
    static final int[] DELTAS = {-42, -28, -18, -11, -6, -3, -1, 0, 1, 3, 6, 11, 18, 28, 42, 60};
    static final int ZERO = 7;
    static final int ACC0 = 64;
    static final int PTRLO = 0, PTRHI = 1, BANK = 2, PREV = 3, ACC = 4;
    public static final int MAX_LINE = 28;

    private RomBuilder() {}

    /** Largest song (seconds) that fits, at the lowest sample rate the player supports. */
    public static double maxSeconds(int prgKb) {
        int c = 5 * 254 + 31;
        return (prgKb / 16 - 1) * 64 * 512.0 * c / CPU_HZ;
    }

    public static Result build(short[] pcm, int length, int inRate, String artist, String title,
                               int prgKb, Progress p) {
        int prgBanks = prgKb / 16;
        int audioBanks = prgBanks - 1;
        int maxPages = audioBanks * 64;

        // trim near-silence (below -50 dB of peak) from both ends
        int peak = 0;
        for (int i = 0; i < length; i++) peak = Math.max(peak, Math.abs(pcm[i]));
        if (peak == 0) throw new IllegalArgumentException("The audio is completely silent.");
        double thr = peak * Math.pow(10, -50 / 20.0);
        int start = 0, end = length;
        while (start < end && Math.abs(pcm[start]) <= thr) start++;
        while (end > start && Math.abs(pcm[end - 1]) <= thr) end--;
        int n = end - start;
        double seconds = (double) n / inRate;

        // cycles per sample: C = 5N+29 (+2 with optional NOP); N in [11, 254]
        int pickN = -1, pickNop = 0, pickC = 0;
        search:
        for (int nn = 11; nn <= 254; nn++) {
            for (int extra = 0; extra <= 2; extra += 2) {
                int c = 5 * nn + 29 + extra;
                long m = (long) Math.floor(n * ((double) CPU_HZ / c) / inRate);
                if ((m + 511) / 512 <= maxPages) {
                    pickN = nn; pickNop = extra; pickC = c;
                    break search;
                }
            }
        }
        if (pickN < 0) {
            throw new IllegalArgumentException(String.format(
                    "Song is too long (%.0f s). The %d KB ROM holds at most %.0f s.",
                    seconds, prgKb, maxSeconds(prgKb)));
        }
        double rate = (double) CPU_HZ / pickC;

        p.update("Resampling", 0f);
        float[] x = resample(pcm, start, n, inRate, rate, p);

        // 40 Hz high-pass to remove DC/rumble
        double a = Math.exp(-2 * Math.PI * 40 / rate);
        double y = 0, prev = 0;
        for (int i = 0; i < x.length; i++) {
            double v = x[i];
            y = a * (y + v - prev);
            prev = v;
            x[i] = (float) y;
        }

        // normalise to the 99.98th percentile so rare peaks don't waste range
        float[] mags = new float[x.length];
        for (int i = 0; i < x.length; i++) mags[i] = Math.abs(x[i]);
        Arrays.sort(mags);
        float ref = mags[Math.min(mags.length - 1, (int) (mags.length * 0.9998))];
        if (ref <= 0) ref = 1;

        // invert the APU's nonlinear DMC DAC so the output is linear
        double top = tnd(127.0);
        float[] target = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            double v = Math.max(-1, Math.min(1, x[i] / ref));
            double want = Math.max(1e-6, Math.min(top, (v + 1) / 2 * top));
            double d = 22638.0 / (159.79 / want - 100);
            target[i] = (float) Math.max(0, Math.min(127, d));
        }

        byte[] codes = viterbi(target, p);

        int pages = (codes.length + 511) / 512;
        byte[] audio = new byte[audioBanks * 16384];
        for (int i = 0; i < pages * 256; i++) {
            int hi = 2 * i < codes.length ? codes[2 * i] : ZERO;
            int lo = 2 * i + 1 < codes.length ? codes[2 * i + 1] : ZERO;
            audio[i] = (byte) ((hi << 4) | lo);
        }
        int endBank = pages / 64, endPage = 0x80 + pages % 64;

        byte[] fixed = assemble(prgBanks, pickN, pickNop != 0, endBank, endPage, artist, title);

        byte[] rom = new byte[16 + prgBanks * 16384];
        byte[] header = {'N', 'E', 'S', 0x1A, (byte) prgBanks, 0, 0x21, 0};
        System.arraycopy(header, 0, rom, 0, header.length);
        System.arraycopy(audio, 0, rom, 16, audio.length);
        System.arraycopy(fixed, 0, rom, 16 + audio.length, fixed.length);
        p.update("Done", 1f);
        return new Result(rom, rate, codes.length / rate);
    }

    static double tnd(double d) {
        return 159.79 / (22638.0 / Math.max(d, 1e-9) + 100);
    }

    // ------------------------------------------------------------ resampler

    /** Windowed-sinc resampler (Blackman window, 16 zero crossings each side). */
    static float[] resample(short[] in, int off, int n, double inRate, double outRate, Progress p) {
        double fc = 0.45 * Math.min(inRate, outRate);
        double step = 2 * fc / inRate;              // sinc argument per input sample
        int zeros = 16;
        double halfW = zeros / step;                // half width in input samples
        final int res = 64;
        int tabLen = (int) Math.ceil(halfW * res) + 2;
        float[] tab = new float[tabLen];
        for (int j = 0; j < tabLen; j++) {
            double t = (double) j / res;
            if (t >= halfW) break;
            double s = step * t;
            double sinc = s == 0 ? 1 : Math.sin(Math.PI * s) / (Math.PI * s);
            double u = 0.5 + 0.5 * t / halfW;       // Blackman over [-halfW, halfW]
            double win = 0.42 - 0.5 * Math.cos(2 * Math.PI * u) + 0.08 * Math.cos(4 * Math.PI * u);
            tab[j] = (float) (sinc * win * step / 32768.0);
        }
        int m = (int) Math.floor(n * outRate / inRate);
        float[] out = new float[m];
        double ratio = inRate / outRate;
        for (int i = 0; i < m; i++) {
            double center = i * ratio;
            int lo = Math.max(0, (int) Math.ceil(center - halfW));
            int hi = Math.min(n - 1, (int) Math.floor(center + halfW));
            double sum = 0;
            for (int j = lo; j <= hi; j++) {
                double d = Math.abs(j - center) * res;
                int k = (int) d;
                float f = (float) (d - k);
                sum += in[off + j] * (tab[k] + (tab[k + 1] - tab[k]) * f);
            }
            out[i] = (float) sum;
            if ((i & 0x3FFF) == 0) p.update("Resampling", (float) i / m);
        }
        return out;
    }

    // ------------------------------------------------------------ encoder

    /** Optimal 4-bit delta codes so the 7-bit accumulator tracks target (0..127). */
    static byte[] viterbi(float[] x, Progress p) {
        final int block = 4096;
        int n = x.length;
        byte[] codes = new byte[n];
        // predecessor lists: state t can be reached from s = t - DELTAS[k]
        int[][] predS = new int[128][], predK = new int[128][];
        for (int t = 0; t < 128; t++) {
            List<int[]> l = new ArrayList<>();
            for (int k = 0; k < 16; k++) {
                int s = t - DELTAS[k];
                if (s >= 0 && s <= 127) l.add(new int[]{s, k});
            }
            predS[t] = new int[l.size()];
            predK[t] = new int[l.size()];
            for (int i = 0; i < l.size(); i++) {
                predS[t][i] = l.get(i)[0];
                predK[t][i] = l.get(i)[1];
            }
        }
        float[] cost = new float[128], next = new float[128];
        byte[] bp = new byte[block * 128];
        int acc = ACC0;
        for (int b0 = 0; b0 < n; b0 += block) {
            int len = Math.min(block, n - b0);
            Arrays.fill(cost, Float.POSITIVE_INFINITY);
            cost[acc] = 0;
            for (int i = 0; i < len; i++) {
                float xi = x[b0 + i];
                float lowest = Float.POSITIVE_INFINITY;
                int row = i * 128;
                for (int t = 0; t < 128; t++) {
                    int[] ps = predS[t], pk = predK[t];
                    float best = Float.POSITIVE_INFINITY;
                    int bk = ZERO;
                    for (int j = 0; j < ps.length; j++) {
                        float c = cost[ps[j]];
                        if (c < best) { best = c; bk = pk[j]; }
                    }
                    float e = t - xi;
                    float v = best + e * e;
                    next[t] = v;
                    bp[row + t] = (byte) bk;
                    if (v < lowest) lowest = v;
                }
                for (int t = 0; t < 128; t++) cost[t] = next[t] - lowest;  // keep floats small
            }
            int s = 0;
            for (int t = 1; t < 128; t++) if (cost[t] < cost[s]) s = t;
            acc = s;
            for (int i = len - 1; i >= 0; i--) {
                int k = bp[i * 128 + s];
                codes[b0 + i] = (byte) k;
                s -= DELTAS[k];
            }
            p.update("Encoding", (float) (b0 + len) / n);
        }
        return codes;
    }

    // ------------------------------------------------------------ font + text

    static final String CHARSET = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-:/.,'!?&()";
    static final int[][] GLYPHS = {
        {0, 0, 0, 0, 0, 0, 0},
        {0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001}, // A
        {0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110}, // B
        {0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110}, // C
        {0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110}, // D
        {0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111}, // E
        {0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000}, // F
        {0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111}, // G
        {0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001}, // H
        {0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110}, // I
        {0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100}, // J
        {0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001}, // K
        {0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111}, // L
        {0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001}, // M
        {0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001}, // N
        {0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110}, // O
        {0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000}, // P
        {0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101}, // Q
        {0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001}, // R
        {0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110}, // S
        {0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100}, // T
        {0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110}, // U
        {0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100}, // V
        {0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b10101, 0b01010}, // W
        {0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001}, // X
        {0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100}, // Y
        {0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111}, // Z
        {0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110}, // 0
        {0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110}, // 1
        {0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111}, // 2
        {0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110}, // 3
        {0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010}, // 4
        {0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110}, // 5
        {0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110}, // 6
        {0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000}, // 7
        {0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110}, // 8
        {0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100}, // 9
        {0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000}, // -
        {0b00000, 0b00100, 0b00100, 0b00000, 0b00100, 0b00100, 0b00000}, // :
        {0b00001, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b10000}, // /
        {0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100}, // .
        {0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b00100, 0b01000}, // ,
        {0b00100, 0b00100, 0b01000, 0b00000, 0b00000, 0b00000, 0b00000}, // '
        {0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00000, 0b00100}, // !
        {0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b00000, 0b00100}, // ?
        {0b01100, 0b10010, 0b10100, 0b01000, 0b10101, 0b10010, 0b01101}, // &
        {0b00010, 0b00100, 0b01000, 0b01000, 0b01000, 0b00100, 0b00010}, // (
        {0b01000, 0b00100, 0b00010, 0b00010, 0b00010, 0b00100, 0b01000}, // )
    };

    /** Upper-cases and drops characters the font can't draw. */
    public static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toUpperCase().toCharArray()) {
            if (ch == '_') ch = ' ';
            b.append(CHARSET.indexOf(ch) >= 0 ? ch : ' ');
        }
        return b.toString().replaceAll(" +", " ").trim();
    }

    /** Wraps into at most two lines of MAX_LINE characters. */
    static List<String> wrap(String s) {
        List<String> out = new ArrayList<>();
        s = sanitize(s);
        while (!s.isEmpty() && out.size() < 2) {
            if (s.length() <= MAX_LINE || out.size() == 1) {
                out.add(s.length() <= MAX_LINE ? s : s.substring(0, MAX_LINE));
                break;
            }
            int cut = s.lastIndexOf(' ', MAX_LINE);
            if (cut <= 0) cut = MAX_LINE;
            out.add(s.substring(0, cut).trim());
            s = s.substring(cut).trim();
        }
        return out;
    }

    // ------------------------------------------------------------ 6502 code

    static final class Asm {
        final int org;
        byte[] buf = new byte[0x4000];
        int len;
        final Map<String, Integer> labels = new HashMap<>();
        final List<Object[]> fixes = new ArrayList<>();

        Asm(int org) { this.org = org; }
        int pc() { return org + len; }
        void label(String name) { labels.put(name, pc()); }
        void b(int... v) { for (int x : v) buf[len++] = (byte) x; }
        void w(int op, int addr) { b(op, addr & 0xFF, addr >> 8); }
        void w(int op, String target) { b(op); fixes.add(new Object[]{"abs", len, target}); b(0, 0); }
        void br(int op, String target) { b(op); fixes.add(new Object[]{"rel", len, target}); b(0); }

        void resolve() {
            for (Object[] f : fixes) {
                int at = (Integer) f[1];
                Integer v = labels.get((String) f[2]);
                if (v == null) throw new IllegalStateException("label " + f[2]);
                if (f[0].equals("abs")) {
                    buf[at] = (byte) (v & 0xFF);
                    buf[at + 1] = (byte) (v >> 8);
                } else {
                    int rel = v - (org + at + 1);
                    if (rel < -128 || rel > 127) throw new IllegalStateException("branch " + f[2]);
                    buf[at] = (byte) rel;
                }
            }
        }
    }

    static byte[] assemble(int prgBanks, int n, boolean nop, int endBank, int endPage,
                           String artist, String title) {
        // font in CHR format: plane 0 only, glyph shifted to columns 1..5
        int tiles = CHARSET.length();
        int chrPages = (tiles * 16 + 255) / 256;
        byte[] chr = new byte[chrPages * 256];
        for (int t = 0; t < tiles; t++)
            for (int r = 0; r < 7; r++) chr[t * 16 + r] = (byte) (GLYPHS[t][r] << 2);

        Asm a = new Asm(0xC000);
        a.label("banktable");
        for (int i = 0; i < prgBanks; i++) a.b(i);
        while ((a.pc() & 0xFF) != 0) a.b(0);
        a.label("hitab");
        for (int v = 0; v < 256; v++) a.b(DELTAS[v >> 4] & 0xFF);
        a.label("lotab");
        for (int v = 0; v < 256; v++) a.b(DELTAS[v & 15] & 0xFF);
        a.label("font");
        for (byte c : chr) a.b(c);
        int font = a.labels.get("font");

        a.label("reset");
        a.b(0x78, 0xD8);                              // sei, cld
        a.b(0xA2, 0x40); a.w(0x8E, 0x4017);           // no frame IRQ
        a.b(0xA2, 0xFF, 0x9A, 0xE8);                  // ldx #$ff, txs, inx
        a.w(0x8E, 0x2000); a.w(0x8E, 0x2001);
        a.w(0x8E, 0x4010); a.w(0x8E, 0x4015);
        a.w(0x2C, 0x2002);
        for (int i = 0; i < 2; i++) {                 // wait two vblanks
            a.label("vw" + i); a.w(0x2C, 0x2002); a.br(0x10, "vw" + i);
        }

        // upload font to CHR-RAM $0000
        a.b(0xA9, 0x00); a.w(0x8D, 0x2006); a.w(0x8D, 0x2006);
        a.b(0xA9, font & 0xFF, 0x85, PTRLO);
        a.b(0xA9, font >> 8, 0x85, PTRHI);
        a.b(0xA2, chrPages, 0xA0, 0x00);
        a.label("chr"); a.b(0xB1, PTRLO); a.w(0x8D, 0x2007); a.b(0xC8); a.br(0xD0, "chr");
        a.b(0xE6, PTRHI, 0xCA); a.br(0xD0, "chr");

        // palette: black background, white text
        a.b(0xA9, 0x3F); a.w(0x8D, 0x2006); a.b(0xA9, 0x00); a.w(0x8D, 0x2006);
        for (int i = 0; i < 32; i++) { a.b(0xA9, i % 4 != 0 ? 0x30 : 0x0F); a.w(0x8D, 0x2007); }

        // clear nametable 0 + attributes
        a.b(0xA9, 0x20); a.w(0x8D, 0x2006); a.b(0xA9, 0x00); a.w(0x8D, 0x2006);
        a.b(0xA0, 0x04, 0xA2, 0x00);
        a.label("clr"); a.w(0x8D, 0x2007); a.b(0xCA); a.br(0xD0, "clr"); a.b(0x88); a.br(0xD0, "clr");

        // text: artist, blank row, title, centred around row 12; hint at row 20
        List<String> lines = new ArrayList<>(wrap(artist));
        List<String> t = wrap(title);
        if (!lines.isEmpty() && !t.isEmpty()) lines.add("");
        lines.addAll(t);
        int row0 = 12 - lines.size() / 2;
        List<int[]> placed = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) continue;
            placed.add(new int[]{row0 + i}); texts.add(lines.get(i));
        }
        placed.add(new int[]{20}); texts.add("A: PLAY/PAUSE");
        for (int i = 0; i < texts.size(); i++) {
            String s = texts.get(i);
            int addr = 0x2000 + placed.get(i)[0] * 32 + (32 - s.length()) / 2;
            a.b(0xA9, addr >> 8); a.w(0x8D, 0x2006); a.b(0xA9, addr & 0xFF); a.w(0x8D, 0x2006);
            for (char ch : s.toCharArray()) { a.b(0xA9, CHARSET.indexOf(ch)); a.w(0x8D, 0x2007); }
        }

        a.label("vw2"); a.w(0x2C, 0x2002); a.br(0x10, "vw2");
        a.b(0xA9, 0x00); a.w(0x8D, 0x2005); a.w(0x8D, 0x2005); a.w(0x8D, 0x2000);
        a.b(0xA9, 0x0A); a.w(0x8D, 0x2001);           // show background
        a.w(0x20, "rewind");
        a.b(0xA9, 0x00, 0x85, PREV);

        // ---- playback loop: 5N+29(+2) cycles per sample, also on page change
        a.label("loop_full"); a.b(0xA2, n);
        a.label("loop_dx"); a.label("dl"); a.b(0xCA); a.br(0xD0, "dl");
        if (nop) a.b(0xEA);
        decodeSample(a, "hitab");
        a.b(0xA2, n + 1);
        a.label("dl2"); a.b(0xCA); a.br(0xD0, "dl2");
        if (nop) a.b(0xEA);
        decodeSample(a, "lotab");
        a.b(0xC8); a.br(0xD0, "loop_full");           // iny ; bne
        a.b(0xE6, PTRHI, 0xA5, PTRHI, 0xC9, 0xC0); a.br(0xD0, "nobank");
        a.b(0xA9, 0x80, 0x85, PTRHI, 0xE6, BANK, 0xA6, BANK);
        a.w(0xBD, "banktable"); a.w(0x9D, "banktable"); // bus-conflict-safe bank switch
        a.label("nobank");
        a.b(0xA5, BANK, 0xC9, endBank); a.br(0xD0, "noend");
        a.b(0xA5, PTRHI, 0xC9, endPage); a.br(0xD0, "noend");
        a.w(0x20, "rewind"); a.w(0x4C, "pause");      // song over: rewind and pause
        a.label("noend");
        // inline A-button poll (kept inline so the cycle count stays fixed)
        a.b(0xA9, 0x01); a.w(0x8D, 0x4016); a.b(0xA9, 0x00); a.w(0x8D, 0x4016);
        a.w(0xAD, 0x4016); a.b(0x29, 0x01, 0xAA, 0x45, PREV); a.br(0xF0, "nochg");
        a.b(0x86, PREV, 0x8A); a.br(0xD0, "pause");
        a.label("nochg"); a.b(0xA2, n - 10); a.w(0x4C, "loop_dx");

        // ---- paused: poll once per frame, resume on a fresh press of A
        a.label("pause");
        a.label("pv"); a.w(0x2C, 0x2002); a.br(0x10, "pv");
        a.b(0xA9, 0x01); a.w(0x8D, 0x4016); a.b(0xA9, 0x00); a.w(0x8D, 0x4016);
        a.w(0xAD, 0x4016); a.b(0x29, 0x01, 0xAA, 0x45, PREV); a.br(0xF0, "pause");
        a.b(0x86, PREV, 0x8A); a.br(0xF0, "pause");
        a.w(0x4C, "loop_full");

        a.label("rewind");
        a.b(0xA9, 0x00, 0x85, PTRLO, 0x85, BANK, 0xAA); a.w(0x9D, "banktable");
        a.b(0xA9, ACC0, 0x85, ACC);
        a.b(0xA9, 0x80, 0x85, PTRHI, 0xA0, 0x00, 0x60);

        a.label("irq"); a.b(0x40);
        a.resolve();
        if (a.len > 0x4000 - 6) throw new IllegalStateException("fixed bank overflow");

        byte[] fixed = Arrays.copyOf(a.buf, 0x4000);
        int irq = a.labels.get("irq"), reset = a.labels.get("reset");
        int[] vec = {irq, reset, irq};
        for (int i = 0; i < 3; i++) {
            fixed[0x3FFA + 2 * i] = (byte) (vec[i] & 0xFF);
            fixed[0x3FFB + 2 * i] = (byte) (vec[i] >> 8);
        }
        return fixed;
    }

    // lda (ptr),y ; tax ; lda acc ; clc ; adc tab,x ; sta acc ; sta $4011
    static void decodeSample(Asm a, String tab) {
        a.b(0xB1, PTRLO, 0xAA, 0xA5, ACC, 0x18); a.w(0x7D, tab);
        a.b(0x85, ACC); a.w(0x8D, 0x4011);
    }
}
