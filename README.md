# forj

Scala-style for comprehensions for Java, as a javac plugin. Your files stay `.java`.

```java
Optional<String> city = forj {
    user <- findUser(name);
    manager <- user.managerName();
    address <- findAddress(manager);
} yield address.city();
```

That compiles to the nested calls you would otherwise write by hand:

```java
findUser(name).flatMap(user ->
    user.managerName().flatMap(manager ->
        findAddress(manager).map(address -> address.city())));
```

Comprehensions run on real type classes: a block over `F` needs a `Monad<F>`, found at
compile time like any other `given`. Instances for `Optional`, `List`, `Callable` and forj's
own `IO` are built in, and the same block works for any `F`, including a type parameter.
`forj.Par` adds parallel calls on structured concurrency, in the style of cats-effect's
`parMapN`.

It also brings Scala 3's `given`/`using`: type classes and context parameters, resolved at
compile time:

```java
public interface Show<A> {
    String show(A a);
    given Show<Integer> integer = i -> Integer.toString(i);
    given <A> Show<List<A>> list(using Show<A> element) { ... }
    static <A> String show(A a)(using Show<A> s) { return s.show(a); }
}

Show.show(List.of(1, 2));   // the plugin passes Show.list(Show.integer); no instance, no build
```

Callers can still pass an instance themselves (`Show.show(xs)(using hexShow)`). For code that
must parse in any Java editor, `@Using`/`@Given` annotations do the same as the keywords.

And Scala's string interpolation:

```java
s"Hi $name, you owe ${total / 100}"        // "Hi " + name + ", you owe " + (total / 100)
f"$item%-10s $price%8.2f"                  // String.format("%-10s %8.2f", item, price)
raw"C:\temp\$file"                         // backslashes stay backslashes
```

- New to comprehensions? Read [docs/java-developers.md](docs/java-developers.md).
- Coming from Scala? Read [docs/scala-developers.md](docs/scala-developers.md).

## Higher-kinded types, the CE2 ladder and tagless final

Write `F<_>` for a type parameter that takes a type, and program against capabilities the way
cats-effect 2 does:

```java
public interface Inventory<F<_>> {                       // an algebra, in any effect F
    F<Integer> stock(String item);
    F<Unit> reserve(String item, int quantity);
}

public static <F<_>> F<Receipt> checkout(Order order)(using Sync<F> sync, Inventory<F> inventory, Payments<F> payments) {
    return forj {
        available <- inventory.stock(order.item());
        _ <- available >= order.quantity() ? sync.unit() : sync.<Unit>raiseError(new OutOfStock(...));
        _ <- inventory.reserve(order.item(), order.quantity());
        transaction <- payments.charge(order.customer(), order.totalCents());
    } yield new Receipt(order, transaction);
}

given Inventory<IO> inventory = new Live.Warehouse(stock);
given Payments<IO> payments = new Live.Bank();
IO<Receipt> program = checkout(order);                  // F = IO, from the target type
Receipt receipt = program.unsafeRunSync();              // nothing ran until here
```

The ladder is `Functor` → `Applicative` → `Monad` → `MonadError` → `Bracket` → `Sync` →
`Async` → `Concurrent`. `IO` is lazy and stack safe, and runs on virtual threads, with fibers,
`race` and `parMap2` on structured concurrency. A function asks for the least it needs, and
asking for more than an effect has is a compile error:
`forj: no given forj.typeclass.Concurrent<java.util.concurrent.Callable>`.
The runnable version is in `examples/.../tagless/`.

## Syntax at a glance

```java
List<String> labels = forj {
    rank <- List.of(1, 2, 3, 4);       // generator
    guard(rank % 2 == 0);              // filter on the generator above
    var label = rank == 4 ? "K" : String.valueOf(rank);   // value definition
    suit <- List.of("♠", "♥");
    _ <- List.of(1);                   // bind and discard
} yield label + suit;                  // the result
```

## How it works

`forj { ... } yield e` isn't valid Java, so the plugin works in two stages inside javac:

1. **Before parsing**, it rewrites the source text into Java that javac can parse:
   `forj {` becomes `forj (() -> {`, `x <- e;` becomes `x =  e;`, and `} yield e` moves
   inside the block. No lines are added or removed, and after parsing every position is
   mapped back to your original file, so errors, stack traces and debuggers point at the
   code you wrote.
