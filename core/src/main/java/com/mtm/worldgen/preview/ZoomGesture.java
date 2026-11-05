package com.mtm.worldgen.preview;

/**
 * Turns a stream of scroll or pinch amounts into whole zoom steps. A mouse wheel sends one event per notch, a trackpad
 * a stream of small fractions, many of them zero; zooming a full step on each event sent a trackpad gesture through
 * several steps at once, and its zero amounts all zoomed out. Amounts add up until they reach one step, and a change
 * of direction starts over, so a gesture that turns back does not first undo what it had gathered.
 */
final class ZoomGesture {
    private final double step;
    private double gathered;

    /** @param step the amount that makes one zoom step */
    ZoomGesture(double step) {
        this.step = step;
    }

    /** Adds an amount and returns the whole steps it completes: negative to zoom in, positive to zoom out. */
    int add(double amount) {
        if (amount == 0.0 || Double.isNaN(amount)) {
            return 0;
        }
        if (Math.signum(amount) != Math.signum(gathered)) {
            gathered = 0.0;
        }
        gathered += amount;
        int steps = (int) (gathered / step);
        gathered -= steps * step;
        return steps;
    }
}
