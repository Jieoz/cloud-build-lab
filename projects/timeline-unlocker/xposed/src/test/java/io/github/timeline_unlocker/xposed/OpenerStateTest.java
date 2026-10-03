package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Properties;

public class OpenerStateTest {

    @Test
    public void requestsHandledOnce() {
        OpenerState state = new OpenerState(4);
        assertTrue(state.shouldHandle(1000));
        assertFalse(state.shouldHandle(1000)); // same request again: no
        assertTrue(state.shouldHandle(2000));
        assertEquals(2, state.accepted());
        assertFalse(state.shouldHandle(0)); // malformed: never handled
        assertFalse(state.shouldHandle(-5));
        assertEquals(2, state.accepted());
    }

    @Test
    public void oldRequestsFallOutOfTheWindow() {
        OpenerState state = new OpenerState(2);
        assertTrue(state.shouldHandle(1));
        assertTrue(state.shouldHandle(2));
        assertTrue(state.shouldHandle(3)); // evicts 1
        assertTrue(state.shouldHandle(1)); // acceptable again, window moved on
        assertEquals(4, state.accepted());
    }

    @Test
    public void longReadsAndWrites() {
        Properties props = new Properties();
        assertEquals(0, OpenerState.readLong(props, "missing"));
        OpenerState.writeLong(props, "open_request", 1727870400000L);
        assertEquals(1727870400000L, OpenerState.readLong(props, "open_request"));
        props.setProperty("junk", "not-a-number");
        assertEquals(0, OpenerState.readLong(props, "junk"));
    }

    @Test
    public void saveAndLoadRoundTrip() throws Exception {
        java.io.File file = java.io.File.createTempFile("opener", ".properties");
        file.deleteOnExit();
        Properties out = new Properties();
        OpenerState.writeLong(out, "open_request", 42L);
        OpenerState.writeLong(out, "veneer_class_seen", 43L);
        assertTrue(OpenerState.saveQuietly(out, file));
        Properties in = OpenerState.loadQuietly(file);
        assertEquals(42L, OpenerState.readLong(in, "open_request"));
        assertEquals(43L, OpenerState.readLong(in, "veneer_class_seen"));
        // Missing file and null arguments are quiet no-ops.
        assertEquals(0, OpenerState.readLong(
                OpenerState.loadQuietly(new java.io.File("/nonexistent/x.properties")), "k"));
        assertFalse(OpenerState.saveQuietly(null, file));
        assertFalse(OpenerState.saveQuietly(out, null));
    }
}