2. **After parsing, before type checking**, it desugars each block the way scalac does:
   every generator but the last becomes `forjFlatMap`, the last becomes `forjMap`, and
   guards become `forjFilter`: methods that take the `Monad<F>` (or `Functor<F>`,
   `FunctorFilter<F>`) instance as a `using` parameter. javac then type checks the result as
   ordinary Java.
3. **Just before javac type checks each class**, it fills in `using` arguments that calls
   leave out. It type checks copies of the call's arguments to infer the method's type
   parameters, searches for a `given` of each required type (recursively, for givens that
   have `using` parameters of their own), and inserts it. No match, or two, fails the build.

The runtime library is small: `forj.For` holds the monad instances for JDK types and a
one-line `run` helper, and `forj.Par` holds the parallel combinators.

## Building

Requirements: macOS or Linux. Everything else is fetched for you.

```sh
bin/fetch-jdk        # downloads the pinned JDK (28 early access) into .jdk/
./mill __.test       # builds the plugin and runs all tests
./mill examples.runMain forj.examples.http.DashboardServer   # example HTTP server on :8080
curl localhost:8080/dashboard/ana
```

The example server shows lazy, parallel and sequential steps together. Three calls run in
parallel, then a fourth that needs one of their answers:

```java
Callable<String> dashboard = forj {
    parts <- Par.mapN(get("/profile/" + user), get("/orders/" + user), get("/recommendations/" + user), Parts::new);
    shipping <- get("/shipping/" + itemCount(parts.orders()));
} yield render(parts, shipping);
```

The build uses [Mill](https://mill-build.org). The plugin reaches into javac internals,
so every module compiles with the exact JDK in `.jdk/`, and any JVM that runs javac with the
plugin needs the `--add-exports`/`--add-opens` flags listed in `build.mill`
(`pluginJvmOptions`).

The whole project uses Java preview features (`StructuredTaskScope` is preview in JDK 27
and 28). javac gets `--enable-preview --source 28`, and every JVM runs from `.jdk/preview`,
a mirror of the JDK whose `java` launcher always adds `--enable-preview`
(`bin/make-preview-jdk`). That way even the JVMs Mill starts without our flags, such as its
test discovery step, have preview on.

To use forj in a module, mix in `ForjModule`:

```scala
object app extends ForjModule
```

Set `def forjDebug = true` to print each desugared comprehension during compilation.

## Project layout

| Path | What it is |
|---|---|
| `core/` | `forj.Kind` and the type class ladder (`forj.typeclass`), the `IO` effect (`forj.effect`), instances for JDK types (`forj.Instances`), what comprehensions compile to (`forj.For`), `forj.Par`, `@Given`/`@Using`/`@Lower`. No dependencies. |
| `plugin/` | The javac plugin: `SourceRewriter` and `Interpolations` (text stage), `Desugarer` (forj blocks), `ImplicitResolver` (`given`/`using`), and the glue that installs them into javac. |
| `examples/` | Example code and tests: a third-party monad (`StreamMonad`), a small HTTP server (`http/`), type classes with `given`/`using` (`typeclasses/`), generic code over any `F` and any value type: `Monad`, `Applicative`, `Semigroup`/`Monoid`, `Traverse` (`hkt/`), and tagless final with two interpreters (`tagless/`). |
| `bin/fetch-jdk` | Downloads and verifies the pinned JDK, then runs `bin/make-preview-jdk`. |
| `TODO.md` | Known issues and planned work. |

## Status and limitations

This is an experiment. It works and is tested, but:

- **Editors show errors.** IntelliJ, VS Code and Metals parse `.java` files without the
  plugin, so they mark `forj { }`, `<-` and `yield` as syntax errors and have no completion
  inside forj blocks. Builds are unaffected. Editor plugins are in `TODO.md`.
- **Metals indexing** (go-to-definition, find-references) currently fails on files that use
  forj, because of an interaction with the semanticdb plugin. Investigation notes are in
  `TODO.md`.
- **Tied to javac internals.** The plugin swaps javac's parser factory and edits its trees,
  so it targets one JDK at a time (currently JDK 28 early access).
- **Preview features.** Code using forj is compiled and run with `--enable-preview`.
- **Wrapping cost for JDK types.** Comprehensions always go through the `Monad<F>` type
  class, so `List`, `Optional` and `Callable` values are wrapped: about 3-4 ns per step,
  invisible next to real work (+3% for a 100×100 `List`), noticeable only in hot loops of
  tiny `Optional` chains. `IO` implements `Kind` itself and pays nothing.
