/**
 * A method that cannot be proved automatically for a reason a person can name.
 *
 * <p>{@link #sumTo} is correct. What it lacks is a {@code loop_invariant}, so the prover has
 * nothing to reason about the loop with and stops. That distinction — wrong versus
 * under-specified — is what the diagnostics are for: an agent that is told "a loop invariant rule
 * applies here but cannot be instantiated" knows what to write next, whereas "one goal remains
 * open" tells it nothing.
 *
 * <p>{@code n} is deliberately unbounded above, so unwinding the loop cannot finish the proof
 * either. There is no way through this method except an invariant.
 */
public final class Summer {

    private Summer() {
    }

    /*@ public normal_behavior
      @   requires 0 <= n;
      @   ensures \result >= 0;
      @   assignable \nothing;
      @*/
    public static int sumTo(int n) {
        int total = 0;
        int i = 1;
        while (i <= n) {
            total += i;
            i++;
        }
        return total;
    }
}
