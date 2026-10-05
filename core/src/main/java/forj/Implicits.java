package forj;

/** Scala's {@code summon}: {@code Implicits.<Ordering<Integer>>summon()} returns that given. */
public final class Implicits {
    private Implicits() {}

    public static <T> T summon(@Using T instance) {
        return instance;
    }
}
