package dev.yg.skyboxblock.custom;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Inverse PIZ transforms for one scanline block or tile with unit channel sampling. */
// Wavelet and Huffman format adapted from OpenEXR v3.4.4 (internal_piz.c, ImfHuf.cpp).
// Copyright (c) Contributors to the OpenEXR Project. BSD-3-Clause; see LICENSES/OpenEXR.txt.
final class OpenExrPiz {
    private static final int WORD_VALUES = 1 << 16;
    private static final int BITMAP_BYTES = WORD_VALUES / 8;
    private static final int MAX_CODE_BITS = 58;
    private static final int FAST_BITS = 10;

    private OpenExrPiz() { }

    static byte[] decode(byte[] chunk, int expected, int width, int height, int[] channelWords) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
        if (in.remaining() < 8) throw invalid("缺少范围表头");
        int first = Short.toUnsignedInt(in.getShort());
        int last = Short.toUnsignedInt(in.getShort());
        int bitmapSize = first <= last ? last - first + 1 : 0;
        if (first >= BITMAP_BYTES || last >= BITMAP_BYTES || in.remaining() < bitmapSize + 4)
            throw invalid("范围位图越界");
        char[] values = new char[Math.min(WORD_VALUES, bitmapSize * 8 + 1)];
        int maxValue = 0; // zero is implicit
        for (int i = 0; i < bitmapSize; i++) {
            int index = first + i;
            int set = (in.get() & 255) & (index == 0 ? 254 : 255);
            while (set != 0) {
                values[++maxValue] = (char) (index * 8 + Integer.numberOfTrailingZeros(set));
                set &= set - 1;
            }
        }
        int huffmanSize = in.getInt();
        if (huffmanSize != in.remaining() || huffmanSize < 20) throw invalid("Huffman 数据长度错误");
        char[] words = huffman(in, expected / 2);

