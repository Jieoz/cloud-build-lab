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

    /**
     * The single method that invokes {@code owner.method}, plus the invokes inside that caller.
     * Stops at the first caller. Does not list every caller in the dex.
     */
    static java.util.List<String> callerReads(byte[] dex, String owner, String method, int limit) {
        java.util.List<String> found = new java.util.ArrayList<>();
        if (dex == null || dex.length < 0x74 || owner == null || method == null || limit <= 0) return found;
        int stringOff = u32(dex, 0x3c);
        int typeOff = u32(dex, 0x44);
        int methodIds = u32(dex, 0x58);
        int methodOff = u32(dex, 0x5c);
        int classDefs = u32(dex, 0x60);
        int classOff = u32(dex, 0x64);
        if (methodIds <= 0 || classDefs <= 0) return found;
        int target = -1;
        for (int i = 0; i < methodIds; i++) {
            int pos = methodOff + i * 8;
            if (pos < 0 || pos + 8 > dex.length) break;
            String who = typeName(dex, stringOff, typeOff, u16(dex, pos));
            String name = string(dex, stringOff, u32(dex, pos + 4));
            if (method.equals(name) && (owner.equals(who) || ("L" + owner + ";").equals(who))) {
                target = i;
                break;
            }
        }
        if (target < 0) return found;
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
            if (callerBody(dex, cursor, directMethods, methodOff, stringOff, typeOff, target, found, limit)) return found;
            if (callerBody(dex, cursor, virtualMethods, methodOff, stringOff, typeOff, target, found, limit)) return found;
        }
        return found;
    }

    private static boolean callerBody(byte[] dex, int[] cursor, int count, int methodOff, int stringOff,
            int typeOff, int target, java.util.List<String> found, int limit) {
        int methodIdx = 0;
        for (int i = 0; i < count; i++) {
            if (cursor[0] >= dex.length) return false;
            methodIdx += uleb(dex, cursor);
            uleb(dex, cursor);
            int codeOff = uleb(dex, cursor);
            if (codeOff <= 0 || codeOff >= dex.length) continue;
            int insns = u32(dex, codeOff + 12);
            int start = codeOff + 16;
            int end = Math.min(dex.length, start + Math.max(0, insns) * 2);
            boolean hit = false;
            java.util.List<String> local = new java.util.ArrayList<>();
            for (int pc = start; pc + 4 <= end; ) {
                int op = dex[pc] & 0xff;
                int width = insnWidth(dex, pc, end);
                if (width < 2) break;
                if ((op >= 0x6e && op <= 0x72) || op == 0x74 || op == 0x75 || op == 0x76 || op == 0x78) {
                    int idx = (dex[pc + 2] & 0xff) | ((dex[pc + 3] & 0xff) << 8);
                    int pos = methodOff + idx * 8;
                    if (idx == target) hit = true;
                    if (pos >= 0 && pos + 8 <= dex.length && local.size() < limit) {
                        String line = typeName(dex, stringOff, typeOff, u16(dex, pos)) + "->"
                                + string(dex, stringOff, u32(dex, pos + 4));
                        if (!local.contains(line)) local.add(line);
                    }
                }
                pc += width;
            }
            if (!hit) continue;
            int pos = methodOff + methodIdx * 8;
            if (pos >= 0 && pos + 8 <= dex.length) {
                found.add(typeName(dex, stringOff, typeOff, u16(dex, pos)) + "->"
                        + string(dex, stringOff, u32(dex, pos + 4)));
            }
            found.addAll(local);
            return true;
        }
        return false;
    }

    /**
     * Methods whose body invokes {@code ownerNeedle}. Stops after {@code limit} hits.
     * Does not decode every method name in the dex.
     */
    static java.util.List<String> callersOf(byte[] dex, String ownerNeedle, int limit) {
        java.util.List<String> found = new java.util.ArrayList<>();
        if (dex == null || dex.length < 0x74 || ownerNeedle == null || limit <= 0) return found;
        int stringOff = u32(dex, 0x3c);
        int typeOff = u32(dex, 0x44);
        int methodIds = u32(dex, 0x58);
        int methodOff = u32(dex, 0x5c);
        int classDefs = u32(dex, 0x60);
        int classOff = u32(dex, 0x64);
        if (methodIds <= 0 || classDefs <= 0) return found;
        boolean[] want = new boolean[methodIds];
        boolean any = false;
        for (int i = 0; i < methodIds; i++) {
            int pos = methodOff + i * 8;
            if (pos < 0 || pos + 8 > dex.length) break;
            String owner = typeName(dex, stringOff, typeOff, u16(dex, pos));
            if (ownerNeedle.equals(owner) || ("L" + ownerNeedle + ";").equals(owner) || owner.contains(ownerNeedle)) {
                want[i] = true;
                any = true;
            }
        }
        if (!any) return found;
        for (int c = 0; c < classDefs && found.size() < limit; c++) {
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
            markCallers(dex, cursor, directMethods, methodOff, stringOff, typeOff, want, found, limit);
            markCallers(dex, cursor, virtualMethods, methodOff, stringOff, typeOff, want, found, limit);
        }
        return found;
    }

    private static void markCallers(byte[] dex, int[] cursor, int count, int methodOff, int stringOff,
            int typeOff, boolean[] want, java.util.List<String> found, int limit) {
        int methodIdx = 0;
        for (int i = 0; i < count && found.size() < limit; i++) {
            if (cursor[0] >= dex.length) return;
            methodIdx += uleb(dex, cursor);
            uleb(dex, cursor);
            int codeOff = uleb(dex, cursor);
            if (codeOff <= 0 || codeOff >= dex.length || methodIdx < 0 || methodIdx >= want.length) continue;
            int insns = u32(dex, codeOff + 12);
            int start = codeOff + 16;
            int end = Math.min(dex.length, start + Math.max(0, insns) * 2);
            boolean hit = false;
            for (int pc = start; pc + 4 <= end; ) {
                int op = dex[pc] & 0xff;
                int width = insnWidth(dex, pc, end);
                if (width < 2) break;
                if ((op >= 0x6e && op <= 0x72) || op == 0x74 || op == 0x75 || op == 0x76 || op == 0x78) {
                    int idx = (dex[pc + 2] & 0xff) | ((dex[pc + 3] & 0xff) << 8);
                    if (idx >= 0 && idx < want.length && want[idx]) {
                        hit = true;
                        break;
                    }
                }
                pc += width;
            }
            if (!hit) continue;
            int pos = methodOff + methodIdx * 8;
            if (pos < 0 || pos + 8 > dex.length) continue;
            String line = typeName(dex, stringOff, typeOff, u16(dex, pos)) + "->"
                    + string(dex, stringOff, u32(dex, pos + 4));
            if (!found.contains(line)) found.add(line);
        }
    }

    /**
     * Invoke targets of one named method. Walks only that method's code, then stops.
     * A full-dex invoke walk on the startup path hangs Maps.
     */
    static java.util.List<String> invokesOf(byte[] dex, String ownerNeedle, String methodName, int limit) {
        return invokesOf(dex, ownerNeedle, methodName, null, limit);
    }

    /**
     * Invoke targets of one named method. When {@code mustCall} is set, only the overload whose
     * body references that type is decoded. Walks that one method, then stops.
     */
    static java.util.List<String> invokesOf(byte[] dex, String ownerNeedle, String methodName,
            String mustCall, int limit) {
        java.util.List<String> found = new java.util.ArrayList<>();
        if (dex == null || dex.length < 0x74 || ownerNeedle == null || methodName == null || limit <= 0) {
            return found;
        }
        int stringOff = u32(dex, 0x3c);
        int typeOff = u32(dex, 0x44);
        int methodIds = u32(dex, 0x58);
        int methodOff = u32(dex, 0x5c);
        int classDefs = u32(dex, 0x60);
        int classOff = u32(dex, 0x64);
        if (methodIds <= 0 || classDefs <= 0) return found;
        for (int c = 0; c < classDefs && found.isEmpty(); c++) {
            int classPos = classOff + c * 32;
            if (classPos < 0 || classPos + 32 > dex.length) break;
            String owner = typeName(dex, stringOff, typeOff, u32(dex, classPos));
            if (!ownerNeedle.equals(owner) && !("L" + ownerNeedle + ";").equals(owner)) continue;
            int classDataOff = u32(dex, classPos + 24);
            if (classDataOff <= 0 || classDataOff >= dex.length) continue;
            int[] cursor = new int[]{classDataOff};
            int staticFields = uleb(dex, cursor);
            int instanceFields = uleb(dex, cursor);
            int directMethods = uleb(dex, cursor);
            int virtualMethods = uleb(dex, cursor);
            skipEncodedFields(dex, cursor, staticFields + instanceFields);
            collectNamed(dex, cursor, directMethods, methodOff, stringOff, typeOff, methodName, mustCall, found, limit);
            collectNamed(dex, cursor, virtualMethods, methodOff, stringOff, typeOff, methodName, mustCall, found, limit);
        }
        return found;
    }

    private static void collectNamed(byte[] dex, int[] cursor, int count, int methodOff, int stringOff,
            int typeOff, String methodName, String mustCall, java.util.List<String> found, int limit) {
        int methodIdx = 0;
        for (int i = 0; i < count; i++) {
            if (cursor[0] >= dex.length) return;
            methodIdx += uleb(dex, cursor);
            uleb(dex, cursor);
            int codeOff = uleb(dex, cursor);
            if (!found.isEmpty()) continue;
            int pos = methodOff + methodIdx * 8;
            if (pos < 0 || pos + 8 > dex.length || codeOff <= 0 || codeOff >= dex.length) continue;
            if (!methodName.equals(string(dex, stringOff, u32(dex, pos + 4)))) continue;
            int insns = u32(dex, codeOff + 12);
            int start = codeOff + 16;
            int end = Math.min(dex.length, start + Math.max(0, insns) * 2);
            java.util.List<String> local = new java.util.ArrayList<>();
            boolean matched = mustCall == null || mustCall.isEmpty();
            for (int pc = start; pc + 2 <= end && local.size() < limit; ) {
                int op = dex[pc] & 0xff;
                int width = insnWidth(dex, pc, end);
                if (width < 2) break;
                if ((op >= 0x6e && op <= 0x72) || op == 0x74 || op == 0x75 || op == 0x76 || op == 0x78) {
                    int idx = (dex[pc + 2] & 0xff) | ((dex[pc + 3] & 0xff) << 8);
                    int target = methodOff + idx * 8;
                    if (target >= 0 && target + 8 <= dex.length) {
                        String who = typeName(dex, stringOff, typeOff, u16(dex, target));
                        String name = string(dex, stringOff, u32(dex, target + 4));
                        if (mustCall != null && who.contains(mustCall)) matched = true;
                        String line = who + "->" + name;
                        if (!local.contains(line)) local.add(line);
                    }
                }
                pc += width;
            }
            if (matched) found.addAll(local);
        }
    }

    /** Code units consumed by the instruction at pc. 0 means the cursor cannot advance. */
    private static int insnWidth(byte[] dex, int pc, int end) {
        if (pc >= end) return 0;
        int op = dex[pc] & 0xff;
        if (op == 0x00) return 2;
        if (op == 0x01 || op == 0x04 || op == 0x05 || op == 0x06 || op == 0x07) return 2;
        if (op == 0x02) return 4;
        if (op == 0x03) return 6;
        if ((op >= 0x12 && op <= 0x19) || op == 0x1a || op == 0x1c || op == 0x1d || op == 0x1e || op == 0x1f) return 2;
        if (op == 0x1b) return 4;
        if (op == 0x26) return 4;
        if ((op >= 0x2d && op <= 0x31) || op == 0x32) return 4;
        if (op >= 0x6e && op <= 0x72) return 6;
        if (op == 0x74 || op == 0x75 || op == 0x76 || op == 0x78) return 6;
        return 2;
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

    // ---- Width-correct decode (log60) ----------------------------------------------------------
    //
    // The 2-byte-stepping helpers above can alias operand bytes as invoke opcodes: on the
    // 26.39 package exactly one phantom "TimelineWrapper.<init> call" was produced that way.
    // Everything below walks instructions by their true widths, so a reported call is a real
    // call, and an undecodable opcode reports nothing rather than guessing.

    /** A method found by a width-correct scan, with its callees in first-seen order. */
    static final class Creator {
        final String owner;
        final String name;
        final String ret;
        /** Parameter slots of the proto (wide types count as one). -1 when unknown. */
        final int arity;
        /** Parameter type descriptors, empty when unknown. */
        final java.util.List<String> params;
        final java.util.List<String> calls;
        final int targetIndex;

        Creator(String owner, String name, String ret, java.util.List<String> calls, int targetIndex) {
            this(owner, name, ret, -1, java.util.Collections.emptyList(), calls, targetIndex);
        }

        Creator(String owner, String name, String ret, int arity, java.util.List<String> params,
                java.util.List<String> calls, int targetIndex) {
            this.owner = owner;
            this.name = name;
            this.ret = ret;
            this.arity = arity;
            this.params = params;
            this.calls = calls;
            this.targetIndex = targetIndex;
        }

        /** {@code owner->name(param descriptors)}, so a short obfuscated name does not skip siblings. */
        String signature() {
            StringBuilder out = new StringBuilder();
            out.append(owner).append("->").append(name).append('(');
            for (int i = 0; i < params.size(); i++) out.append(params.get(i)).append(',');
            return out.append(')').toString();
        }
    }

    /**
     * Width-correct hunt for the first method that truly invokes
     * {@code com.google.android.apps.gmm.mapsactivity.instant.TimelineWrapper.<init>}.
     * Null when this dex has no decodable caller.
     */
    static Creator findCreator(byte[] dex) {
        return findInvoker(dex, "Lcom/google/android/apps/gmm/mapsactivity/instant/TimelineWrapper;",
                "<init>", null);
    }

    /**
     * Width-correct hunt for the first method that truly invokes any method id whose owner
     * contains {@code ownerNeedle} and whose name is {@code nameNeedle}. {@code skip} excludes
     * one signature (the found method itself when walking one level up).
     */
    static Creator findInvoker(byte[] dex, String ownerNeedle, String nameNeedle, String skip) {
        if (dex == null || dex.length < 0x70 || ownerNeedle == null || nameNeedle == null) return null;
        int stringOff = u32(dex, 0x3c);
        int typeOff = u32(dex, 0x44);
        int protoOff = u32(dex, 0x4c);
        int methodOff = u32(dex, 0x5c);
        int methodIds = u32(dex, 0x58);
        int classDefs = u32(dex, 0x60);
        int classOff = u32(dex, 0x64);
        if (methodIds <= 0 || classDefs <= 0) return null;
        java.util.List<Integer> targets = new java.util.ArrayList<>();
        for (int i = 0; i < methodIds; i++) {
            int pos = methodOff + i * 8;
            if (pos < 0 || pos + 8 > dex.length) break;
            if (!nameNeedle.equals(string(dex, stringOff, u32(dex, pos + 4)))) continue;
            if (typeName(dex, stringOff, typeOff, u16(dex, pos)).contains(ownerNeedle)) targets.add(i);
        }
        if (targets.isEmpty()) return null;
        for (int c = 0; c < classDefs; c++) {
            int classPos = classOff + c * 32;
            if (classPos < 0 || classPos + 32 > dex.length) break;
            String owner = typeName(dex, stringOff, typeOff, u16(dex, classPos));
            int classDataOff = u32(dex, classPos + 24);
            if (classDataOff <= 0 || classDataOff >= dex.length) continue;
            int[] cursor = new int[]{classDataOff};
            int staticFields = uleb(dex, cursor);
            int instanceFields = uleb(dex, cursor);
            int directMethods = uleb(dex, cursor);
            int virtualMethods = uleb(dex, cursor);
            skipEncodedFields(dex, cursor, staticFields + instanceFields);
            Creator found = scanMethods(dex, cursor, directMethods, methodOff, methodIds,
                    stringOff, protoOff, owner, skip, targets);
            if (found != null) return found;
            found = scanMethods(dex, cursor, virtualMethods, methodOff, methodIds,
                    stringOff, protoOff, owner, skip, targets);
            if (found != null) return found;
        }
        return null;
    }

    /** Every method in this dex that invokes a target id, as {@code owner->name/arity}. Capped. */
    static java.util.List<String> allInvokers(byte[] dex, String ownerNeedle, String nameNeedle, int cap) {
        java.util.List<String> found = new java.util.ArrayList<>();
        if (dex == null || dex.length < 0x70 || ownerNeedle == null || nameNeedle == null || cap <= 0) return found;
        int stringOff = u32(dex, 0x3c);
        int typeOff = u32(dex, 0x44);
        int protoOff = u32(dex, 0x4c);
        int methodOff = u32(dex, 0x5c);
        int methodIds = u32(dex, 0x58);
        int classDefs = u32(dex, 0x60);
        int classOff = u32(dex, 0x64);
        if (methodIds <= 0 || classDefs <= 0) return found;
        java.util.List<Integer> targets = new java.util.ArrayList<>();
        for (int i = 0; i < methodIds; i++) {
            int pos = methodOff + i * 8;
            if (pos < 0 || pos + 8 > dex.length) break;
            if (!nameNeedle.equals(string(dex, stringOff, u32(dex, pos + 4)))) continue;
            if (typeName(dex, stringOff, typeOff, u16(dex, pos)).contains(ownerNeedle)) targets.add(i);
        }
        if (targets.isEmpty()) return found;
        for (int c = 0; c < classDefs && found.size() < cap; c++) {
            int classPos = classOff + c * 32;
            if (classPos < 0 || classPos + 32 > dex.length) break;
            String owner = typeName(dex, stringOff, typeOff, u16(dex, classPos));
            int classDataOff = u32(dex, classPos + 24);
            if (classDataOff <= 0 || classDataOff >= dex.length) continue;
            int[] cursor = new int[]{classDataOff};
            int staticFields = uleb(dex, cursor);
            int instanceFields = uleb(dex, cursor);
            int directMethods = uleb(dex, cursor);
            int virtualMethods = uleb(dex, cursor);
            skipEncodedFields(dex, cursor, staticFields + instanceFields);
            collectInvokers(dex, cursor, directMethods, methodOff, methodIds, stringOff, protoOff,
                    owner, targets, found, cap);
            collectInvokers(dex, cursor, virtualMethods, methodOff, methodIds, stringOff, protoOff,
                    owner, targets, found, cap);
        }
        return found;
    }

    private static void collectInvokers(byte[] dex, int[] cursor, int count, int methodOff, int methodIds,
            int stringOff, int protoOff, String owner, java.util.List<Integer> targets,
            java.util.List<String> found, int cap) {
        int methodIdx = 0;
        for (int i = 0; i < count && found.size() < cap; i++) {
            if (cursor[0] >= dex.length) return;
            methodIdx += uleb(dex, cursor);
            uleb(dex, cursor);
            int codeOff = uleb(dex, cursor);
            if (codeOff <= 0 || codeOff >= dex.length || methodIdx >= methodIds) continue;
            int idPos = methodOff + methodIdx * 8;
            java.util.List<String> calls = new java.util.ArrayList<>();
            if (decodeCallees(dex, codeOff, protoOff, targets, calls, 8) < 0) continue;
            int protoIdx = u16(dex, idPos + 2);
            found.add(owner + "->" + string(dex, stringOff, u32(dex, idPos + 4))
                    + "/" + protoArity(dex, protoOff, protoIdx));
        }
    }

    /**
     * One encoded_method section. Direct and virtual each restart the method index at 0;
     * carrying the index across both sections reads a field name as a method.
     */
    private static Creator scanMethods(byte[] dex, int[] cursor, int count, int methodOff, int methodIds,
            int stringOff, int protoOff, String owner, String skip, java.util.List<Integer> targets) {
        int methodIdx = 0;
        for (int i = 0; i < count; i++) {
            if (cursor[0] >= dex.length) return null;
            methodIdx += uleb(dex, cursor);
            uleb(dex, cursor);
            int codeOff = uleb(dex, cursor);
            if (codeOff <= 0 || codeOff >= dex.length || methodIdx >= methodIds) continue;
            int idPos = methodOff + methodIdx * 8;
            String name = string(dex, stringOff, u32(dex, idPos + 4));
            if ((owner + "->" + name + protoKey(dex, protoOff, u16(dex, idPos + 2))).equals(skip)) continue;
            java.util.List<String> calls = new java.util.ArrayList<>();
            int hit = decodeCallees(dex, codeOff, protoOff, targets, calls, 80);
            if (hit >= 0) {
                int protoIdx = u16(dex, idPos + 2);
                String ret = protoReturn(dex, protoOff, protoIdx);
                return new Creator(owner, name, ret, protoArity(dex, protoOff, protoIdx),
                        protoParams(dex, protoOff, protoIdx), calls, hit);
            }
        }
        return null;
    }

    /**
     * Walks one method body by real instruction widths. Returns the position in {@code calls}
     * of the first invoke of a target id (-1 when none or undecodable); {@code calls} holds
     * the invokes seen, each as {@code owner->name ret}.
     */
    private static int decodeCallees(byte[] dex, int codeOff, int protoOff,
            java.util.List<Integer> targets, java.util.List<String> calls, int cap) {
        int stringOff = u32(dex, 0x3c);
        int typeOff = u32(dex, 0x44);
        int methodOff = u32(dex, 0x5c);
        int methodIds = u32(dex, 0x58);
        int insns = u32(dex, codeOff + 12);
        int start = codeOff + 16;
        int end = Math.min(dex.length, start + Math.max(0, insns) * 2);
        int pc = start;
        while (pc + 2 <= end) {
            int op = dex[pc] & 0xff;
            boolean methodInvoke = (op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78);
            boolean polyInvoke = op == 0xfa || op == 0xfb;
            if (methodInvoke || polyInvoke) {
                int bytes = polyInvoke ? 8 : 6;
                if (pc + bytes > end) return -1;
                int idx = u16(dex, pc + 2);
                if (idx >= 0 && idx < methodIds) {
                    int pos = methodOff + idx * 8;
                    String line = typeName(dex, stringOff, typeOff, u16(dex, pos)) + "->"
                            + string(dex, stringOff, u32(dex, pos + 4))
                            + " " + protoReturn(dex, protoOff, u16(dex, pos + 2));
                    int index = calls.indexOf(line);
                    if (index < 0 && calls.size() < cap) {
                        calls.add(line);
                        index = calls.size() - 1;
                    }
                    if (targets.contains(idx)) return index;
                }
                pc += bytes;
                continue;
            }
            int units;
            if (op == 0x00) {
                units = nopUnits(dex, pc, end);
            } else if (op == 0xfc || op == 0xfd) {
                units = 3; // invoke-custom: callsite id, not a method id; skip without decoding
            } else {
                units = insnUnitsOf(op);
            }
            if (units <= 0) return -1;
            pc += units * 2;
        }
        return -1;
    }

    /** Width of the nop / switch-payload at pc in code units. 0 = cannot decode. */
    private static int nopUnits(byte[] dex, int pc, int end) {
        if (pc + 2 > end) return 0;
        int ident = u16(dex, pc);
        if (ident != 0x0100 && ident != 0x0200 && ident != 0x0300) return 1;
        if (pc + 8 > end) return 0;
        int size = u32(dex, pc + 4);
        long bytes = 8L + size * (ident == 0x0300 ? 2 : (ident == 0x0100 ? 4 : 8));
        long units = bytes / 2;
        return pc + units * 2 <= end ? (int) units : 0;
    }

    /** Width of one non-nop opcode in code units. 0 = unused or unknown: never guess. */
    private static int insnUnitsOf(int op) {
        switch (op) {
            case 0x01: case 0x04: case 0x07:
            case 0x0a: case 0x0b: case 0x0c: case 0x0d:
            case 0x0e: case 0x0f: case 0x10: case 0x11:
            case 0x12: case 0x1d: case 0x1e: case 0x21: case 0x27: case 0x28:
            case 0x7b: case 0x7c: case 0x7d: case 0x7e: case 0x7f: case 0x80:
            case 0x81: case 0x82: case 0x83: case 0x84: case 0x85: case 0x86:
            case 0x87: case 0x88: case 0x89: case 0x8a: case 0x8b: case 0x8c:
            case 0x8d: case 0x8e: case 0x8f:
            case 0xb0: case 0xb1: case 0xb2: case 0xb3: case 0xb4: case 0xb5:
            case 0xb6: case 0xb7: case 0xb8: case 0xb9: case 0xba: case 0xbb:
            case 0xbc: case 0xbd: case 0xbe: case 0xbf: case 0xc0: case 0xc1:
            case 0xc2: case 0xc3: case 0xc4: case 0xc5: case 0xc6: case 0xc7:
            case 0xc8: case 0xc9: case 0xca: case 0xcb: case 0xcc: case 0xcd:
            case 0xce: case 0xcf:
                return 1;
            case 0x02: case 0x05: case 0x08:
            case 0x13: case 0x15: case 0x19: case 0x1a: case 0x1c:
            case 0x1f: case 0x20: case 0x22: case 0x23:
            case 0x29: case 0x2b: case 0x2c:
                return 2;
            case 0x03: case 0x06: case 0x09:
            case 0x14: case 0x16: case 0x17: case 0x1b:
            case 0x24: case 0x25: case 0x26: case 0x2a:
            case 0xfa: case 0xfb:
            case 0xfc: case 0xfd:
                return 3;
            case 0x18:
                return 5;
            default:
                if ((op >= 0x2d && op <= 0x3d) || (op >= 0x44 && op <= 0x6d)
                        || (op >= 0x90 && op <= 0xaf) || (op >= 0xd0 && op <= 0xe2)) return 2;
                return 0;
        }
    }

    /** Return-type descriptor of a method id ("Z" boolean, "V", "L...;", "[..."). "?" unknown. */
    static String protoReturn(byte[] dex, int protoOff, int protoIdx) {
        if (dex == null || dex.length < 0x50 || protoOff <= 0 || protoIdx < 0) return "?";
        int protoIds = u32(dex, 0x48);
        int pos = protoOff + protoIdx * 12;
        if (protoIdx >= protoIds || pos + 12 > dex.length) return "?";
        return typeName(dex, u32(dex, 0x3c), u32(dex, 0x44), u32(dex, pos + 4));
    }

    /** Parameter slot count of a proto. -1 when the proto or its type list cannot be read. */
    static int protoArity(byte[] dex, int protoOff, int protoIdx) {
        if (dex == null || dex.length < 0x50 || protoOff <= 0 || protoIdx < 0) return -1;
        int protoIds = u32(dex, 0x48);
        int pos = protoOff + protoIdx * 12;
        if (protoIdx >= protoIds || pos + 12 > dex.length) return -1;
        int paramsOff = u32(dex, pos + 8);
        if (paramsOff == 0) return 0;
        if (paramsOff < 0 || paramsOff + 4 > dex.length) return -1;
        return u32(dex, paramsOff);
    }

    /** Parameter type descriptors of a proto, in declaration order. Empty when unreadable. */
    static java.util.List<String> protoParams(byte[] dex, int protoOff, int protoIdx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (dex == null || dex.length < 0x50 || protoOff <= 0 || protoIdx < 0) return out;
        int protoIds = u32(dex, 0x48);
        int pos = protoOff + protoIdx * 12;
        if (protoIdx >= protoIds || pos + 12 > dex.length) return out;
        int paramsOff = u32(dex, pos + 8);
        if (paramsOff == 0) return out;
        if (paramsOff < 0 || paramsOff + 4 > dex.length) return out;
        int count = u32(dex, paramsOff);
        int stringOff = u32(dex, 0x3c);
        int typeOff = u32(dex, 0x44);
        for (int i = 0; i < count; i++) {
            int at = paramsOff + 4 + i * 2;
            if (at + 2 > dex.length) break;
            out.add(typeName(dex, stringOff, typeOff, u16(dex, at)));
        }
        return out;
    }

    private static String protoKey(byte[] dex, int protoOff, int protoIdx) {
        StringBuilder out = new StringBuilder("(");
        for (String param : protoParams(dex, protoOff, protoIdx)) out.append(param).append(',');
        return out.append(')').toString();
    }

    /**
     * The class that decides whether the Timeline menu entry is shown. Found by shape, not by
     * name: one no-arg method reads a {@code cdup} field and a {@code Boolean} field, then writes
     * a {@code boolean} field. On the 26.38 package that is {@code akoj.b}, and the written field
     * is what the menu reads. Returns the class descriptor, or null.
     */
    static String findEntryGate(byte[] dex) {
        if (dex == null || dex.length < 0x70) return null;
        int stringOff = u32(dex, 0x3c);
        int typeOff = u32(dex, 0x44);
        int protoOff = u32(dex, 0x4c);
        int fieldOff = u32(dex, 0x54);
        int methodOff = u32(dex, 0x5c);
        int classDefs = u32(dex, 0x60);
        int classOff = u32(dex, 0x64);
        if (classDefs <= 0) return null;
        for (int c = 0; c < classDefs; c++) {
            int cp = classOff + c * 32;
            if (cp < 0 || cp + 32 > dex.length) break;
            int data = u32(dex, cp + 24);
            if (data <= 0 || data >= dex.length) continue;
            int[] k = new int[]{data};
            int staticFields = uleb(dex, k);
            int instanceFields = uleb(dex, k);
            int direct = uleb(dex, k);
            int virtual = uleb(dex, k);
            skipEncodedFields(dex, k, staticFields + instanceFields);
            if (gateMethod(dex, k, direct, methodOff, stringOff, protoOff, fieldOff, typeOff)
                    || gateMethod(dex, k, virtual, methodOff, stringOff, protoOff, fieldOff, typeOff)) {
                return typeName(dex, stringOff, typeOff, u16(dex, cp));
            }
        }
        return null;
    }

    /** True when one no-arg method in this list reads cdup + Boolean and writes a boolean. */
    private static boolean gateMethod(byte[] dex, int[] k, int count, int methodOff, int stringOff,
            int protoOff, int fieldOff, int typeOff) {
        int idx = 0;
        for (int i = 0; i < count; i++) {
            idx += uleb(dex, k);
            uleb(dex, k);
            int code = uleb(dex, k);
            if (code <= 0 || code + 16 > dex.length) continue;
            int mp = methodOff + idx * 8;
            if (mp < 0 || mp + 8 > dex.length) continue;
            int paramOff = u32(dex, protoOff + u16(dex, mp + 2) * 12 + 8);
            int nparams = paramOff == 0 ? 0 : (paramOff > 0 && paramOff + 4 <= dex.length ? u32(dex, paramOff) : -1);
            if (nparams != 0) continue;
            int insns = u32(dex, code + 12);
            int start = code + 16;
            int end = Math.min(dex.length, start + insns * 2);
            boolean cdup = false, boxed = false, put = false;
            for (int pc = start; pc + 4 <= end; pc += 2) {
                int op = dex[pc] & 0xff;
                if (op == 0x54 || op == 0x55) {
                    String t = fieldType(dex, stringOff, typeOff, fieldOff, u16(dex, pc + 2));
                    if ("Lcdup;".equals(t)) cdup = true;
                    if ("Ljava/lang/Boolean;".equals(t)) boxed = true;
                } else if (op == 0x5b || op == 0x5c) {
                    if ("Z".equals(fieldType(dex, stringOff, typeOff, fieldOff, u16(dex, pc + 2)))) put = true;
                }
            }
            if (cdup && boxed && put) return true;
        }
        return false;
    }

    private static String fieldType(byte[] dex, int stringOff, int typeOff, int fieldOff, int fieldIdx) {
        int fp = fieldOff + fieldIdx * 8;
        if (fp < 0 || fp + 8 > dex.length) return "";
        return typeName(dex, stringOff, typeOff, u16(dex, fp + 2));
    }
}
