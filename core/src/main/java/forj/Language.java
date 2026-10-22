package forj;

/**
 * Opt-in language features, switched on per file with a static import, like Scala's
 * {@code import scala.language.implicitConversions}:
 *
 * <pre>{@code
 * import static forj.Language.liftResults;     // one feature
 * import static forj.Language.lifting;         // all three lifting features
 * }</pre>
 *
 * A file without the import behaves as if the feature didn't exist. The values themselves
 * mean nothing; the forj plugin only looks at which of them a file imports.
 *
 * <h2>Lifting</h2>
 * JDK types like {@code Callable}, {@code List} and {@code Optional} aren't a {@code Kind}, so
 * code generic in {@code F} needs them wrapped ({@code new CallableK<>(c)}) and unwrapped. The
 * lifting features let the plugin do that, using the same {@code forjLift} overloads and
 * {@code @Lower} methods comprehensions use (forj's own in {@code forj.For}, a library's
 * through {@code import static}).
 */
public final class Language {
    private Language() {}

    /**
     * A call returning {@code Kind<F, A>} whose value goes into a JDK type (a typed local, a
     * {@code return} or an assignment) infers {@code F} from that type and is unwrapped:
     * {@code Callable<User> u = Json.decodeF(bytes);}.
     */
    public static final String liftResults = "liftResults";

    /**
     * A JDK value used as the receiver of an extension method that takes a {@code Kind} is
     * wrapped first: {@code callable.flatMap(f)}, {@code list.map(f)}. A method the type has
     * itself still wins ({@code optional.map(f)} is {@code Optional.map}).
     */
    public static final String liftReceivers = "liftReceivers";

    /**
     * A JDK value passed to a method with {@code using} parameters, where that method expects
     * a {@code Kind}, is wrapped first: {@code addBoth(callableA, callableB)}.
     */
    public static final String liftArguments = "liftArguments";

    /** All three: {@link #liftResults}, {@link #liftReceivers} and {@link #liftArguments}. */
    public static final String lifting = "lifting";
}
