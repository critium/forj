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
forj {                      forjLower(
    x <- xs;                    forjFlatMap(forjFilter(forjLift(xs), x -> p(x)), x -> {
    guard(p(x));                    var z = f(x);
    var z = f(x);       ==>         return forjMap(forjLift(ys(z)), y -> g(x, y));
    y <- ys(z);                 }))
} yield g(x, y);
```

- `flatMap`, `map` and `withFilter` become `forjFlatMap`, `forjMap` and `forjFilter`, which
  take the `Monad<F>`, `Functor<F>` and `FunctorFilter<F>` instance as `using` parameters.
  There is no separate `withFilter`; the filter is strict.
- A value definition doesn't need tupling as in Scala: it is just a local variable inside
  the next lambda.
- A guard after a value definition is rejected rather than tupled (see the table above).

## Type classes and higher kinds

Unlike scalac, which calls `flatMap` on the value, forj always goes through a real
`Monad<F>` type class (cats-style), resolved at compile time like any `given`. That is what
lets one comprehension work for any `F`:

```java
static <F<_>> F<Integer> addBoth(F<Integer> a, F<Integer> b) using Monad<F> m {
    return forj { x <- a; y <- b; } yield x + y;
}
```

`F<_>` declares a higher-kinded parameter (Scala's `F[_]`). `F<A>` compiles to
`forj.Kind<F, A>`, the "lightweight higher-kinded types" encoding used by HighJ and
Arrow-Java, with the raw class as the witness: `Kind<List, A>`, `Monad<IO>`. Types forj owns
implement `Kind` directly (`IO<A> implements Kind<IO, A>`); JDK types are wrapped
(`ListK`, `OptionalK`, `CallableK`), which costs an allocation per step.

As in Scala, every generator in a block must have the same `F`: a `List` generator followed
by an `Optional` one doesn't compile.

## cats-effect 2, in Java

| cats-effect 2 | forj |
|---|---|
| `F[_]`, `F[A]` | `F<_>`, `F<A>` |
| `Functor`, `Applicative`, `Monad` | same names in `forj.typeclass` |
| `ApplicativeError[F, E]`, `MonadError[F, E]` | same |
| `Bracket[F, E]`, `Sync[F]`, `Async[F]`, `Concurrent[F]` | same (`Sync` is `Bracket<F, Throwable>`) |
| `Fiber[F, A]` with `join` / `cancel` | same |
| `Traverse`, `FunctorFilter` | same |
| `IO`, `IO.delay`, `IO.suspend`, `IO.async`, `unsafeRunSync()` | `forj.effect.IO`, same |
| `IO.race`, `parMapN` | `IO.race`, `IO.parMap2`, `Par.mapN` for `Callable` |
| `implicit` instances, `F: Sync` context bounds | `given`, `using Sync<F> sync` |
| tagless final algebras `trait Repo[F[_]]` | `interface Repo<F<_>>` |

Differences: no `Resource`, `Timer`, `ContextShift`, `Effect`/`ConcurrentEffect` or
`LiftIO`; no syntax extensions (`fa.map(f)` on any `F`; you call the instance:
`functor.map(fa, f)`); cancellation is thread interruption, checked between `IO` steps and
at blocking calls, rather than CE's cancellation tokens; `IO` blocks a virtual thread instead
of running on a fiber scheduler, so there is no `tailRecM` (the interpreter is stack safe on
its own).

## Effects: `Callable` and `IO`, not `Future`

`CompletableFuture` behaves like Scala's `Future`: it starts running when it's created.
forj supports `Callable` (lazy, `Sync`) and its own `IO` (lazy, `Concurrent`) instead:

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

As with `IO`, a comprehension never runs steps in parallel by itself. `forj.Par` is the
`parMapN` family, built on structured concurrency (`StructuredTaskScope`):

| cats-effect | forj |
|---|---|
| `(a, b, c).parMapN(f)` | `Par.mapN(a, b, c, f)` (2 to 4 tasks) |
| `list.parTraverse(f)` | `Par.traverse(list, f)` |
| `tasks.parSequence` | `Par.sequence(tasks)` |

```java
Callable<String> dashboard = forj {
    parts <- Par.mapN(get("/profile/" + user), get("/orders/" + user), get("/recommendations/" + user), Parts::new);
    shipping <- get("/shipping/" + itemCount(parts.orders()));   // sequential again: needs the orders
} yield render(parts, shipping);
```

The cats-effect equivalent:

```scala
for {
  parts    <- (getProfile(user), getOrders(user), getRecs(user)).parMapN(Parts.apply)
  shipping <- getShipping(itemCount(parts.orders))
} yield render(parts, shipping)
```

Like the cats-effect versions, these are lazy and structured: nothing runs until `call()`,
each call runs the tasks on virtual threads in one scope, and a failure cancels the
siblings and rethrows the failed task's exception. There is no tuple type, so the combining
function usually builds a record. `StructuredTaskScope` is a preview API in JDK 27 and 28,
so forj builds with `--enable-preview`.

## String interpolation

The three standard interpolators, with Scala's rules:

| Scala | forj | Becomes |
|---|---|---|
| `s"Hi $name ${a + b}"` | same | `("Hi " + (name) + " " + (a + b) + "")` |
| `f"$x%.2f $y"` | same | `java.lang.String.format("%.2f %s", (x), (y))` |
| `raw"a\n$b"` | same | backslashes doubled in the literal parts |
| `$$` | same | a literal `$` |

Only the text around each expression is rewritten; the expressions stay where they were, so
errors inside `${...}` point at the right column. Differences from Scala:

- No triple-quoted `s"""..."""` yet.
- `f` formats are not checked against the argument types at compile time; a mismatch is
  a runtime `IllegalFormatException`.
- No custom interpolators (`StringContext` extensions).

## `given` / `using`

Scala 3 syntax, resolved at compile time like scalac does:

| Scala 3 | forj |
|---|---|
| `given intOrd: Ordering[Int] = ...` | `given Ordering<Integer> intOrd = ...;` |
| `given listOrd[A](using Ordering[A]): Ordering[List[A]] = ...` | `given <A> Ordering<List<A>> listOrd(using Ordering<A> elem) { ... }` |
| `def max[A](xs: List[A])(using ord: Ordering[A]): A` | `static <A> A max(List<A> xs) using Ordering<A> ord { ... }` |
| `summon[Ordering[Int]]` | `Implicits.<Ordering<Integer>>summon()` |
| `import Instances.given` | `import static Instances.*;` |

Resolution follows Scala 3: candidates must have the required type, and the nearest scope
wins.

| Priority | forj | Scala 3 equivalent |
|---|---|---|
| 1 | local `given` declared earlier in the block | local givens |
| 2 | the enclosing method's `using` parameters | context parameters in scope |
| 3 | givens in the enclosing classes | givens in enclosing templates |
| 4 | `import static X.*` | `import X.given` |
| 5 | givens in the type class's class and its type arguments' classes | implicit scope (companion objects) |

Two matches at the same priority are an ambiguity error. Scala would also try to rank them by
specificity first; forj doesn't. Givens can take `using` parameters, so instances derive
recursively, and a local given flows into derived instances (`given Show<Integer> hex` makes
`Show.show(List.of(10, 11))` use `Show.list(hex)`).

`examples/.../typeclasses/Resolution.java` has one runnable case per rule, and
`docs/java-developers.md` walks through them.

Context parameters work the same way. The dashboard example declares
`given RequestContext request = RequestContext.from(exchange);` in the HTTP handler and
every downstream call takes `using RequestContext request`. In Scala you would write the same
thing. Unlike `ScopedValue`, which is Java's runtime take on dynamic context, a missing
context is a compile error, and lazy `Callable`s capture it when they are built.

Under the hood: `given` compiles to a `public static` member annotated `@forj.Given`, `using`
to a trailing parameter annotated `@forj.Using`. Both annotations are kept in class files, so
libraries can ship instances. At each call that leaves `using` arguments out, the plugin type
checks copies of the arguments, infers the method's type parameters, resolves the givens and
inserts them; javac then type checks the call as written out in full.

Differences from Scala 3:

- Givens must be named. In a class they are static members; local givens work inside
  method bodies.
- No ranking of ambiguous givens by specificity.
- No `given ... with { }` instance bodies: use a lambda or an anonymous class.
- No implicit conversions, no `using` on constructors, no `extension` methods.
- Type parameters that only appear in `using` parameters can't be inferred from the call;
  pass them explicitly (`Owner.<Integer>empty()`).
- `using` methods are found when declared in sources being compiled or in imported classes.

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
