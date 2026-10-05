package forj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks how to turn a {@code Kind<W, A>} back into the type it stands for, e.g.
 * {@code @Lower static <A> List<A> list(Kind<List, A> k)}. A comprehension over {@code List}
 * yields through it, so its result is a {@code List} again. Found the way givens are.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Lower {}
