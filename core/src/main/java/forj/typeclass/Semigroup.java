package forj.typeclass;

/** A way to combine two {@code A}s into one; must be associative. */
public interface Semigroup<A> {

    A combine(A x, A y);
}
