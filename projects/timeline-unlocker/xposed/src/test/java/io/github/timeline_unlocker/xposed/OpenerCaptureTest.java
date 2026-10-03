package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpenerCaptureTest {

    private static StackTraceElement frame(String cls, String method) {
        return new StackTraceElement(cls, method, "SourceFile.java", 1);
    }

    @Test
    public void picksLowestObfuscatedFramesAboveWrapper() {
        StackTraceElement[] stack = {
                frame("com.google.android.apps.gmm.mapsactivity.instant.TimelineWrapper", "<init>"),
                frame("dcqz", "a"), // generated factory plumbing
                frame("akjz", "l"), // the veneer method
                frame("com.google.android.apps.maps.MapsActivity", "onCreate"),
                frame("android.app.Activity", "performCreate"),
        };
        OpenerCapture.Frame[] frames = OpenerCapture.pickVeneerFrames(stack);
        assertEquals(2, frames.length);
        assertEquals("dcqz.a", frames[0].key());
        assertEquals("akjz.l", frames[1].key());
        assertEquals("dcqz", OpenerCapture.pickVeneerFrame(stack).className);
    }

    @Test
    public void skipsAccessMethodsAndNamedClassesAndDuplicates() {
        StackTraceElement[] stack = {
                frame("com.google.android.apps.gmm.mapsactivity.instant.TimelineWrapper", "<init>"),
                frame("akjz", "access$100"),
                frame("akjz", "l"),
                frame("akjz", "l"), // duplicate
                frame("com.google.android.apps.maps.MapsActivityVeneerImpl", "startTimeline"),
        };
        OpenerCapture.Frame[] frames = OpenerCapture.pickVeneerFrames(stack);
        assertEquals(1, frames.length);
        assertEquals("akjz.l", frames[0].key());
    }

    @Test
    public void nullAndEmptyStacksAreSafe() {
        assertEquals(0, OpenerCapture.pickVeneerFrames(null).length);
        assertEquals(0, OpenerCapture.pickVeneerFrames(new StackTraceElement[0]).length);
        assertEquals(0, OpenerCapture.pickVeneerFrames(new StackTraceElement[]{
                frame("com.google.android.apps.maps.MapsActivity", "onCreate")}).length);
        assertNull(OpenerCapture.pickVeneerFrame(new StackTraceElement[0]));
    }

    @Test
    public void obfuscatedNameShape() {
        assertTrue(OpenerCapture.isObfuscatedName("akjz"));
        assertTrue(OpenerCapture.isObfuscatedName("a1"));
        assertTrue(OpenerCapture.isObfuscatedName("dcqz"));
        assertFalse(OpenerCapture.isObfuscatedName("Akjz"));
        assertFalse(OpenerCapture.isObfuscatedName("a"));
        assertFalse(OpenerCapture.isObfuscatedName("toolong1"));
        // "maps" is shape-identical to a real obfuscated name; the ladder's wrapper-timing
        // check is what rejects wrong candidates, so the shape test must accept it.
        assertTrue(OpenerCapture.isObfuscatedName("maps"));
        assertFalse(OpenerCapture.isObfuscatedName(null));
        assertFalse(OpenerCapture.isObfuscatedName(""));
    }

    @Test
    public void candidateListRoundTrip() {
        assertEquals("", OpenerCapture.encodeCandidates(new OpenerCapture.Frame[0]));
        String csv = OpenerCapture.encodeCandidates(OpenerCapture.pickVeneerFrames(
                new StackTraceElement[]{
                        frame("com.google.android.apps.gmm.mapsactivity.instant.TimelineWrapper", "<init>"),
                        frame("dcqz", "a"),
                        frame("akjz", "l"),
                }));
        assertEquals("dcqz.a,akjz.l", csv);
        String[] back = OpenerCapture.decodeCandidates(csv);
        assertEquals(2, back.length);
        assertEquals("dcqz.a", back[0]);
        assertEquals("akjz.l", back[1]);
        assertEquals(0, OpenerCapture.decodeCandidates(null).length);
        assertEquals(0, OpenerCapture.decodeCandidates("nobuiltinname,,x").length);
    }

    @Test
    public void dumpsFieldsAndCountsThem() {
        Object subject = new java.util.HashMap<>(); // no interesting instance fields, no throw
        String dump = OpenerCapture.dumpFields(subject);
        assertEquals(0, OpenerCapture.dumpSize(dump));
        assertEquals(0, OpenerCapture.dumpSize(null));
        assertEquals(0, OpenerCapture.dumpSize(""));
        // Null object and zero cap are safe too.
        assertEquals("", OpenerCapture.dumpFields(null));
        assertEquals("", OpenerCapture.dumpFields(subject, 0));
    }
}
