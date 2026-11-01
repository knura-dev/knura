package io.knura.core.admin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A small QR code encoder (ISO/IEC 18004), so knurd can show a pairing code on the setup page
 * without a third-party library. Byte mode, error correction level M, versions 1 to 40, and the
 * mask with the lowest penalty, as the standard describes. Based on Project Nayuki's public
 * description of the algorithm.
 */
public final class QrCode {
    // Per version (index 0 unused), for level M.
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28,
        28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28};
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17,
        18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49};
    private static final int FORMAT_BITS_M = 0;

    private final int version;
    private final int size;
    private final boolean[][] modules; // [y][x], true is dark
    private final boolean[][] function;

    private QrCode(int version) {
        this.version = version;
        this.size = version * 4 + 17;
        this.modules = new boolean[size][size];
        this.function = new boolean[size][size];
    }

    /** The modules of a QR code holding {@code text} as UTF-8: {@code [y][x]}, true is dark, no quiet zone. */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (true) {
            int countBits = version < 10 ? 8 : 16;
            if (data.length < (1 << countBits) && 4 + countBits + data.length * 8 <= dataCodewords(version) * 8) {
                break;
            }
            if (++version > 40) {
                throw new IllegalArgumentException("too much text for a QR code: " + data.length + " bytes");
            }
        }
        QrCode qr = new QrCode(version);
        qr.drawFunctionPatterns();
        qr.drawCodewords(qr.withEcc(qr.dataBits(data)));
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            qr.applyMask(mask);
            qr.drawFormatBits(mask);
            long p = qr.penalty();
            if (p < bestPenalty) {
                best = mask;
                bestPenalty = p;
            }
            qr.applyMask(mask); // XOR again undoes it
        }
        qr.applyMask(best);
        qr.drawFormatBits(best);
        return qr.modules;
    }

    // ---- data ----

    private byte[] dataBits(byte[] data) {
        int capacity = dataCodewords(version) * 8;
        Bits bits = new Bits();
        bits.add(0b0100, 4); // byte mode
        bits.add(data.length, version < 10 ? 8 : 16);
        for (byte b : data) {
            bits.add(b & 0xFF, 8);
        }
        bits.add(0, Math.min(4, capacity - bits.length));
        bits.add(0, (8 - bits.length % 8) % 8);
        for (int pad = 0xEC; bits.length < capacity; pad ^= 0xEC ^ 0x11) {
            bits.add(pad, 8);
        }
        return bits.bytes();
    }

    private byte[] withEcc(byte[] data) {
        int blocks = BLOCKS[version];
        int eccLen = ECC_PER_BLOCK[version];
        int raw = rawModules(version) / 8;
        int shortBlocks = blocks - raw % blocks;
        int shortLen = raw / blocks;
        byte[][] all = new byte[blocks][];
        byte[] divisor = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < blocks; i++) {
            int datLen = shortLen - eccLen + (i < shortBlocks ? 0 : 1);
            byte[] dat = java.util.Arrays.copyOfRange(data, k, k + datLen);
            k += datLen;
            byte[] ecc = rsRemainder(dat, divisor);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, datLen);
            // Short blocks leave a gap before their ECC, so every block's ECC lines up.
            System.arraycopy(ecc, 0, block, shortLen + 1 - eccLen, eccLen);
            all[i] = block;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw);
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - eccLen || j >= shortBlocks) {
                    out.write(all[j][i]);
                }
            }
        }
        return out.toByteArray();
    }

    static int rawModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int align = ver / 7 + 2;
            result -= (25 * align - 10) * align - 55;
            if (ver >= 7) {
                result -= 36;
            }
        }
        return result;
    }

    static int dataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver];
    }

    // ---- Reed-Solomon over GF(2^8) with x^8 + x^4 + x^3 + x^2 + 1 ----

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) {
                    result[j] ^= result[j + 1];
                }
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---- drawing ----

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] align = alignmentPositions();
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            set(align[i] + dx, align[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                        }
                    }
                }
            }
        }
        drawFormatBits(0); // reserves the area; drawn for real once the mask is chosen
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) {
                rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            }
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = (bits >>> i & 1) != 0;
                int a = size - 11 + i % 3;
                int b = i / 3;
                set(a, b, bit);
                set(b, a, bit);
            }
        }
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx;
                int yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) {
                    set(xx, yy, dist != 2 && dist != 4);
                }
            }
        }
    }

    private int[] alignmentPositions() {
        if (version == 1) {
            return new int[0];
        }
        int n = version / 7 + 2;
        int step = version == 32 ? 26 : (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] result = new int[n];
        result[0] = 6;
        for (int i = n - 1, pos = size - 7; i >= 1; i--, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    private void drawFormatBits(int mask) {
        int data = FORMAT_BITS_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) {
            set(8, i, bit(bits, i));
        }
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) {
            set(14 - i, 8, bit(bits, i));
        }
        for (int i = 0; i < 8; i++) {
            set(size - 1 - i, 8, bit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            set(8, size - 15 + i, bit(bits, i));
        }
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) {
        return (x >>> i & 1) != 0;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert = switch (mask) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (x + y) % 3 == 0;
                    case 4 -> (x / 3 + y / 2) % 2 == 0;
                    case 5 -> x * y % 2 + x * y % 3 == 0;
                    case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                    default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
                };
                if (invert && !function[y][x]) {
                    modules[y][x] = !modules[y][x];
                }
            }
        }
    }

    /** The standard's penalty score: lower scans better. */
    private long penalty() {
        long result = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 0;
                boolean last = false;
                int window = 0; // the last 11 modules of this line, newest in bit 0
                for (int b = 0; b < size; b++) {
                    boolean dark = pass == 0 ? modules[a][b] : modules[b][a];
                    if (b > 0 && dark == last) {
                        run++;
                        if (run == 5) {
                            result += 3;
                        } else if (run > 5) {
                            result++;
                        }
                    } else {
                        run = 1;
                        last = dark;
                    }
                    window = (window << 1 | (dark ? 1 : 0)) & 0x7FF;
                    if (b >= 10 && (window == 0b10111010000 || window == 0b00001011101)) {
                        result += 40;
                    }
                }
            }
        }
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean c = modules[y][x];
                if (c) {
                    dark++;
                }
                if (x + 1 < size && y + 1 < size && c == modules[y][x + 1] && c == modules[y + 1][x]
                        && c == modules[y + 1][x + 1]) {
                    result += 3;
                }
            }
        }
        int total = size * size;
        long k = (Math.abs(dark * 20L - total * 10L) + total - 1) / total - 1;
        return result + k * 10;
    }

    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        int length;

        void add(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                current = current << 1 | (value >>> i & 1);
                length++;
                if (length % 8 == 0) {
                    out.write(current);
                    current = 0;
                }
            }
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
