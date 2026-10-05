package forj.examples.tagless;

/** An algebra: charging a customer, in any effect F. Returns a transaction id. */
public interface Payments<F<_>> {
    F<String> charge(String customer, long cents);
}
