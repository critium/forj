package forj.typeclass;

/**
 * An effect that is both {@link Concurrent} and {@link Effect}: it can be run, and what runs
 * can start fibers, race and run in parallel. What a server asks for when its handlers may
 * do things at the same time. Like cats-effect 2's {@code ConcurrentEffect}.
 */
public interface ConcurrentEffect<F> extends Concurrent<F>, Effect<F> {}
