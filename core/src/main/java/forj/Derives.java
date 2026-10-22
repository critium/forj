package forj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Asks the forj plugin to derive type class instances for a record: what
 * {@code record User(long id, String name) derives Codec { }} compiles to. Written by hand it is
 * the plain-Java spelling: {@code @Derives(Codec.class) record User(long id, String name) { }}.
 *
 * <p>For each type class {@code X} listed, the record gets a {@code given X<User>} named after the
 * type class ({@code codec} for {@code Codec}), built through a convention {@code X} declares:
 *
 * <ul>
 *   <li><b>Required, the generic form:</b> a static {@code field(String name, Function<T, F> getter)}
 *       that takes {@code using X<F>}, and a static
 *       {@code product(Function<Object[], T> constructor, <field type>... fields)}. The derived
 *       instance is {@code X.product(a -> new User((long) a[0], (String) a[1]),
 *       X.<User, Long>field("id", u -> u.id()), ...)}. Each component's instance is resolved like
 *       any {@code using} argument, so givens in scope (a custom {@code X<Instant>}) apply.</li>
 *   <li><b>Optional, the specialized form:</b> a static {@code deriveProduct(constructor, encoder,
 *       fields...)} and {@code deriveField(out, index, value)} overloads. forj then also generates
 *       the encoder as straight-line code, {@code X.deriveField(out, 0, u.id());
 *       X.deriveField(out, 1, u.name()); ...}, and javac's overload resolution picks the
 *       {@code deriveField} for each component's type (a {@code long} one avoids boxing). A
 *       {@code deriveField} taking {@code using X<A>} covers every other type.</li>
 * </ul>
 *
 * forj knows nothing about what {@code X} does; the convention is all it uses.
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Derives {
    /** The type classes to derive, e.g. {@code Codec.class}. */
    Class<?>[] value();
}
