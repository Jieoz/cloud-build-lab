package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class DexTypesTest {

    @Test
    public void countsTypeDescriptorsNotStringConstants() {
        byte[] dex = miniDex(
                new String[]{"LTimelineActivity;", "Timeline is a string", "LOther;"},
                new int[]{0, 2});
        assertEquals(1, DexTypes.countDescriptorContaining(dex, "Timeline"));
        assertEquals("LTimelineActivity;", DexTypes.descriptorsContaining(dex, "Timeline", 8).get(0));
    }

    @Test
    public void zeroWhenHeaderTooSmall() {
        assertEquals(0, DexTypes.countDescriptorContaining(new byte[10], "Timeline"));
    }

    @Test
    public void skipsFrameworkFrameTimeline() {
        byte[] dex = miniDex(
                new String[]{
                        "Landroid/view/Choreographer$FrameTimeline;",
                        "Lcom/google/android/gms/semanticlocation/TimelineMemory;",
                        "Lcom/google/android/apps/maps/TimelineActivity;"},
                new int[]{0, 1, 2});
        java.util.List<String> names = DexTypes.descriptorsContaining(dex, "Timeline", 8);
        assertEquals(2, names.size());
        assertEquals("Lcom/google/android/gms/semanticlocation/TimelineMemory;", names.get(0));
    }

    /** header(0x70) + string ids + type ids + the string data. */
    private static byte[] miniDex(String[] strings, int[] typeStringIndex) {
        int header = 0x70;
        int stringIds = header;
        int typeIds = stringIds + strings.length * 4;
        int data = typeIds + typeStringIndex.length * 4;
        byte[][] encoded = new byte[strings.length][];
        int[] offs = new int[strings.length];
        int cursor = data;
        for (int i = 0; i < strings.length; i++) {
            byte[] utf = strings[i].getBytes(StandardCharsets.UTF_8);
            encoded[i] = new byte[1 + utf.length + 1];
            encoded[i][0] = (byte) utf.length;
            System.arraycopy(utf, 0, encoded[i], 1, utf.length);
            offs[i] = cursor;
            cursor += encoded[i].length;
        }
        ByteBuffer buf = ByteBuffer.allocate(cursor).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(0x38);
        buf.putInt(strings.length);
        buf.putInt(stringIds);
        buf.putInt(typeStringIndex.length);
        buf.putInt(typeIds);
        buf.position(stringIds);
        for (int off : offs) buf.putInt(off);
        for (int idx : typeStringIndex) buf.putInt(idx);
        buf.position(data);
        for (byte[] one : encoded) buf.put(one);
        return buf.array();
    }
}
