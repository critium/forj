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

### Parallel calls with `Par`

Generators always run one after another. To run calls at the same time, combine them with
`forj.Par` and bind the combined result. Steps after it are sequential again, so a call that
needs one of the parallel answers simply goes on the next line:

```java
record Parts(String profile, String orders, String recommendations) {}

Callable<String> dashboard = forj {
    parts <- Par.mapN(                                  // 1. three calls at once
            get("/profile/" + user),
            get("/orders/" + user),
            get("/recommendations/" + user),
            Parts::new);
    shipping <- get("/shipping/" + itemCount(parts.orders()));   // 2. then one that needs the orders
} yield render(parts, shipping);
```

With each call taking 200 ms, this takes about 400 ms: one round for the three parallel calls
and one for shipping, instead of 800 ms for all four in a row. This is the example server in
`examples/.../http/DashboardServer.java`.

`Par` is still lazy: `Par.mapN` returns a `Callable`, and nothing starts until `call()`.
Each call opens a `StructuredTaskScope`, runs every task on its own virtual thread, and
waits for all of them. If one fails, the others are cancelled and `call()` throws that
task's exception.

| Method | Does |
|---|---|
| `Par.mapN(a, b, f)` (up to 4 tasks) | Runs the tasks in parallel, then combines the results with `f`. |
| `Par.traverse(list, f)` | Runs `f` on every element in parallel; results keep the list's order. |
| `Par.sequence(tasks)` | Runs a list of tasks in parallel; results keep the list's order. |

`StructuredTaskScope` is a preview API in JDK 27 and 28, so forj builds with
`--enable-preview`.

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

## String interpolation

Put values straight into a string, instead of `+` chains or `String.format`:

```java
String name = "ana";
int total = 1250;

s"Hi $name, you owe ${total / 100} dollars"   // "Hi ana, you owe 12 dollars"
f"$name%-6s|${total / 100.0}%8.2f"             // "ana   |   12.50"
raw"C:\temp\$name.txt"                        // C:\temp\ana.txt  (backslashes kept)
```

| Form | Does |
|---|---|
| `$name` | inserts a variable (letters, digits, `_`; a following `.x` stays text) |
| `${expression}` | inserts any expression, including method calls and nested quotes |
| `$$` | a literal `$` |
| `s"..."` | joins the parts like `+` does; escapes like `\t` work as usual |
| `f"..."` | `String.format`: put a format right after a value (`$price%.2f`); no format means `%s`; write `%%` for a literal `%` |
| `raw"..."` | like `s`, but `\` is an ordinary character, handy for paths and regexes |

Each interpolated string is a single expression: `s"ab$x".length()` works, and
`s"${1}${2}"` is `"12"`, not `3`. Mistakes are compile errors:

```
forj: $ must be followed by a name, {expression} or $
forj: write $$ for a literal $, not \$
```

and errors inside `${...}` point at the exact place in the expression.

Not supported yet: triple-quoted (`s"""..."""`) multi-line strings, and checking `f` formats
against the value types at compile time (Scala does; here a mismatch fails when it runs).

## Type classes with `given` and `using`

A *type class* is an interface describing something a type can do, like `Comparator`, but
looked up by type instead of passed by hand. forj finds the right implementation at compile
time.

```java
public interface Show<A> {
    String show(A a);

    given Show<Integer> integer = i -> Integer.toString(i);              // an instance

    given <A> Show<List<A>> list(using Show<A> element) {                // built from another
        return xs -> xs.stream().map(element::show).toList().toString();
    }

    static <A> String show(A a) using Show<A> s {                       // asks for one
        return s.show(a);
    }
}

