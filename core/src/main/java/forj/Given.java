package forj;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type class instance: what {@code given T name = ...;} and
 * {@code given <A> T name(using ...) { ... }} compile to. Kept in class files so instances in
 * libraries are found too.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.LOCAL_VARIABLE})
public @interface Given {}
