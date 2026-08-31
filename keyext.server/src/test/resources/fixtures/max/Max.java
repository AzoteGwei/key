/**
 * The correct half of the acceptance-test pair.
 *
 * <p>Its only difference from {@code broken-max/Max.java} is the body of {@link #max}: the
 * specification, the class and the method signature are identical. Anything that reports the same
 * verdict for both files is broken, whichever verdict it reports.
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
        return a >= b ? a : b;
    }
}