Show.show(List.of(1, 2));   // "[1, 2]"
```

- **`given`** declares an instance. In a class it becomes a `public static` member (write
  `private` or another modifier to change that).
- **`using`** declares a parameter that callers leave out: after the parameter list
  (`f(A a) using Show<A> s`), or inside it (`f(A a, using Show<A> s)`).
- **At each call** the plugin works out the type the parameter needs (`Show<List<Integer>>`
  above), finds a given for it, and passes it: here `Show.list(Show.integer)`. If none
  exists, or two do, the build fails:

  ```
  forj: no given Show<java.time.Instant> for show
  forj: ambiguous givens for Show<java.lang.Integer>: [Report.natural, Report.reversed]
  ```

  (javac then adds its own "cannot be applied" error for the same call.)

### Which given is picked

There can be many givens of the same type: `Show.integer` next to the type class, a
`given Show<Integer> hex` in some method, another in a class. The plugin picks by two
things, in order:

1. **The type.** Only givens of exactly the required type count. `Show.show(42)` needs a
   `Show<Integer>`, so `Show<String>` and `Show<Money>` givens are never considered.
2. **The nearest scope.** Among those, the closest one wins:

| Level | Where | Example |
|---|---|---|
| 1 | a local `given` declared earlier in the block | `given Show<Integer> hex = ...;` then `Show.show(255)` gives `0xff` |
| 2 | the enclosing method's `using` parameters | generic code uses whatever its caller had |
| 3 | givens in the enclosing classes | `Accounting.ledger` formats money inside `Accounting` |
| 4 | givens brought in with `import static` | `import static ...Euro.*;` formats money in euros |
| 5 | givens next to the type class or the data type | `Show.integer`, `Money.show`: the defaults, found without imports |

Two matches at the *same* level is a compile error, never a guess.

All of these are runnable in `examples/.../typeclasses/Resolution.java` and
`ImportedGivens.java`, with the expected output in `ResolutionTest`:

```java
// by type: three different instances for three types
Show.show(42);                  // "42"      Show.integer
Show.show("hi");                // "\"hi\""   Show.string
Show.show(new Money(150));      // "$1.50"   Money.show

// derived instances pick each element's instance
Show.show(List.of(1, 2));                       // "[1, 2]"          Show.list(Show.integer)
Show.show(List.of(new Money(100)));             // "[$1.00]"         Show.list(Money.show)

// a local given beats the default, inside its block only
static String localGiven() {
    given Show<Integer> hex = i -> "0x" + Integer.toHexString(i);
    return Show.show(255);                       // "0xff"
}
static String withoutLocalGiven() {
    return Show.show(255);                       // "255"
}

// ...and it flows into derived instances
given Show<Integer> hex = ...;
Show.show(List.of(10, 11));                      // "[0xa, 0xb]"      Show.list(hex)

// generic code uses its caller's instance
static <A> String twice(A a) using Show<A> show { return Show.show(a) + " " + Show.show(a); }

twice(7);                                        // "7 7"             twice(7, Show.integer)
given Show<Integer> roman = i -> i == 7 ? "VII" : "?";
twice(7);                                        // "VII VII"         twice(7, roman)

// a class-level given beats the data type's own
final class Accounting {
    given Show<Money> ledger = m -> ...;
    static String report() { return Show.show(new Money(-420)); }   // "(420c)", not "$-4.20"
}

// an imported given beats the data type's own
import static forj.examples.typeclasses.Euro.*;
Show.show(new Money(1999));                      // "€19,99"          Euro.euros

// passing it yourself always wins: nothing is looked up
Show.show(3, stars);                             // "***"
```

And when it can't decide:

```java
final class Report {
    given Show<Integer> natural = i -> ...;
    given Show<Integer> padded = i -> ...;
    static String r() { return Show.show(1); }
}
// forj: ambiguous givens for ...Show<java.lang.Integer>: [Report.natural, Report.padded]
```

To get an instance directly: `Implicits.<Show<Integer>>summon()`. Instances in compiled
libraries are found too.

## Context parameters: passing things through without passing them

`using` isn't only for type classes. It also carries context that a lot of code needs and
nobody wants to thread by hand: the current request, a trace ID, a transaction. The dashboard
example sends the caller's trace ID to every downstream service:

```java
public record RequestContext(String traceId) { ... }

// the handler declares it once
private void dashboard(HttpExchange exchange) {
    given RequestContext request = RequestContext.from(exchange);
    respond(exchange, 200, dashboard(user).call());          // `request` passed for us
}

// everything below asks for it instead of taking it as an argument
Callable<String> dashboard(String user) using RequestContext request {
    return forj {
        parts <- Par.mapN(get("/profile/" + user), get("/orders/" + user),
                          get("/recommendations/" + user), Parts::new);
        shipping <- get("/shipping/" + itemCount(parts.orders()));
    } yield ...;
}

private Callable<String> get(String path) using RequestContext request {
    return () -> ... .header("X-Trace-Id", request.traceId()) ...;
}
```

`DashboardServerTest` checks all four downstream calls, including the three parallel ones,
arrive with the caller's trace ID.

Compared with Java's `ScopedValue`, which does a similar job at runtime:

- **Checked at compile time.** If a call needs a `RequestContext` and none is in scope, the
  build fails. A `ScopedValue` that isn't bound fails when the code runs.
- **Works with lazy code.** The context is an ordinary argument, captured when the `Callable`
  is built. A `ScopedValue` must still be bound when `call()` runs, which is easy to get
  wrong with lazy tasks.
- **Visible in signatures.** `using RequestContext request` says what a method needs.

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
