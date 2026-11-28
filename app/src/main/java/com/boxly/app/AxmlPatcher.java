package com.boxly.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Replaces one string inside the string pool of a compiled (binary) AndroidManifest.xml.
 * The new string may have any length or characters (e.g. Bengali). Returns null if the pool can't be handled.
 */
public final class AxmlPatcher {
    private AxmlPatcher() {}

    public static byte[] replaceString(byte[] xml, String from, String to) {
        try {
            return doReplace(xml, from, to);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24);
    }

    private static byte[] doReplace(byte[] xml, String from, String to) {
        if (xml.length < 36 || u16(xml, 0) != 0x0003) return null;
        int poolStart = u16(xml, 2);
        if (u16(xml, poolStart) != 0x0001) return null;
        int poolHeader = u16(xml, poolStart + 2);
        int poolSize = u32(xml, poolStart + 4);
        int count = u32(xml, poolStart + 8);
        int styleCount = u32(xml, poolStart + 12);
        int flags = u32(xml, poolStart + 16);
        int stringsStart = u32(xml, poolStart + 20);
        if (styleCount != 0 || count < 0) return null;
        if (stringsStart != poolHeader + count * 4) return null;
        boolean utf8 = (flags & 0x100) != 0;
        int offsetsPos = poolStart + poolHeader - 0; // header already includes fixed fields
        offsetsPos = poolStart + poolHeader;
        int dataPos = poolStart + stringsStart;

        List<byte[]> entries = new ArrayList<>();
        int target = -1;
        for (int i = 0; i < count; i++) {
            int start = dataPos + u32(xml, offsetsPos + i * 4);
            int p = start;
            int end;
            String value;
            if (utf8) {
                int[] l1 = len8(xml, p); p += l1[1];
                int[] l2 = len8(xml, p); p += l2[1];
                value = new String(xml, p, l2[0], StandardCharsets.UTF_8);
                end = p + l2[0] + 1;
            } else {
                int[] l1 = len16(xml, p); p += l1[1];
                value = new String(xml, p, l1[0] * 2, StandardCharsets.UTF_16LE);
                end = p + l1[0] * 2 + 2;
            }
            byte[] raw = new byte[end - start];
            System.arraycopy(xml, start, raw, 0, raw.length);
            entries.add(raw);
            if (target < 0 && value.equals(from)) target = i;
        }
        if (target < 0) return null;
        entries.set(target, encode(to, utf8));

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = data.size();
            byte[] e = entries.get(i);
            data.write(e, 0, e.length);
        }
        while (data.size() % 4 != 0) data.write(0);
        byte[] d = data.toByteArray();

        int newPoolSize = stringsStart + d.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(xml, 0, poolStart);
        byte[] header = new byte[poolHeader];
        System.arraycopy(xml, poolStart, header, 0, poolHeader);
        put32(header, 4, newPoolSize);
        out.write(header, 0, header.length);
        byte[] off = new byte[count * 4];
        for (int i = 0; i < count; i++) put32(off, i * 4, offsets[i]);
        out.write(off, 0, off.length);
        out.write(d, 0, d.length);
        int restStart = poolStart + poolSize;
        out.write(xml, restStart, xml.length - restStart);
        byte[] res = out.toByteArray();
        put32(res, 4, res.length);
        return res;
    }

    /** returns {value, bytesUsed} */
    private static int[] len8(byte[] b, int o) {
        int v = b[o] & 0xFF;
        if ((v & 0x80) != 0) return new int[]{((v & 0x7F) << 8) | (b[o + 1] & 0xFF), 2};
        return new int[]{v, 1};
    }

    private static int[] len16(byte[] b, int o) {
        int v = u16(b, o);
        if ((v & 0x8000) != 0) return new int[]{((v & 0x7FFF) << 16) | u16(b, o + 2), 4};
        return new int[]{v, 2};
    }

    private static void writeLen8(ByteArrayOutputStream o, int n) {
        if (n > 0x7F) { o.write((n >> 8) | 0x80); o.write(n & 0xFF); } else o.write(n);
    }

    private static void writeLen16(ByteArrayOutputStream o, int n) {
        if (n > 0x7FFF) {
            int hi = (n >> 16) | 0x8000;
            o.write(hi & 0xFF); o.write(hi >> 8);
            o.write(n & 0xFF); o.write((n >> 8) & 0xFF);
        } else { o.write(n & 0xFF); o.write(n >> 8); }
    }

    private static byte[] encode(String s, boolean utf8) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (utf8) {
            byte[] d = s.getBytes(StandardCharsets.UTF_8);
            writeLen8(o, s.length());
            writeLen8(o, d.length);
            o.write(d, 0, d.length);
            o.write(0);
        } else {
            writeLen16(o, s.length());
            byte[] d = s.getBytes(StandardCharsets.UTF_16LE);
            o.write(d, 0, d.length);
            o.write(0); o.write(0);
        }
        return o.toByteArray();
    }
}
