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

It works with `Optional`, `List` and `Callable` out of the box, and any other type can plug
in by providing three static methods (`forjFlatMap`, `forjMap`, and optionally `forjFilter`).
`forj.Par` adds parallel calls on structured concurrency, in the style of cats-effect's
`parMapN`.

- New to comprehensions? Read [docs/java-developers.md](docs/java-developers.md).
- Coming from Scala? Read [docs/scala-developers.md](docs/scala-developers.md).

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
   guards become `forjFilter`. javac then type checks the result as ordinary Java.

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
| `core/` | `forj.For`: monad instances for `Optional`, `List`, `Callable`. `forj.Par`: parallel combinators. No dependencies. |
| `plugin/` | The javac plugin: `SourceRewriter` (text stage), `Desugarer` (tree stage), and the glue that installs them into javac. |
| `examples/` | Example code and tests, including a third-party monad instance (`StreamMonad`) and a small HTTP server (`http/DashboardServer`). |
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
