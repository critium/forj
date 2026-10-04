# forj for Scala developers

forj brings Scala's `for`/`yield` to plain `.java` files. It desugars the same way scalac
does, so most of what you know carries over. This page covers the differences.

## Side by side

```scala
// Scala
for {
  a <- 1 to n
  b <- a to n
  c <- b to n
  if a * a + b * b == c * c
} yield (a, b, c)
```

```java
// forj
forj {
    a <- range(1, n);
    b <- range(a, n);
    c <- range(b, n);
    guard(a * a + b * b == c * c);
} yield List.of(a, b, c);
```

| Scala | forj | Notes |
|---|---|---|
| `x <- e` | `x <- e;` | Semicolon required. |
| `_ <- e` | `_ <- e;` | |
| `if cond` | `guard(cond);` | Must directly follow a generator or another guard. |
| `y = expr` | `var y = expr;` | Any Java statement works here, not just definitions. |
| `} yield e` | `} yield e;` | Same position: after the block. |
| `for { ... } yield e` as an expression | same | Assign it, return it, pass it as an argument. |
| `(a, b) <- pairs` | — | No pattern binders. Bind a record and use its accessors. |
| `for { ... } doSomething` / `for ... do` | — | No `foreach` form; every block needs `yield`. |

## Desugaring

Purely syntactic, before type checking, like scalac:

```java
forj {                      forj.For.run(() ->
    x <- xs;                    forjFlatMap(forjFilter(xs, x -> p(x)), x -> {
    guard(p(x));                    var z = f(x);
    var z = f(x);       ==>         return forjMap(ys(z), y -> g(x, y));
    y <- ys(z);                 }));
} yield g(x, y);
```

- `flatMap`, `map` and `withFilter` become `forjFlatMap`, `forjMap` and `forjFilter`.
  There is no separate `withFilter`; the filter is strict.
- A value definition doesn't need tupling as in Scala: it is just a local variable inside
  the next lambda.
- A guard after a value definition is rejected rather than tupled (see the table above).

## Type classes, Java style

Scala resolves `flatMap` as a method on the value. Java's `Optional`, `List` and `Callable`
don't share an interface, so forj resolves instances through **overloading on static
imports**. An instance for `M[_]` is a class with static methods:

```java
static <A, B> M<B> forjFlatMap(M<A> m, Function<? super A, ? extends M<B>> f)
static <A, B> M<B> forjMap(M<A> m, Function<? super A, ? extends B> f)
static <A>    M<A> forjFilter(M<A> m, Predicate<? super A> p)    // optional, for guard
```

The generated calls are unqualified, so javac's overload resolution across every
`import static X.*` picks the instance. Think of the static import as bringing an implicit
instance into scope. Resolution is by the static type of the generator expression,
at compile time, with no runtime cost. JDK instances live in `forj.For` and are imported
automatically; `examples/.../StreamMonad.java` shows a third-party one.

As in Scala, every generator in a block must have the same container type: a `List`
generator followed by an `Optional` one doesn't compile.

## Effects: `Callable` is `IO`, not `Future`

`CompletableFuture` behaves like Scala's `Future`: it starts running when it's created.
forj supports `Callable` instead, which behaves like cats-effect `IO`:

```java
Callable<String> dashboard = forj {
    profile <- get("/profile/" + user);    // get: Callable<String>
    orders <- get("/orders/" + user);
} yield combine(profile, orders);

dashboard.call();   // runs now; each call() runs it again
```

- **Lazy:** building and composing does nothing; `call()` runs it.
- **Sequential:** like `IO`'s `flatMap`, each step waits for the previous one.
- **Direct style:** steps block instead of using callbacks. On virtual threads that's cheap,
  so there is no async runtime: `call()` runs on the calling thread.
- **Errors** are exceptions thrown from `call()`. A failed `guard` throws
  `NoSuchElementException`, like a failed `Future.filter`. (cats-effect `IO` has no
  `withFilter`, so there `if` guards don't compile at all.)

### Parallelism

As with `IO`, a comprehension never runs steps in parallel by itself. In cats-effect you'd
reach for `parMapN`, `parTupled`, `IO.both` or `parTraverse`. The Java equivalent is
structured concurrency (`StructuredTaskScope`), which also cancels siblings on failure.
forj doesn't have parallel helpers yet; they're planned as `Par.mapN(a, b, c, f)` and
friends returning lazy `Callable`s. `StructuredTaskScope` is still a preview API in JDK 27
and 28, so that will need `--enable-preview`. See `TODO.md`.

## Things Scala does that forj doesn't (yet)

- No pattern binders or refutable patterns in generators.
- No `foreach` form (`for ... do` without `yield`).
- `guard(cond)` instead of `if cond`; semicolons instead of newlines.
- No type ascription on binders (`x: Int <- ...`); types are inferred, as for lambda
  parameters.
- Binders follow Java lambda rules: they can't shadow locals of the enclosing method and
  can't be reassigned.
- Editor support is missing: IntelliJ, VS Code and Metals don't parse the syntax yet.
  With Metals, diagnostics are still correct because they come from the Mill build.
