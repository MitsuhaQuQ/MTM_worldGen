package com.mtm.worldgen.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoomGestureTest {
    /** A mouse wheel zooms one step per notch, as before. */
    @Test
    void wheelNotchesZoomOneStepEach() {
        ZoomGesture zoom = new ZoomGesture(1.0);
        assertEquals(-1, zoom.add(-1.0));
        assertEquals(1, zoom.add(1.0));
        assertEquals(2, zoom.add(2.0));
    }

    /** A trackpad's small amounts add up to one step instead of each zooming a full step, and zeros do nothing. */
    @Test
    void trackpadAmountsGatherIntoSteps() {
        ZoomGesture zoom = new ZoomGesture(1.0);
        int steps = 0;
        for (int i = 0; i < 25; i++) {
            steps += zoom.add(0.0);
            steps += zoom.add(-0.1);
        }
        assertEquals(-2, steps);
    }

    /** Turning back starts over, so a gesture does not first have to undo what it gathered the other way. */
    @Test
    void changingDirectionStartsOver() {
        ZoomGesture zoom = new ZoomGesture(1.0);
        assertEquals(0, zoom.add(0.9));
        assertEquals(0, zoom.add(-0.5));
        assertEquals(-1, zoom.add(-0.5));
    }
}
