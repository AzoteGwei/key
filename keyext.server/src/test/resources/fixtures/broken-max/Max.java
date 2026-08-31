/**
 * The wrong half of the acceptance-test pair, and the most important file in this test suite.
 *
 * <p>{@link #max} claims to return the larger of its two arguments and returns the first one
 * instead, so {@code max(1, 2) == 1} violates {@code ensures \result >= b}. It cannot be proved,
 * and any run of this project that says otherwise has a defect worth stopping for.
 *
 * <p>Written and kept here on purpose rather than borrowed from {@code key.ui/examples}: the
 * wrongness is the asset. If it lived upstream, someone fixing the example would quietly turn this
 * test into one that proves nothing.
 */
public final class Max {

    private Max() {
    }

    /*@ public normal_behavior
      @   ensures \result >= a && \result >= b;
      @   ensures \result == a || \result == b;
      @   assignable \nothing;
      @*/
    public static int max(int a, int b) {
        return a;
    }
}