        // PIZ stores one full plane per channel, with separate wavelets for the two words of 32-bit samples.
        boolean narrow = maxValue < (1 << 14);
        int start = 0;
        for (int stride : channelWords) {
            for (int word = 0; word < stride; word++) inverseWavelet(words, start + word, width, height, stride, narrow);
            start += width * height * stride;
        }
        byte[] raw = new byte[expected];
        int out = 0;
        for (int y = 0; y < height; y++) {
            start = 0;
            for (int stride : channelWords) {
                int rowWords = width * stride;
                for (int p = start + y * rowWords, end = p + rowWords; p < end; p++) {
                    int index = words[p];
                    if (index > maxValue) throw invalid("反变换后的范围索引越界");
                    int value = values[index];
                    raw[out++] = (byte) value;
                    raw[out++] = (byte) (value >>> 8);
                }
                start += rowWords * height;
            }
        }
        return raw;
    }

    private static char[] huffman(ByteBuffer in, int wordCount) throws IOException {
        int first = in.getInt();
        int repeat = in.getInt(); // largest symbol is the run-length marker, not a sample
        int tableBytes = in.getInt();
        long dataBits = Integer.toUnsignedLong(in.getInt());
        in.getInt(); // reserved
        if (first < 0 || repeat <= first || repeat > WORD_VALUES || tableBytes <= 0
                || tableBytes + (dataBits + 7) / 8 != in.remaining() || dataBits == 0)
            throw invalid("Huffman 表头错误");

        Bits table = new Bits(in.array(), in.position(), tableBytes * 8L);
        byte[] lengths = new byte[repeat - first + 1];
        int[] counts = new int[MAX_CODE_BITS + 1];
        for (int i = 0; i < lengths.length;) {
            int length = table.take(6);
            if (length > MAX_CODE_BITS) {
                int zeros = length == 63 ? table.take(8) + 6 : length - 57;
                if (zeros > lengths.length - i) throw invalid("Huffman 码长表越界");
                i += zeros;
            } else {
                lengths[i++] = (byte) length;
                if (length != 0) counts[length]++;
            }
        }
        if (table.remaining >= 8 || lengths[0] == 0 || lengths[lengths.length - 1] == 0)
            throw invalid("Huffman 码长表长度错误");

        // OpenEXR assigns canonical codes from longest to shortest, then by symbol for equal lengths.
        long[] base = new long[MAX_CODE_BITS + 1];
        int[] index = new int[MAX_CODE_BITS + 1];
        int numberOfCodes = 0;
        long next = 0;
        for (int length = MAX_CODE_BITS; length > 0; length--) {
            base[length] = next;
            next += counts[length];
            if ((next & 1) != 0 || next > (1L << length)) throw invalid("Huffman 码树无效");
            next >>>= 1;
            numberOfCodes += counts[length];
        }
        if (next != 1) throw invalid("Huffman 码树为空");
        for (int length = 2; length <= MAX_CODE_BITS; length++) index[length] = index[length - 1] + counts[length - 1];
        int[] used = new int[MAX_CODE_BITS + 1];
        int[] symbols = new int[numberOfCodes];
        int[] lookup = new int[1 << FAST_BITS];
        int longest = 0;
        for (int i = 0; i < lengths.length; i++) {
            int length = lengths[i];
            if (length == 0) continue;
            int rank = used[length]++;
            int symbol = i + first;
            symbols[index[length] + rank] = symbol;
            longest = Math.max(longest, length);
            if (length <= FAST_BITS) {
                int shift = FAST_BITS - length;
                int prefix = (int) (base[length] + rank) << shift;
                Arrays.fill(lookup, prefix, prefix + (1 << shift), (symbol << 6) | length);
            }
        }

        Bits data = new Bits(in.array(), in.position() + tableBytes, dataBits);
        char[] out = new char[wordCount];
        int pos = 0;
        while (data.remaining > 0) {
            int entry = data.remaining >= FAST_BITS ? lookup[data.peek(FAST_BITS)] : 0;
            int symbol;
            if (entry != 0) {
                data.drop(entry & 63);
                symbol = entry >>> 6;
            } else {
                int matched = -1;
                int length = 0;
                long code = 0;
                if (data.remaining >= FAST_BITS) {
                    length = FAST_BITS;
                    code = data.take(FAST_BITS); // this prefix has no short match
                }
                while (length < longest && data.remaining > 0) {
                    code = (code << 1) | data.take(1);
                    long rank = code - base[++length];
                    if (rank >= 0 && rank < counts[length]) {
                        matched = symbols[index[length] + (int) rank];
                        break;
                    }
                }
                if (matched < 0) throw invalid("Huffman 编码无效");
                symbol = matched;
            }
            if (symbol == repeat) {
                int count = data.take(8);
                if (pos == 0 || count > out.length - pos) throw invalid("Huffman 重复长度越界");
                Arrays.fill(out, pos, pos + count, out[pos - 1]);
                pos += count;
            } else {
                if (pos == out.length) throw invalid("Huffman 解码数据过长");
                out[pos++] = (char) symbol;
            }
        }
        if (pos != out.length) throw invalid("Huffman 解码数据截断");
        return out;
    }

    private static void inverseWavelet(char[] words, int start, int width, int height, int stride, boolean narrow) {
        int row = width * stride;
        for (int step = Integer.highestOneBit(Math.min(width, height)); step > 1; step >>>= 1) {
            int half = step >>> 1, dx = half * stride, dy = half * row;
            int y = 0;
            for (; y <= height - step; y += step) {
                int x = 0;
                int base = start + y * row;
                for (; x <= width - step; x += step) {
                    int a = base + x * stride, b = a + dx, c = a + dy, d = c + dx;
                    inversePair(words, a, c, narrow);
                    inversePair(words, b, d, narrow);
                    inversePair(words, a, b, narrow);
                    inversePair(words, c, d, narrow);
                }
                if ((width & half) != 0) {
                    int a = base + x * stride;
                    inversePair(words, a, a + dy, narrow);
                }
            }
            if ((height & half) != 0) {
                int base = start + y * row;
                for (int x = 0; x <= width - step; x += step) {
                    int a = base + x * stride;
                    inversePair(words, a, a + dx, narrow);
                }
            }
        }
    }

    private static void inversePair(char[] words, int a, int b, boolean narrow) {
        if (narrow) {
            int delta = (short) words[b];
            int first = (short) words[a] + ((delta + 1) >> 1);
            words[a] = (char) first;
            words[b] = (char) (first - delta);
        } else {
            int delta = words[b];
            char second = (char) (words[a] - (delta >>> 1));
            words[a] = (char) (delta + second - 32768);
            words[b] = second;
        }
    }

    private static IOException invalid(String message) { return new IOException("OpenEXR PIZ：" + message); }

    /** MSB-first bit stream; padding bits in the final byte are not readable. */
    private static final class Bits {
        final byte[] bytes;
        int pos, pending, buffer;
        long remaining;

        Bits(byte[] bytes, int pos, long remaining) {
            this.bytes = bytes;
            this.pos = pos;
            this.remaining = remaining;
        }

        int peek(int bits) throws IOException {
            if (bits > remaining) throw invalid("Huffman 比特流截断");
            while (pending < bits) {
                buffer = (buffer << 8) | (bytes[pos++] & 255);
                pending += 8;
            }
            return (buffer >>> (pending - bits)) & ((1 << bits) - 1);
        }

        void drop(int bits) { pending -= bits; remaining -= bits; }
        int take(int bits) throws IOException { int value = peek(bits); drop(bits); return value; }
    }
}
