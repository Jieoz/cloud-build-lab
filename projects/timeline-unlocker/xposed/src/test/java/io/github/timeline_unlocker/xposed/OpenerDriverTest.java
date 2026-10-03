package io.github.timeline_unlocker.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class OpenerDriverTest {

    private static final String[] LINKS = {"https://www.google.com/maps/timeline"};
    private static final String[] CANDIDATES = {"akjz.l", "dcqz.a"};

    @Test
    public void confirmedCaptureGoesFirstWithArg() {
        List<OpenerDriver.Step> steps = OpenerDriver.ladder(
                true, "akjz", "l", "a=b:I:1", CANDIDATES, LINKS);
        // arg, then the null variant of the confirmed method (an arg failure may still pass with
        // null), then the remaining candidates, then the links.
        assertEquals(4, steps.size());
        assertEquals(OpenerDriver.KIND_VENEER_ARG, steps.get(0).kind);
        assertEquals("akjz", steps.get(0).className);
        assertEquals("l", steps.get(0).methodName);
        assertEquals(OpenerDriver.KIND_VENEER_NULL, steps.get(1).kind);
        assertEquals(OpenerDriver.KIND_VENEER_NULL, steps.get(2).kind);
        assertEquals("dcqz", steps.get(2).className);
        assertEquals(OpenerDriver.KIND_LINK, steps.get(3).kind);
    }

    @Test
    public void unconfirmedCaptureStillRunsCandidatesThenLinks() {
        List<OpenerDriver.Step> steps = OpenerDriver.ladder(
                false, "akjz", "l", "a=b:I:1", CANDIDATES, LINKS);
        assertEquals(3, steps.size());
        assertEquals(OpenerDriver.KIND_VENEER_NULL, steps.get(0).kind);
        assertEquals("akjz", steps.get(0).className);
        assertEquals("dcqz", steps.get(1).className);
        assertEquals(OpenerDriver.KIND_LINK, steps.get(2).kind);
    }

    @Test
    public void emptyStateOnlyLinks() {
        List<OpenerDriver.Step> steps = OpenerDriver.ladder(false, null, null, null, null, LINKS);
        assertEquals(1, steps.size());
        assertEquals(OpenerDriver.KIND_LINK, steps.get(0).kind);
    }

    @Test
    public void ladderIsCapped() {
        String[] many = new String[30];
        for (int i = 0; i < many.length; i++) many[i] = "https://example.com/" + i;
        List<OpenerDriver.Step> steps = OpenerDriver.ladder(
                true, "akjz", "l", "a=b:I:1", CANDIDATES, many);
        assertEquals(12, steps.size());
    }

    @Test
    public void wrapperSightingInsideWindowConfirms() {
        long t0 = 1_000_000L;
        assertTrue(OpenerDriver.confirms(1, t0 + 500, t0, 3_000));
        assertFalse(OpenerDriver.confirms(1, t0 + 3_500, t0, 3_000));
        assertFalse(OpenerDriver.confirms(1, t0 - 1, t0, 3_000)); // before the step began
        assertFalse(OpenerDriver.confirms(0, t0 + 100, t0, 3_000)); // nothing was attempted
    }

    @Test
    public void verdictLineShape() {
        assertEquals("opener veneer akjz.l(captured arg,2 fields) -> OPENED",
                OpenerDriver.verdict("veneer akjz.l(captured arg,2 fields)", true));
        assertEquals("opener link https://x -> no",
                OpenerDriver.verdict("link https://x", false));
    }
}
