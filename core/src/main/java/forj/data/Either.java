package forj.data;

import java.util.function.Function;

/** A value of one of two types: {@code Left} by convention for failure, {@code Right} for success. */
public sealed interface Either<L, R> {

    record Left<L, R>(L value) implements Either<L, R> {}

    record Right<L, R>(R value) implements Either<L, R> {}

    static <L, R> Either<L, R> left(L value) {
        return new Left<>(value);
    }

    static <L, R> Either<L, R> right(R value) {
        return new Right<>(value);
    }

    default <T> T fold(Function<? super L, ? extends T> onLeft, Function<? super R, ? extends T> onRight) {
        return switch (this) {
            case Left<L, R> l -> onLeft.apply(l.value());
            case Right<L, R> r -> onRight.apply(r.value());
        };
    }
}
