package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpenerFreshTest {

    @Test
    public void freshWindowBoundaries() {
        long request = 1_000_000L;
        assertTrue(OpenerState.isFresh(request + 1, request, 60_000));
        assertTrue(OpenerState.isFresh(request + 60_000, request, 60_000));
        assertFalse(OpenerState.isFresh(request + 60_001, request, 60_000));
        assertFalse(OpenerState.isFresh(request - 1, request, 60_000)); // clock skew guard
        assertFalse(OpenerState.isFresh(0, 0, 60_000));
        assertFalse(OpenerState.isFresh(500_000, request, 60_000)); // now before request
    }
}
