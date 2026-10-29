package com.music2nes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns mono PCM into a UxROM (mapper 2) NES ROM that streams the audio
 * through the APU's $4011 DAC, with the title, an elapsed/total timer and a
 * progress bar on screen. Audio is stored as 4-bit delta codes, two samples
 * per byte, chosen by a Viterbi search. Pure Java, no Android deps.
 *
 * Timing: every sample slot runs the same fixed-length "work" block, which
 * polls vblank and performs a few PPU writes from a precomputed op list, so
 * the screen updates during vblank without disturbing the sample spacing.
 * Timer and bar values are precomputed per 256-byte page and stored in the
 * last two pages of each 16 KB bank.
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
    static final int PTRLO = 0, PTRHI = 1, BANK = 2, PREV = 3, ACC = 4, OPIDX = 5, YSAVE = 6;
    static final int OPVAL = 0x0300;            // RAM copy of the PPU op values
    static final int PAGES_PER_BANK = 62;       // pages 62-63 of each bank hold display tables
    static final int TABLES = 0xBE00;           // 7 tables x 64 entries, indexed by page in bank
    static final int N_MIN = 24, N_MAX = 254;   // delay-loop count limits
    static final int VBLANK_BUDGET = 2200;      // CPU cycles of vblank we allow ourselves to use
    static final int TIMER_ROW = 21, TIMER_COL = 9, BAR_ROW = 23, BAR_CELLS = 24;
    static final int BAR_PX = BAR_CELLS * 8;
    public static final int MAX_LINE = 28;

    private RomBuilder() {}

    /** Longest song (seconds) that fits, at the lowest sample rate the player supports. */
    public static double maxSeconds(int prgKb) {
        int c = 5 * N_MAX + 61 + 15 * 16 + 2;
        return (prgKb / 16 - 1) * PAGES_PER_BANK * 512.0 * c / CPU_HZ;
    }

    public static Result build(short[] pcm, int length, int inRate, String artist, String title,
                               int prgKb, Progress p) {
        int prgBanks = prgKb / 16;
        int audioBanks = prgBanks - 1;
        int maxPages = audioBanks * PAGES_PER_BANK;

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

        // cycles per sample C = 5N + 61 + 15K (+2 with optional NOP), K = PPU ops per slot.
        // Pick the smallest C whose audio fits and whose PPU updates fit in vblank.
        int bestN = -1, bestNop = 0, bestC = Integer.MAX_VALUE;
        Ops bestOps = null;
        for (int k = 2; k <= 16; k++) {
            Ops ops = pack(k);
            if ((ops.slots + 1) * k > 128) continue;
            int work = 30 + 15 * k;
            search:
            for (int nn = N_MIN; nn <= N_MAX; nn++) {
                for (int extra = 0; extra <= 2; extra += 2) {
                    int c = 5 * nn + 61 + 15 * k + extra;
                    long m = (long) Math.floor(n * ((double) CPU_HZ / c) / inRate);
                    if ((m + 511) / 512 > maxPages) continue;
                    if (ops.slots * c + work <= VBLANK_BUDGET && c < bestC) {
                        bestN = nn; bestNop = extra; bestC = c; bestOps = ops;
                    }
                    break search;     // larger C only makes the vblank fit worse
                }
            }
        }
        if (bestOps == null) {
            throw new IllegalArgumentException(String.format(
                    "Song is too long (%.0f s). The %d KB ROM holds at most %.0f s.",
                    seconds, prgKb, maxSeconds(prgKb)));
        }
        double rate = (double) CPU_HZ / bestC;

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
        for (int g = 0; g < pages; g++) {
            int base = (g / PAGES_PER_BANK) * 16384 + (g % PAGES_PER_BANK) * 256;
            for (int i = 0; i < 256; i++) {
                int s = (g * 256 + i) * 2;
                int hi = s < codes.length ? codes[s] : ZERO;
                int lo = s + 1 < codes.length ? codes[s + 1] : ZERO;
                audio[base + i] = (byte) ((hi << 4) | lo);
            }
        }
        // per-page display values: MT MO ST SO (timer digits), BLO BA BB (bar head)
        for (int b = 0; b < audioBanks; b++) {
            for (int k = 0; k < PAGES_PER_BANK; k++) {
                int[] v = pageDisplay(Math.min(b * PAGES_PER_BANK + k, pages), pages, rate);
                for (int t = 0; t < 7; t++) audio[b * 16384 + (TABLES - 0x8000) + 64 * t + k] = (byte) v[t];
            }
        }
        int endBank = pages / PAGES_PER_BANK, endPage = 0x80 + pages % PAGES_PER_BANK;
        double total = codes.length / rate;

        byte[] fixed = assemble(prgBanks, bestN, bestNop != 0, bestOps, endBank, endPage,
                artist, title, clock((int) total));

        byte[] rom = new byte[16 + prgBanks * 16384];
        byte[] header = {'N', 'E', 'S', 0x1A, (byte) prgBanks, 0, 0x21, 0};
        System.arraycopy(header, 0, rom, 0, header.length);
        System.arraycopy(audio, 0, rom, 16, audio.length);
        System.arraycopy(fixed, 0, rom, 16 + audio.length, fixed.length);
        p.update("Done", 1f);
        return new Result(rom, rate, total);
    }

    static double tnd(double d) {
        return 159.79 / (22638.0 / Math.max(d, 1e-9) + 100);
    }

    /** "MM:SS" with a leading space instead of a zero for minutes under 10. */
    static String clock(int secs) {
        int m = Math.min(99, secs / 60), s = secs % 60;
        return (m < 10 ? " " + m : "" + m) + ":" + (s < 10 ? "0" : "") + s;
    }

    static int[] pageDisplay(int g, int pages, double rate) {
        String c = clock((int) (g * 512L / rate));
        int px = (int) ((long) g * BAR_PX / pages);
        int i = px >> 3, f = px & 7;
        return new int[]{
            tile(c.charAt(0)), tile(c.charAt(1)), tile(c.charAt(3)), tile(c.charAt(4)),
            (barAddr() & 0xFF) + i,
            i == 0 ? T_LCAP : T_FULL,
            i == BAR_CELLS ? T_RCAP : f == 0 ? T_EMPTY : T_PART1 + f - 1,
        };
    }

    static int barAddr() { return 0x2000 + BAR_ROW * 32 + 3; }
    static int timerAddr() { return 0x2000 + TIMER_ROW * 32 + TIMER_COL; }

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
    // progress-bar tiles follow the font: empty, 1-7 px filled, full, left cap, right cap
    static final int T_EMPTY = CHARSET.length(), T_PART1 = T_EMPTY + 1, T_FULL = T_EMPTY + 8,
            T_LCAP = T_EMPTY + 9, T_RCAP = T_EMPTY + 10, TILE_COUNT = T_EMPTY + 11;

    static int tile(char ch) {
        return CHARSET.indexOf(ch);
    }

    /** CHR data: white text and bar outline on plane 0, green bar fill on plane 1. */
    static byte[] chrData() {
        int pages = (TILE_COUNT * 16 + 255) / 256;
        byte[] chr = new byte[pages * 256];
        for (int t = 0; t < CHARSET.length(); t++)
            for (int r = 0; r < 7; r++) chr[t * 16 + r] = (byte) (GLYPHS[t][r] << 2);
        for (int f = 0; f <= 8; f++) {          // bar cells with f px filled
            int o = (T_EMPTY + f) * 16;
            chr[o + 1] = chr[o + 6] = (byte) 0xFF;
            int fill = (0xFF << (8 - f)) & 0xFF;
            for (int r = 2; r <= 5; r++) chr[o + 8 + r] = (byte) fill;
        }
        int l = T_LCAP * 16, rr = T_RCAP * 16;
        chr[l + 1] = chr[l + 6] = (byte) 0x03;
        chr[rr + 1] = chr[rr + 6] = (byte) 0xC0;
        for (int r = 2; r <= 5; r++) {
            chr[l + r] = (byte) 0x02;
            chr[rr + r] = (byte) 0x40;
        }
        return chr;
    }

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

    // ------------------------------------------------------------ PPU op list

    /**
     * The vblank update as a list of (register offset from $2000, value) writes,
     * packed K per slot. Slot 0 is the idle slot (writes to read-only $2002, a
     * no-op). $2006/$2005 pairs never straddle a slot, because each slot's
     * $2002 read resets the PPU's write latch.
     */
    static final class Ops {
        int k, slots;
        final int[] reg = new int[256], val = new int[256];
        final int[] pos = new int[7];   // op index of MT MO ST SO BLO BA BB
    }

    static Ops pack(int k) {
        int ta = timerAddr(), ba = barAddr();
        int[][][] groups = {
            {{6, ba >> 8, -1}, {6, ba & 0xFF, 4}},
            {{7, T_LCAP, 5}}, {{7, T_EMPTY, 6}},
            {{6, ta >> 8, -1}, {6, ta & 0xFF, -1}},
            {{7, 0, 0}}, {{7, 0, 1}}, {{7, tile(':'), -1}}, {{7, 0, 2}}, {{7, 0, 3}},
            {{0, 0x00, -1}},
            {{5, 0, -1}, {5, 0, -1}},
        };
        Ops o = new Ops();
        o.k = k;
        Arrays.fill(o.reg, 2);
        int idx = k, used = 0;
        for (int[][] g : groups) {
            if (g.length > k - used) { idx += k - used; used = 0; }
            for (int[] op : g) {
                o.reg[idx] = op[0];
                o.val[idx] = op[1];
                if (op[2] >= 0) o.pos[op[2]] = idx;
                idx++;
                if (++used == k) used = 0;
            }
        }
        o.slots = (idx - k + k - 1) / k;
        return o;
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
        void w(int op, String target, int offset) {
            b(op); fixes.add(new Object[]{"abs", len, target, offset}); b(0, 0);
        }
        final List<int[]> branches = new ArrayList<>();   // {operand offset}
        void br(int op, String target) {
            b(op); branches.add(new int[]{len}); fixes.add(new Object[]{"rel", len, target}); b(0);
        }

        /** True if any taken branch between the two labels lands on another page (+1 cycle). */
        boolean crossesPage(String from, String to) {
            int lo = labels.get(from), hi = labels.get(to);
            for (int[] br : branches) {
                int next = org + br[0] + 1;
                if (next <= lo || next > hi) continue;
                int target = next + buf[br[0]];
                if ((target >> 8) != (next >> 8)) return true;
            }
            return false;
        }
        void page() { while ((pc() & 0xFF) != 0) b(0); }

        void resolve() {
            for (Object[] f : fixes) {
                int at = (Integer) f[1];
                Integer v = labels.get((String) f[2]);
                if (v == null) throw new IllegalStateException("label " + f[2]);
                if (f.length > 3) v += (Integer) f[3];
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

    static byte[] assemble(int prgBanks, int n, boolean nop, Ops ops, int endBank, int endPage,
                           String artist, String title, String total) {
        for (int pad = 0; pad < 256; pad++) {
            Asm a = assemble(prgBanks, n, nop, ops, endBank, endPage, artist, title, total, pad);
            if (a.crossesPage("loop_full", "pause")) continue;
            byte[] fixed = Arrays.copyOf(a.buf, 0x4000);
            int irq = a.labels.get("irq"), reset = a.labels.get("reset");
            int[] vec = {irq, reset, irq};
            for (int i = 0; i < 3; i++) {
                fixed[0x3FFA + 2 * i] = (byte) (vec[i] & 0xFF);
                fixed[0x3FFB + 2 * i] = (byte) (vec[i] >> 8);
            }
            return fixed;
        }
        throw new IllegalStateException("could not place the playback loop");
    }

    static Asm assemble(int prgBanks, int n, boolean nop, Ops ops, int endBank, int endPage,
                        String artist, String title, String total, int pad) {
        byte[] chr = chrData();
        int chrPages = chr.length / 256;
        int k = ops.k;
        if ((barAddr() & 0xFF) + BAR_CELLS + 1 > 0xFF) throw new IllegalStateException("bar row");

        Asm a = new Asm(0xC000);
        a.label("banktable");
        for (int i = 0; i < prgBanks; i++) a.b(i);
        a.page();
        a.label("hitab");
        for (int v = 0; v < 256; v++) a.b(DELTAS[v >> 4] & 0xFF);
        a.label("lotab");
        for (int v = 0; v < 256; v++) a.b(DELTAS[v & 15] & 0xFF);
        a.label("opreg");
        for (int i = 0; i < 256; i++) a.b(ops.reg[i]);
        a.label("opinit");
        for (int i = 0; i < 256; i++) a.b(ops.val[i]);
        // startmap[vblank | opidx]: begin the op list on vblank when idle
        a.label("startmap");
        for (int i = 0; i < 256; i++) a.b((i & 0x7F) != 0 ? i & 0x7F : (i & 0x80) != 0 ? k : 0);
        a.label("nextslot");
        for (int i = 0; i < 256; i++) {
            int s = i / k;
            a.b(i % k == 0 && s >= 1 && s < ops.slots ? (s + 1) * k : 0);
        }
        a.label("font");
        for (byte c : chr) a.b(c);
        int font = a.labels.get("font");

        for (int i = 0; i < pad; i++) a.b(0);
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

        // palette: black background, white text, green bar fill
        a.b(0xA9, 0x3F); a.w(0x8D, 0x2006); a.b(0xA9, 0x00); a.w(0x8D, 0x2006);
        for (int i = 0; i < 32; i++) {
            a.b(0xA9, i % 4 == 0 ? 0x0F : i % 4 == 2 ? 0x2A : 0x30); a.w(0x8D, 0x2007);
        }

        // clear nametable 0 + attributes
        a.b(0xA9, 0x20); a.w(0x8D, 0x2006); a.b(0xA9, 0x00); a.w(0x8D, 0x2006);
        a.b(0xA0, 0x04, 0xA2, 0x00);
        a.label("clr"); a.w(0x8D, 0x2007); a.b(0xCA); a.br(0xD0, "clr"); a.b(0x88); a.br(0xD0, "clr");

        // copy the op values to RAM
        a.b(0xA2, 0x00);
        a.label("opcp"); a.w(0xBD, "opinit"); a.w(0x9D, OPVAL); a.b(0xE8); a.br(0xD0, "opcp");

        // text: artist, blank row, title, centred around row 11; hint, timer, bar below
        List<String> lines = new ArrayList<>(wrap(artist));
        List<String> t = wrap(title);
        if (!lines.isEmpty() && !t.isEmpty()) lines.add("");
        lines.addAll(t);
        int row0 = 11 - lines.size() / 2;
        List<int[]> at = new ArrayList<>();
        List<int[]> tiles = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s.isEmpty()) continue;
            at.add(new int[]{0x2000 + (row0 + i) * 32 + (32 - s.length()) / 2});
            tiles.add(tilesOf(s));
        }
        String hint = "A: PLAY/PAUSE";
        at.add(new int[]{0x2000 + 18 * 32 + (32 - hint.length()) / 2});
        tiles.add(tilesOf(hint));
        at.add(new int[]{timerAddr()});
        tiles.add(tilesOf(clock(0) + " / " + total));
        int[] bar = new int[BAR_CELLS + 2];
        Arrays.fill(bar, T_EMPTY);
        bar[0] = T_LCAP;
        bar[BAR_CELLS + 1] = T_RCAP;
        at.add(new int[]{barAddr()});
        tiles.add(bar);
        for (int i = 0; i < at.size(); i++) {
            int addr = at.get(i)[0];
            a.b(0xA9, addr >> 8); a.w(0x8D, 0x2006); a.b(0xA9, addr & 0xFF); a.w(0x8D, 0x2006);
            for (int v : tiles.get(i)) { a.b(0xA9, v); a.w(0x8D, 0x2007); }
        }

        a.label("vw2"); a.w(0x2C, 0x2002); a.br(0x10, "vw2");
        a.b(0xA9, 0x00); a.w(0x8D, 0x2005); a.w(0x8D, 0x2005); a.w(0x8D, 0x2000);
        a.b(0xA9, 0x0A); a.w(0x8D, 0x2001);           // show background
        a.w(0x20, "rewind");
        a.b(0xA9, 0x00, 0x85, PREV);

        // ---- playback loop: C = 5N+61+15K(+2) cycles per sample, page changes included
        a.label("loop_full"); a.b(0xA2, n);
        a.label("loop_dx"); a.label("dl"); a.b(0xCA); a.br(0xD0, "dl");
        if (nop) a.b(0xEA);
        decodeSample(a, "hitab");
        work(a, k);
        a.b(0xA2, n + 1, 0xEA);                       // ldx #N+1 ; nop
        a.label("dl2"); a.b(0xCA); a.br(0xD0, "dl2");
        if (nop) a.b(0xEA);
        decodeSample(a, "lotab");
        work(a, k);
        a.b(0xC8); a.br(0xF0, "pagestep");            // iny ; beq
        a.w(0x4C, "loop_full");
        a.label("pagestep");
        a.b(0xE6, PTRHI, 0xA5, PTRHI, 0xC9, 0x80 + PAGES_PER_BANK); a.br(0xD0, "nobank");
        a.b(0xA9, 0x80, 0x85, PTRHI, 0xE6, BANK, 0xA6, BANK);
        a.w(0xBD, "banktable"); a.w(0x9D, "banktable"); // bus-conflict-safe bank switch
        a.label("nobank");
        a.b(0xA5, BANK, 0xC9, endBank); a.br(0xD0, "noend");
        a.b(0xA5, PTRHI, 0xC9, endPage); a.br(0xD0, "noend");
        a.w(0x20, "rewind"); a.w(0x4C, "pause");      // song over: rewind and pause
        a.label("noend");
        copyDisplay(a, ops);
        // inline A-button poll (kept inline so the cycle count stays fixed)
        a.b(0xA9, 0x01); a.w(0x8D, 0x4016); a.b(0xA9, 0x00); a.w(0x8D, 0x4016);
        a.w(0xAD, 0x4016); a.b(0x29, 0x01, 0xAA, 0x45, PREV); a.br(0xF0, "nochg");
        a.b(0x86, PREV, 0x8A); a.br(0xD0, "pause");
        a.label("nochg"); a.b(0xEA, 0xEA, 0xA2, n - 23); a.w(0x4C, "loop_dx");

        // ---- paused: poll once per frame, resume on a fresh press of A
        a.label("pause");
        a.label("pv"); a.w(0x2C, 0x2002); a.br(0x10, "pv");
        a.b(0xA9, 0x00); a.w(0x8D, 0x2000); a.w(0x8D, 0x2005); a.w(0x8D, 0x2005);
        a.b(0xA9, 0x01); a.w(0x8D, 0x4016); a.b(0xA9, 0x00); a.w(0x8D, 0x4016);
        a.w(0xAD, 0x4016); a.b(0x29, 0x01, 0xAA, 0x45, PREV); a.br(0xF0, "pause");
        a.b(0x86, PREV, 0x8A); a.br(0xF0, "pause");
        a.b(0xA9, 0x00, 0x85, OPIDX);                 // never resume mid-update
        a.w(0x4C, "loop_full");

        // ---- back to the start: reset state and redraw the bar and timer in vblank
        a.label("rewind");
        a.b(0xA9, 0x00, 0x85, PTRLO, 0x85, BANK, 0x85, OPIDX, 0xAA); a.w(0x9D, "banktable");
        a.b(0xA9, ACC0, 0x85, ACC);
        a.b(0xA9, 0x80, 0x85, PTRHI);
        copyDisplay(a, ops);
        a.label("rwv"); a.w(0x2C, 0x2002); a.br(0x10, "rwv");
        int ba = barAddr(), ta = timerAddr();
        a.b(0xA9, ba >> 8); a.w(0x8D, 0x2006); a.b(0xA9, ba & 0xFF); a.w(0x8D, 0x2006);
        a.b(0xA9, T_LCAP); a.w(0x8D, 0x2007);
        a.b(0xA9, T_EMPTY, 0xA2, BAR_CELLS);
        a.label("rwb"); a.w(0x8D, 0x2007); a.b(0xCA); a.br(0xD0, "rwb");
        a.b(0xA9, T_RCAP); a.w(0x8D, 0x2007);
        a.b(0xA9, ta >> 8); a.w(0x8D, 0x2006); a.b(0xA9, ta & 0xFF); a.w(0x8D, 0x2006);
        for (int v : tilesOf(clock(0))) { a.b(0xA9, v); a.w(0x8D, 0x2007); }
        a.b(0xA9, 0x00); a.w(0x8D, 0x2000); a.w(0x8D, 0x2005); a.w(0x8D, 0x2005);
        a.b(0xA0, 0x00, 0x60);

        a.label("irq"); a.b(0x40);
        a.resolve();
        if (a.len > 0x4000 - 6) throw new IllegalStateException("fixed bank overflow");
        return a;
    }

    static int[] tilesOf(String s) {
        int[] v = new int[s.length()];
        for (int i = 0; i < v.length; i++) v[i] = tile(s.charAt(i));
        return v;
    }

    // lda (ptr),y ; tax ; lda acc ; clc ; adc tab,x ; sta acc ; sta $4011   (23 cycles)
    static void decodeSample(Asm a, String tab) {
        a.b(0xB1, PTRLO, 0xAA, 0xA5, ACC, 0x18); a.w(0x7D, tab);
        a.b(0x85, ACC); a.w(0x8D, 0x4011);
    }

    /**
     * Fixed-length per-slot work (30 + 15K cycles): poll vblank, start the op
     * list if idle, then perform this slot's K PPU writes. Tables are
     * page-aligned so no indexed read crosses a page.
     */
    static void work(Asm a, int k) {
        a.b(0x84, YSAVE);                              // sty ysave
        a.w(0xAD, 0x2002); a.b(0x29, 0x80, 0x05, OPIDX, 0xAA); // vblank | opidx
        a.w(0xBD, "startmap"); a.b(0xAA);
        for (int j = 0; j < k; j++) {
            a.w(0xBD, "opreg", j); a.b(0xA8);          // lda opreg+j,x ; tay
            a.w(0xBD, OPVAL + j);                      // lda opval+j,x
            a.w(0x99, 0x2000);                         // sta $2000,y
        }
        a.w(0xBD, "nextslot"); a.b(0x85, OPIDX, 0xA4, YSAVE);
    }

    /** Copies this page's timer digits and bar head into the op values (63 cycles). */
    static void copyDisplay(Asm a, Ops ops) {
        a.b(0xA5, PTRHI, 0x29, 0x3F, 0xAA);
        for (int t = 0; t < 7; t++) {
            a.w(0xBD, TABLES + 64 * t);
            a.w(0x8D, OPVAL + ops.pos[t]);
        }
    }
}
