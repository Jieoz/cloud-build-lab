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
     * One line per method that invokes a constructor of a class whose name contains {@code needle}:
     * caller, then the method names it invokes. Does not execute them.
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
        java.util.List<Integer> called = new java.util.ArrayList<>();
        for (int c = 0; c < classDefs && found.size() < limit; c++) {
            int classPos = classOff + c * 32;
            if (classPos < 0 || classPos + 32 > dex.length) break;
            int classDataOff = u32(dex, classPos + 24);
            if (classDataOff <= 0 || classDataOff >= dex.length) continue;
            int[] cursor = new int[]{classDataOff};
            skipEncodedFields(dex, cursor, uleb(dex, cursor) + uleb(dex, cursor));
            int directMethods = uleb(dex, cursor);
            int virtualMethods = uleb(dex, cursor);
            markMethods(dex, cursor, directMethods, targets, found, called, stringOff, typeOff, methodOff);
            markMethods(dex, cursor, virtualMethods, targets, found, called, stringOff, typeOff, methodOff);
        }
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    private static void markMethods(byte[] dex, int[] cursor, int count, java.util.List<Integer> targets,
                                    java.util.List<String> found, java.util.List<Integer> called,
                                    int stringOff, int typeOff, int methodOff) {
        int methodIdx = 0;
        for (int i = 0; i < count; i++) {
            if (cursor[0] >= dex.length) return;
            methodIdx += uleb(dex, cursor);
            uleb(dex, cursor);
            int codeOff = uleb(dex, cursor);
            if (methodIdx < 0 || codeOff <= 0 || codeOff >= dex.length) continue;
            int insns = u32(dex, codeOff + 12);
            int start = codeOff + 16;
            int end = Math.min(dex.length, start + insns * 2);
            int before = called.size();
            if (!calls(dex, start, end, targets, called)) continue;
            int pos = methodOff + methodIdx * 8;
            if (pos < 0 || pos + 8 > dex.length) continue;
            String caller = typeName(dex, stringOff, typeOff, u16(dex, pos))
                    + "->" + string(dex, stringOff, u32(dex, pos + 4));
            java.util.List<String> names = new java.util.ArrayList<>();
            for (int n = before; n < called.size(); n++) {
                int ipos = methodOff + called.get(n) * 8;
                if (ipos < 0 || ipos + 8 > dex.length) continue;
                names.add(string(dex, stringOff, u32(dex, ipos + 4)));
            }
            called.clear();
            found.add(caller + " calls " + names);
        }
    }

    private static boolean calls(byte[] dex, int start, int end, java.util.List<Integer> targets,
                                 java.util.List<Integer> invoked) {
        for (int i = start; i + 2 <= end; ) {
            int op = dex[i] & 0xff;
            if (op >= 0x0e && op <= 0x11) break;
            int width = opWidth(op);
            if (width < 2) width = 2;
            if ((op >= 0x6e && op <= 0x72) || op == 0x74 || op == 0x75 || op == 0x76 || op == 0x78) {
                if (i + 4 <= end) {
                    int idx = (dex[i + 2] & 0xff) | ((dex[i + 3] & 0xff) << 8);
                    if (targets.contains(idx)) {
                        collectInvokes(dex, start, end, invoked);
                        return true;
                    }
                }
            }
            i += width;
        }
        return false;
    }

    private static void collectInvokes(byte[] dex, int start, int end, java.util.List<Integer> invoked) {
        for (int i = start; i + 2 <= end && invoked.size() < 24; ) {
            int op = dex[i] & 0xff;
            if (op >= 0x0e && op <= 0x11) break;
            int width = opWidth(op);
            if (width < 2) width = 2;
            if (((op >= 0x6e && op <= 0x72) || op == 0x74 || op == 0x75 || op == 0x76 || op == 0x78) && i + 4 <= end) {
                int idx = (dex[i + 2] & 0xff) | ((dex[i + 3] & 0xff) << 8);
                if (!invoked.contains(idx)) invoked.add(idx);
            }
            i += width;
        }
    }

    private static void skipEncodedFields(byte[] dex, int[] cursor, int count) {
        for (int i = 0; i < count && cursor[0] < dex.length; i++) {
            uleb(dex, cursor);
            uleb(dex, cursor);
        }
    }


    /** Code units. 10x instructions carry a packed-switch/array payload after them. */
    private static int opWidth(int op) {
        if (op == 0x00) return 2;
        if ((op >= 0x01 && op <= 0x0d) || op == 0x0f || op == 0x12 || op == 0x1d || op == 0x1e
                || op == 0x27 || (op >= 0x7b && op <= 0x8f) || (op >= 0xd0 && op <= 0xe2)) return 2;
        if (op == 0x0e || op == 0x10 || op == 0x11 || op == 0x13 || op == 0x15 || op == 0x16
                || op == 0x19 || op == 0x1a || op == 0x1c || op == 0x1f || op == 0x20 || op == 0x21
                || op == 0x22 || (op >= 0x2d && op <= 0x31) || (op >= 0x44 && op <= 0x6d)
                || (op >= 0x90 && op <= 0xaf) || (op >= 0xd8 && op <= 0xeb)) return 4;
        return 6;
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
