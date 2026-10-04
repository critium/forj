package forj.examples;

import static forj.examples.StreamMonad.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class Examples {
    private Examples() {}

    record User(String name, Optional<String> managerName) {}

    record Address(String city) {}

    static final Map<String, User> USERS = Map.of(
            "ana", new User("ana", Optional.of("bo")),
            "bo", new User("bo", Optional.empty()));

    static final Map<String, Address> ADDRESSES = Map.of("bo", new Address("Lisbon"));

    static Optional<User> findUser(String name) {
        return Optional.ofNullable(USERS.get(name));
    }

    static Optional<Address> findAddress(String name) {
        return Optional.ofNullable(ADDRESSES.get(name));
    }

    /**
     * Scala:
     *   for {
     *     user    <- findUser(name)
     *     mgrName <- user.managerName
     *     address <- findAddress(mgrName)
     *   } yield address.city
     */
    public static Optional<String> managersCity(String name) {
        return forj {
            user <- findUser(name);
            mgrName <- user.managerName();
            address <- findAddress(mgrName);
        } yield address.city();
    }

    /**
     * Scala:
     *   for {
     *     a <- 1 to n
     *     b <- a to n
     *     c <- b to n
     *     if a*a + b*b == c*c
     *   } yield (a, b, c)
     */
    public static List<List<Integer>> pythagoreanTriples(int n) {
        return forj {
            a <- range(1, n);
            b <- range(a, n);
            c <- range(b, n);
            guard(a * a + b * b == c * c);
        } yield List.of(a, b, c);
    }

    /**
     * Value definitions (Scala's {@code x = expr}) are plain local variables,
     * and a guard filters the generator right above it.
     */
    public static List<String> labels() {
        var suits = List.of("♠", "♥");
        return forj {
            rank <- List.of(1, 2, 3, 4);
            guard(rank % 2 == 0);
            var label = rank == 4 ? "K" : String.valueOf(rank);
            suit <- suits;
        } yield label + suit;
    }

    /** {@code _ <- check} binds and discards, as in Scala. */
    public static Optional<Integer> divide(int a, int b) {
        return forj {
            _ <- b == 0 ? Optional.empty() : Optional.of(true);
        } yield a / b;
    }

    /** Comprehensions nest, and Stream works through StreamMonad without plugin changes. */
    public static List<String> nestedWithStreams() {
        Stream<String> pairs = forj {
            x <- Stream.of("a", "b");
            Optional<String> upper = forj {
                s <- Optional.of(x);
            } yield s.toUpperCase();
            y <- Stream.of(1, 2);
        } yield upper.orElseThrow() + y;
        return pairs.toList();
    }

    static List<Integer> range(int from, int toInclusive) {
        return Stream.iterate(from, i -> i <= toInclusive, i -> i + 1).toList();
    }
}
