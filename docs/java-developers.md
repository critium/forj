# forj for Java developers

This guide assumes you know Java (lambdas, `Optional`, streams) but not Scala.

## The problem it solves

Chaining `flatMap` calls is how you combine values that might be absent, contain many
elements, or haven't been computed yet. With more than two steps it gets hard to read:

```java
Optional<String> city =
    findUser(name).flatMap(user ->
        user.managerName().flatMap(manager ->
            findAddress(manager).map(address ->
                address.city())));
```

forj lets you write the same thing as a flat list of steps:

```java
Optional<String> city = forj {
    user <- findUser(name);
    manager <- user.managerName();
    address <- findAddress(manager);
} yield address.city();
```

Read `user <- findUser(name);` as "take the value out of `findUser(name)` and call it
`user`". If any step is empty, the whole result is empty, exactly as with the nested
`flatMap` version. The compiler turns one into the other; there is no runtime magic.

## Syntax

| Form | Meaning |
|---|---|
| `x <- expr;` | **Generator.** `expr` is an `Optional`, `List`, `Callable`, ...; `x` is each value inside it. |
| `_ <- expr;` | Generator whose value you don't need (for example, a check that may be empty). |
| `guard(cond);` | **Filter.** Keeps only values for which `cond` is true. Must come directly after a generator (or another guard). |
| `var y = ...;` | Ordinary local variable, usable in later steps. |
| `} yield expr;` | **Result.** The value placed back into the container. |

The whole `forj { ... } yield expr` is an expression, so you can assign it, return it, or
pass it as an argument:

```java
assertEquals(List.of(2, 4), forj { x <- List.of(1, 2); } yield x * 2);
```

All generators in one block must use the same type. You can't mix a `List` generator with
an `Optional` one; that's a compile error. Nested blocks can use different types.

## What each type does

### `Optional` — stop at the first empty step

```java
Optional<Integer> total = forj {
    a <- parse("1");
    b <- parse("x");     // empty, so the rest is skipped
} yield a + b;           // Optional.empty()
```

### `List` — every combination

```java
List<String> pairs = forj {
    n <- List.of(1, 2);
    s <- List.of("a", "b");
} yield s + n;           // [a1, b1, a2, b2]
```

Like a nested `for` loop. With `guard`, it works like a `for` loop with an `if`:

```java
List<List<Integer>> triples = forj {
    a <- range(1, 20);
    b <- range(a, 20);
    c <- range(b, 20);
    guard(a * a + b * b == c * c);
} yield List.of(a, b, c);
```

Results are unmodifiable lists.

### `Callable` — lazy steps, run on demand

A comprehension over `Callable`s builds a bigger `Callable`. Nothing runs until you call it:

```java
Callable<String> dashboard = forj {
    profile <- get("/profile/" + user);      // get returns Callable<String>
    orders <- get("/orders/" + user);
} yield combine(profile, orders);

String json = dashboard.call();   // the requests happen here, one after another
dashboard.call();                 // and again here
```

This is different from `CompletableFuture`, which starts working as soon as it's created.
Steps run on the calling thread and simply block, which is cheap on a virtual thread.
`examples/.../http/DashboardServer.java` is a complete server built this way.

A failed `guard` makes `call()` throw `NoSuchElementException`.

## Adding your own types

Any generic type `M<A>` works if a statically imported class provides:

```java
public static <A, B> M<B> forjFlatMap(M<A> m, Function<? super A, ? extends M<B>> f)
public static <A, B> M<B> forjMap(M<A> m, Function<? super A, ? extends B> f)
public static <A>    M<A> forjFilter(M<A> m, Predicate<? super A> p)   // only needed for guard
```

`examples/.../StreamMonad.java` does this for `Stream` in a dozen lines. Use it with:

```java
import static forj.examples.StreamMonad.*;
```

No plugin changes are needed: the generated code calls `forjFlatMap(...)` unqualified,
and javac's normal overload resolution picks the right one from your static imports.
The JDK types (`Optional`, `List`, `Callable`) are imported automatically.

## Things that may surprise you

- **Every generator ends with `;`.** `x <- xs` without a semicolon is not recognised.
- **Guards go directly after a generator.** `x <- xs; var y = x * 2; guard(y > 2);` is an
  error. Put the condition on `x` instead: `guard(x * 2 > 2);`.
- **Generators must be top-level statements** of the block, not inside `if` or loops.
- **Names behave like lambda parameters**, because that's what they become. You can't
  reuse a name that's already a local variable in the enclosing method, and you can't
  reassign a bound name.
- **Don't write `return` inside the block.** The result goes after it, in `yield`.
- **`yield` inside the block is an error**, except inside a `switch` expression, where it
  means what it always means.
- **Editors show red.** IntelliJ and VS Code don't know the syntax yet, so they flag it.
  The Mill build is the source of truth.

## Error messages

forj reports its own errors with a `forj:` prefix, pointing at your code:

```
forj: guard(...) must directly follow a generator (x <- ...) or another guard(...)
forj: x <- ... must be a top-level statement of a forj { ... } block
forj: forj { ... } must be followed by yield <expr>
forj: yield goes after the block: forj { ... } yield expr
```

Type errors come from javac in terms of the generated calls. For example, using a type
with no instance mentions `forjMap` or `forjFlatMap`: it means no static import provides
those methods for that type.

## Seeing what it generates

In Mill, set `def forjDebug = true` on the module, or pass `-Xplugin:"Forj debug"` to javac.
Each comprehension is printed after desugaring:

```
[forj] Examples.java:41
forj.For.run(()->{
    return forjFlatMap(findUser(name), (user)->{
        return forjFlatMap(user.managerName(), (manager)->{
            return forjMap(findAddress(manager), (address)->{
                return address.city();
            ...
```
