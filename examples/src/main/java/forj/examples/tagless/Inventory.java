package forj.examples.tagless;

import forj.data.Unit;

/** An algebra: what a program can ask of the warehouse, in any effect F. */
public interface Inventory<F<_>> {
    F<Integer> stock(String item);

    F<Unit> reserve(String item, int quantity);
}
