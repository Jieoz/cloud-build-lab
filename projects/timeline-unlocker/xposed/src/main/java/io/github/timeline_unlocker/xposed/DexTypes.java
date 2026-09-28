package io.github.timeline_unlocker.xposed;

import java.nio.charset.StandardCharsets;

/** Reads a dex type-id table. Used to see whether Maps shipped a Timeline class. */
final class DexTypes {

    private DexTypes() {}

    static java.util.List<String> descriptorsContaining(byte[] dex, String needle, int limit) {
        java.util.List<String> found = new java.util.ArrayList<>();
        if (dex == null || dex.length < 0x74 || needle == null || needle.isEmpty() || limit <= 0) {
            return found;
        }
        int stringIds = u32(dex, 0x38);
        int stringOff = u32(dex, 0x3c);
        int typeIds = u32(dex, 0x40);
        int typeOff = u32(dex, 0x44);
        if (stringIds < 0 || typeIds < 0) return found;
        for (int i = 0; i < typeIds && found.size() < limit; i++) {
            int typePos = typeOff + i * 4;
            if (typePos < 0 || typePos + 4 > dex.length) break;
            int descIdx = u32(dex, typePos);
            if (descIdx < 0 || descIdx >= stringIds) continue;
            String desc = string(dex, stringOff, descIdx);
            if (desc.contains(needle)) found.add(desc);
        }
        return found;
    }

    static int countDescriptorContaining(byte[] dex, String needle) {
        return descriptorsContaining(dex, needle, Integer.MAX_VALUE).size();
    }

    private static String string(byte[] dex, int stringIdsOff, int index) {
        int pos = stringIdsOff + index * 4;
        if (pos < 0 || pos + 4 > dex.length) return "";
        int off = u32(dex, pos);
        if (off < 0 || off >= dex.length) return "";
        int i = off;
        int shift = 0;
        int len = 0;
        while (i < dex.length) {
            int b = dex[i++] & 0xff;
            len |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        int end = i;
        int seen = 0;
        while (end < dex.length && seen < len && dex[end] != 0) {
            end++;
            seen++;
        }
        return new String(dex, i, end - i, StandardCharsets.UTF_8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }
}
