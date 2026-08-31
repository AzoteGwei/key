/**
 * A minimal, obviously correct specification used to exercise loading and proving.
 *
 * <p>Kept deliberately tiny: when a test using this fails, the failure should be about the server,
 * never about whether the specification itself is provable.
 */
public final class Adder {

    private Adder() {
    }

    /*@ public normal_behavior
      @   requires 0 <= a && a <= 1000;
      @   requires 0 <= b && b <= 1000;
      @   ensures \result == a + b;
      @   assignable \nothing;
      @*/
    public static int add(int a, int b) {
        return a + b;
    }
}
