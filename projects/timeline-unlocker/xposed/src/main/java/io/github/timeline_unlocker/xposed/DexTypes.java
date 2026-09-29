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
            if (isMapsTimeline(desc, needle)) found.add(desc);
        }
        return found;
    }

    static int countDescriptorContaining(byte[] dex, String needle) {
        return descriptorsContaining(dex, needle, Integer.MAX_VALUE).size();
    }

    /**
     * A Maps Timeline class lives under a Google package. Framework types such as
     * {@code android.view.Choreographer$FrameTimeline} contain the same word and are not the screen.
     */
    static boolean isMapsTimeline(String descriptor, String needle) {
        if (descriptor == null || needle == null || !descriptor.contains(needle)) return false;
        return descriptor.startsWith("Lcom/google/")
                || descriptor.startsWith("Lcom/google/android/apps/maps/");
    }

    private static String typeName(byte[] dex, int stringOff, int typeOff, int typeIdx) {
        int pos = typeOff + typeIdx * 4;
        if (pos < 0 || pos + 4 > dex.length) return "?";
        return string(dex, stringOff, u32(dex, pos));
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

    /**
     * Methods whose bytecode contains {@code needle}. Used once at startup to see who can
     * construct TimelineWrapper. Does not execute those methods.
     */
    static java.util.List<String> methodsContaining(byte[] dex, String needle, int limit) {
        java.util.List<String> found = new java.util.ArrayList<>();
        if (dex == null || dex.length < 0x74 || needle == null || needle.isEmpty() || limit <= 0) {
            return found;
        }
        int stringIds = u32(dex, 0x38);
        int stringOff = u32(dex, 0x3c);
        int typeOff = u32(dex, 0x44);
        int methodIds = u32(dex, 0x58);
        int methodOff = u32(dex, 0x5c);
        int classDefs = u32(dex, 0x60);
        int classOff = u32(dex, 0x64);
        if (stringIds < 0 || methodIds < 0 || classDefs < 0) return found;
        java.util.List<Integer> targets = new java.util.ArrayList<>();
        for (int i = 0; i < methodIds; i++) {
            int pos = methodOff + i * 8;
            if (pos < 0 || pos + 8 > dex.length) break;
            String owner = typeName(dex, stringOff, typeOff, u16(dex, pos));
            String name = string(dex, stringOff, u32(dex, pos + 4));
            if (owner.contains(needle) && ("<init>".equals(name) || "<clinit>".equals(name))) {
                targets.add(i);
            }
        }
        if (targets.isEmpty()) return found;
        boolean[] hit = new boolean[methodIds];
        for (int c = 0; c < classDefs; c++) {
            int classPos = classOff + c * 32;
            if (classPos < 0 || classPos + 32 > dex.length) break;
            int classDataOff = u32(dex, classPos + 24);
            if (classDataOff <= 0 || classDataOff >= dex.length) continue;
            int[] cursor = new int[]{classDataOff};
            int staticFields = uleb(dex, cursor);
            int instanceFields = uleb(dex, cursor);
            int directMethods = uleb(dex, cursor);
            int virtualMethods = uleb(dex, cursor);
            skipEncodedFields(dex, cursor, staticFields + instanceFields);
            markMethods(dex, cursor, directMethods, targets, hit);
            markMethods(dex, cursor, virtualMethods, targets, hit);
        }
        for (int i = 0; i < methodIds && found.size() < limit; i++) {
            if (!hit[i]) continue;
            int pos = methodOff + i * 8;
            if (pos < 0 || pos + 8 > dex.length) continue;
            int classIdx = u16(dex, pos);
            int nameIdx = u32(dex, pos + 4);
            found.add(typeName(dex, stringOff, typeOff, classIdx) + "->" + string(dex, stringOff, nameIdx));
        }
        return found;
    }

    private static boolean calls(byte[] dex, int start, int end, java.util.List<Integer> targets) {
        for (int i = start; i + 6 <= end; i += 2) {
            int op = dex[i] & 0xff;
            if ((op >= 0x6e && op <= 0x72) || op == 0x74 || op == 0x75 || op == 0x76 || op == 0x78) {
                int idx = (dex[i + 2] & 0xff) | ((dex[i + 3] & 0xff) << 8);
                if (targets.contains(idx)) return true;
            }
        }
        return false;
    }

    private static void markMethods(byte[] dex, int[] cursor, int count, java.util.List<Integer> targets, boolean[] hit) {
        int methodIdx = 0;
        for (int i = 0; i < count; i++) {
            if (cursor[0] >= dex.length) return;
            methodIdx += uleb(dex, cursor);
            uleb(dex, cursor);
            int codeOff = uleb(dex, cursor);
            if (methodIdx < 0 || methodIdx >= hit.length || codeOff <= 0 || codeOff >= dex.length) continue;
            int insns = u32(dex, codeOff + 12);
            int start = codeOff + 16;
            int end = Math.min(dex.length, start + insns * 2);
            if (calls(dex, start, end, targets)) hit[methodIdx] = true;
        }
    }

    private static void skipEncodedFields(byte[] dex, int[] cursor, int count) {
        for (int i = 0; i < count && cursor[0] < dex.length; i++) {
            uleb(dex, cursor);
            uleb(dex, cursor);
        }
    }

    private static int uleb(byte[] dex, int[] cursor) {
        int result = 0;
        int shift = 0;
        while (cursor[0] < dex.length) {
            int b = dex[cursor[0]++] & 0xff;
            result |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private static int indexOf(byte[] data, int start, int end, byte[] needle) {
        int last = end - needle.length;
        for (int i = start; i <= last; i++) {
            boolean ok = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) return i;
        }
        return -1;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xff)
                | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16)
                | ((b[off + 3] & 0xff) << 24);
    }
}
