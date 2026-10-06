package forj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static method as an extension method: what {@code extension <A> String show(A a)}
 * compiles to. Its first parameter is the receiver, so {@code x.show()} calls
 * {@code Owner.show(x)} when {@code x}'s type has no method named {@code show}. Kept in class
 * files so extensions in libraries are found too.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Extension {}
