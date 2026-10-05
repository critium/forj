package forj;

/**
 * {@code F} applied to {@code A}: how forj writes a higher-kinded type such as "some
 * {@code F<A>}" where {@code F} is itself a type parameter. Java has no {@code F<_>}, so
 * {@code F} is a witness: the raw class of the type constructor ({@code List}, {@code IO},
 * {@code Optional}), and {@code Kind<List, A>} stands for {@code List<A>}.
 *
 * <p>Types forj owns implement it directly ({@code IO<A> implements Kind<IO, A>}); JDK types
 * are wrapped ({@link forj.data.ListK}). Code rarely mentions it: the plugin's {@code F<_>}
 * syntax writes {@code F<A>} for {@code Kind<F, A>}, and forj comprehensions wrap and unwrap
 * for you.
 */
public interface Kind<F, A> {}
