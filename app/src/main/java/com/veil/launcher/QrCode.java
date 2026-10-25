package com.veil.launcher;

import android.graphics.Bitmap;

import java.util.Arrays;

/**
 * Minimal QR encoder — zero dependencies, fixed parameters: version 3 (29x29), EC level M,
 * byte mode, mask 0, a single RS block (44 data + 26 ecc codewords). That carries URLs up to
 * 42 bytes, which covers the wallpaper upload link exactly.
 *
 * Ported line-for-line from a Python simulation that was validated two ways before porting:
 * module-for-module identical to the qrcode library's output (mask 0, optimize=0), and a
 * pyzbar decode round-trip back to the source URL. Keep the port in sync with that sim.
 */
final class QrCode {

    private static final int SIZE = 29;
    private static final int DATA_CW = 44;
    private static final int ECC_CW = 26;

    // GF(256) with poly 0x11D, plus log/exp tables for cheap multiplication.
    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private QrCode() {
    }

    /** Encodes the text into a white-backed black QR bitmap (4-module quiet zone). */
    static Bitmap encode(String text) {
        return encode(text, 8);
    }

    static Bitmap encode(String text, int scale) {
        boolean[][] m = build(text);
        int quiet = 4;
        int dim = (SIZE + quiet * 2) * scale;
        int[] px = new int[dim * dim];
        Arrays.fill(px, 0xFFFFFFFF);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (!m[y][x]) continue;
                int x0 = (x + quiet) * scale;
                int y0 = (y + quiet) * scale;
                for (int yy = 0; yy < scale; yy++) {
                    int row = (y0 + yy) * dim;
                    Arrays.fill(px, row + x0, row + x0 + scale, 0xFF000000);
                }
            }
        }
        return Bitmap.createBitmap(px, dim, dim, Bitmap.Config.ARGB_8888);
    }

    // ---- codewords ------------------------------------------------------------------------------

    private static byte[] makeBits(String text) {
        byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (data.length > 42) throw new IllegalArgumentException("v3 byte capacity is 42");
        StringBuilder bits = new StringBuilder(352);
        bits.append("0100");
        bits.append(pad8(data.length));
        for (byte b : data) bits.append(pad8(b & 0xFF));
        for (int i = 0, n = Math.min(4, DATA_CW * 8 - bits.length()); i < n; i++) bits.append('0');
        while (bits.length() % 8 != 0) bits.append('0');
        String[] pad = {"11101100", "00010001"};
        int i = 0;
        while (bits.length() < DATA_CW * 8) bits.append(pad[i++ % 2]);
        byte[] cw = new byte[DATA_CW];
        for (int k = 0; k < DATA_CW; k++)
            cw[k] = (byte) Integer.parseInt(bits.substring(k * 8, k * 8 + 8), 2);
        byte[] out = new byte[DATA_CW + ECC_CW];
        System.arraycopy(cw, 0, out, 0, DATA_CW);
        int[] ecc = rsEncode(cw, ECC_CW);
        for (int k = 0; k < ECC_CW; k++) out[DATA_CW + k] = (byte) ecc[k];
        return out;
    }

    private static String pad8(int v) {
        String s = Integer.toBinaryString(v);
        while (s.length() < 8) s = "0" + s;
        return s.length() > 8 ? s.substring(s.length() - 8) : s;
    }

    /** Reed-Solomon generator polynomial, highest degree first, gen[0] == 1. */
    private static int[] rsGenerator(int degree) {
        int[] poly = {1};
        for (int i = 0; i < degree; i++) {
            int[] next = new int[poly.length + 1];
            for (int j = 0; j < poly.length; j++) {
                next[j] ^= gmul(poly[j], EXP[i]);
                next[j + 1] ^= poly[j];
            }
            poly = next;
        }
        return poly;
    }

    private static int[] rsEncode(byte[] data, int degree) {
        int[] g = rsGenerator(degree);
        int[] gen = new int[degree + 1];           // reversed: constant term first
        for (int i = 0; i <= degree; i++) gen[i] = g[degree - i];
        int[] res = new int[degree];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ res[0];
            System.arraycopy(res, 1, res, 0, degree - 1);
            res[degree - 1] = 0;
            for (int i = 1; i <= degree; i++) res[i - 1] ^= gmul(gen[i], factor);
        }
        return res;
    }

    private static int gmul(int a, int b) {
        return (a == 0 || b == 0) ? 0 : EXP[LOG[a] + LOG[b]];
    }

    // ---- matrix ---------------------------------------------------------------------------------

    private static boolean[][] build(String text) {
        byte[] cw = makeBits(text);
        boolean[][] mod = new boolean[SIZE][SIZE];
        boolean[][] func = new boolean[SIZE][SIZE];

        for (int i = 0; i < SIZE; i++) {
            setm(mod, func, 6, i, i % 2 == 0);
            setm(mod, func, i, 6, i % 2 == 0);
        }
        finder(mod, func, 3, 3);
        finder(mod, func, SIZE - 4, 3);
        finder(mod, func, 3, SIZE - 4);
        align(mod, func, 22, 22);
        drawFormat(mod, func);   // placeholder pass: marks format cells as function modules
                                 // BEFORE the zigzag, so codewords never land in them.

        int bitI = 0;
        int total = cw.length * 8;
        int right = SIZE - 1;
        while (right >= 1) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < SIZE; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? SIZE - 1 - vert : vert;
                    if (!func[y][x]) {
                        boolean dark = bitI < total
                                && ((cw[bitI >> 3] >> (7 - (bitI & 7))) & 1) == 1;
                        bitI++;
                        mod[y][x] = dark ^ ((x + y) % 2 == 0);   // mask 0: (x+y)%2==0 inverts
                    }
                }
            }
            right -= 2;
        }

        drawFormat(mod, func);   // final values (function modules are never masked)
        return mod;
    }

    private static void setm(boolean[][] mod, boolean[][] func, int x, int y, boolean dark) {
        if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) {
            mod[y][x] = dark;
            func[y][x] = true;
        }
    }

    private static void finder(boolean[][] mod, boolean[][] func, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                setm(mod, func, cx + dx, cy + dy, d != 2 && d != 4);
            }
        }
    }

    private static void align(boolean[][] mod, boolean[][] func, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setm(mod, func, cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    /** Format info for EC level M (formatBits 0) + mask 0, drawn at both standard copies. */
    private static void drawFormat(boolean[][] mod, boolean[][] func) {
        int mask = 0;
        int data5 = mask;
        int rem = data5;
        for (int i = 0; i < 10; i++)
            rem = (rem << 1) ^ ((rem & (1 << 9)) != 0 ? 0x537 : 0);
        int bits15 = ((data5 << 10) | rem) ^ 0x5412;

        // Nayuki's getBit is LSB-first. First copy runs VERTICAL at column 8 for bits 0-7,
        // then (7,8), then bits 9-14 horizontal at row 8 (x = 14-i -> 5..0).
        for (int i = 0; i < 6; i++) setm(mod, func, 8, i, bit(bits15, i));
        setm(mod, func, 8, 7, bit(bits15, 6));
        setm(mod, func, 8, 8, bit(bits15, 7));
        setm(mod, func, 7, 8, bit(bits15, 8));
        for (int i = 9; i < 15; i++) setm(mod, func, 14 - i, 8, bit(bits15, i));
        // Second copy: bits 0-7 horizontal at row 8 right side (x = size-1-i),
        // bits 8-14 vertical at column 8 bottom (y = size-15+i -> 21..27).
        for (int i = 0; i < 8; i++) setm(mod, func, SIZE - 1 - i, 8, bit(bits15, i));
        for (int i = 8; i < 15; i++) setm(mod, func, 8, SIZE - 15 + i, bit(bits15, i));
        setm(mod, func, 8, SIZE - 8, true);   // dark module
    }

    private static boolean bit(int bits15, int i) {
        return ((bits15 >> i) & 1) == 1;
    }
}
