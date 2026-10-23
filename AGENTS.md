# AGENTS.md: reading and writing forj code

forj is a javac plugin that adds Scala features to `.java` files. **Code using forj is not
valid plain Java**, so an editor, a linter, or your own knowledge of Java will flag it as
broken. It isn't. The Mill build is the source of truth: if `./mill __.test` passes, the
code is correct.

Two parts:
- **Part 1** is for writing or reading code that *uses* forj: this repo's `examples/`, and
  downstream repos such as forj-http and forj-codec.
- **Part 2** is for working on the plugin itself.

For detail, see `docs/java-developers.md` (full guide), `docs/scala-developers.md` (Scala
mapping) and `TODO.md` (known limits, follow-ups and design decisions).

## Part 1: code that uses forj

### Never "fix" these: they are forj syntax

| You see | It is | Compiles to |
|---|---|---|
| `forj { x <- fa; y <- fb; } yield x + y` | comprehension (Scala `for`/`yield`) | `forjFlatMap(fa, x -> forjMap(fb, y -> x + y))` with the `Monad<F>` passed in |
| `x <- e;` / `_ <- e;` | generator / generator whose value is discarded | a lambda parameter |
| `guard(cond);` | filter, directly after a generator | `forjFilter` (needs `FunctorFilter<F>`) |
| `f(A a)(using Show<A> s)` | context parameters (Scala 3 `using`) | `f(A a, @forj.Using Show<A> s)` |
| `f(x)(using hex)` at a call | passing an instance explicitly | `f(x, hex)` |
| `f(x)` where `f` has a `using` clause | **instances filled in by the compiler** | `f(x, Show.integer)` |
| `given Show<Integer> integer = ...;` | an instance (a member: `public static` by default) | `@forj.Given public static final Show<Integer> integer = ...;` |
| `given <A> Show<List<A>> list(using Show<A> e) {...}` | an instance built from others | a `@forj.Given` static method |
| `extension <A> String show(A a)(using Show<A> s)` | extension method | `@forj.Extension public static` |
| `money.show()` with no `show` on `Money` | extension call | `Show.show(money, Money.show)` |
| `interface Functor<F<_>>`, `<F<_>> F<A> pure(A a)` | higher-kinded type parameter | `F` is a plain type parameter |
| `F<A>` where `F` is declared `F<_>` | a type applied to a type constructor | `forj.Kind<F, A>` |
| `record User(long id, String name) derives Codec {}` | derived instance (Scala 3 `derives`) | adds `given Codec<User> codec = Codec.product(...)` to the record |
| `s"Hi $name, ${a + b}"` / `f"$x%.2f"` / `raw"C:\$p"` | string interpolation | `+` concatenation / `String.format` / raw text |
| `import static forj.Language.lifting;` | opt-in automatic lifting for this file | nothing; it changes how calls in the file resolve |

The keywords are contextual: `forj`, `given`, `using`, `extension` and `derives` remain
usable as ordinary names elsewhere. Each keyword form has a plain-Java spelling (`@Given`,
`@Using`, `@Extension`, `@Derives(X.class)`) with identical behaviour. Write that spelling
only where a file must stay parseable by plain tools.

### Invisible behaviour to account for when reading code

- **Calls can have arguments you don't see.** Any call to a method with a `using` clause gets
  its instances inserted. To know what runs, find the given. The search goes nearest first,
  and the first level with a match wins; two matches at one level is a compile error:
  1. local `given`s;
  2. the enclosing method's `using` parameters;
  3. the enclosing classes (and their supertypes);
  4. `import static`;
  5. the companions of the required type and its type arguments;
  6. `forj.Instances` (JDK types).
- **`x.m(...)` may not be a method of `x`.** It's an extension call when `x`'s type has no
  `m`. Lookup goes nearest first:
  1. the enclosing classes;
  2. `import static`;
  3. the type classes of givens in scope;
  4. the receiver's class and its type arguments' classes;
  5. `forj.Syntax`, which provides `map`, `flatMap`, `handleErrorWith`, `attempt`,
     `combine`, `sequence` and more on any `F<A>`.

  A real method always wins: `optional.map(f)` is `Optional.map`.
- **`F` is inferred from context,** not only from arguments:
  - a typed local: `IO<Receipt> r = checkout(order);` gives `F = IO`;
  - a `return`, or an assignment;
  - the parameter the call is passed to: `app.through(errors())` on an `HttpApp<IO>`.

  It is *not* inferred through a generic outer call (`describe(checkout(order))`). There,
  use a typed local or `Owner.<IO>checkout(order)`.
- **A `using` clause after varargs moves in front of them** in the compiled signature:
  `of(Route<F>... rs)(using Applicative<F> ap)` is `of(Applicative<F> ap, Route<F>... rs)` to
  plain Java callers and reflection.
- **Lifting**, only in files importing `forj.Language.*`:
  - `liftArguments` wraps a JDK `List`/`Optional`/`Callable` passed where a `Kind` is
    expected;
  - `liftReceivers` lets `list.map(f)` reach `Syntax.map`;
  - `liftResults` unwraps a `Kind` assigned to a JDK type.

### Rules when writing forj code

- Every generator ends with `;`. Generators are top-level statements of the block, never
  inside `if`/loops.
- `guard(...)` must directly follow a generator or another guard.
- No `return` and no `yield` inside a `forj { }` block; the result goes after it,
  `} yield expr`. (`yield` inside a `switch` expression is fine.)
- All generators in one block use the same `F`. Nested blocks may differ.
- Binder names are lambda parameters: they can't reuse an enclosing local's name and can't be
  reassigned.
- Put `using` parameters in their own clause, after the ordinary ones:
  `f(A a)(using Monad<F> m, Show<A> s)`.
- Comprehensions need `Monad<F>`. Ask for the weakest capability that works:
  - `Applicative` with `map2` for independent steps;
  - `Functor` for `map` alone.
- Two `using` overloads must differ in their ordinary parameter types. forj doesn't rank "more
  specific" overloads.
- For `derives X`, the type class `X` must declare:
  - static `field(String, Function<T, F>)(using X<F>)` and
    `product(Function<Object[], T>, Field...)`;
  - optionally `deriveProduct`/`deriveField`, for a generated straight-line encoder.

  Every component needs an `X` given in scope.
- No Jackson by default in forj projects: forj-http's JSON is its own streaming parser, and
  other parsers plug in behind an SPI.

### Reading errors

- `forj: ...` errors come from the plugin and point at your code. Read them first. javac
  often adds a second "cannot be applied to given types" error for the same call, which is
  noise once the `forj:` error is fixed.
- `forj: no given X<T> for m` means no instance of that type is in scope. Either import or
  declare one, or the effect lacks that capability: `FunctorFilter<Callable>` doesn't exist.
- `forj: cannot infer the type of using parameter ...` means `F` has no context to come from.
  Add a typed local, or explicit type arguments.
- `forj: ambiguous givens for ...` lists the clashing instances. Remove one, or pass one
  explicitly with `(using x)`.
- `plug-in not found: Forj` in a build with annotation processors means the plugin is missing
  from the processor path. See the README's `ForjModule`.
- Editors (IntelliJ, VS Code, Metals) show red on forj syntax and can't navigate into it.
  Ignore them; build instead.

### Build and verify

- `bin/fetch-jdk`: run once. It fetches the pinned JDK 28 EA into `.jdk/`. Everything uses
  preview features and `.jdk/preview`.
- `./mill __.test` runs all tests.
- `./mill plugin.test.testOnly forj.plugin.ExtensionsTest` runs one test class.
- `./mill examples.test` runs the example tests.
- To see what forj generated:
  - `def forjDebug = true` on a Mill module (or `-Xplugin:"Forj debug"`) prints each
    desugared comprehension;
  - `-Dforj.trace=true` on the compiler JVM prints every resolver decision and the resolved
    class.
- `./mill __.publishM2Local` publishes to `~/.m2`, as `forj:forj-core` and
  `forj:forj-plugin` (`0.1.0-SNAPSHOT`). `bin/check-publish` then checks that a downstream
  build works against it. Downstream repos consume forj only this way.
- javadoc runs on sources rewritten by `forj.plugin.ExportSources`
  (`forjExportedSources` in `ForjModule`), because the `javadoc` tool can't load the plugin.
  Pages therefore show `forj.Kind<F, A>` and `using` instances as ordinary parameters.

## Part 2: working on the plugin

### Pipeline: where each feature lives

| Stage | File | Does | Tests |
|---|---|---|---|
| before parsing (text) | `SourceRewriter.java` | `forj {`/`<-`/`yield`, `given`, `using` clauses, `extension`, `F<_>` to `Kind`, `derives` to `@Derives`, varargs marker | most test classes |
| before parsing (text) | `Interpolations.java` | `s"..."`, `f"..."`, `raw"..."` | `InterpolationTest` |
| parse finished | `Desugarer.java` | comprehensions to `forjFlatMap`/`forjMap`/`forjFilter`; moves a varargs parameter after its `using` ones | `examples` tests, `DiagnosticsTest` |
| parse finished | `Deriver.java` | `@Derives` records get a `given` built via `product`/`field` | `DerivesTest` |
| before each class is attributed | `ImplicitResolver.java` | passes until nothing changes: `using` resolution (`CallSite.complete`), inference, the given search (`Search`) | `ImplicitsTest`, `ForjHttpLimitsTest` |
| same | `Lifting.java` | `forjLift`/`forjLower`, narrowing `Kind` results, opt-in lifting | `LiftingTest` |
| same | `Extensions.java` | extension-method lookup | `ExtensionsTest` |
| same | `DerivationSpecializer.java` | `deriveProduct`/`deriveField` specialization | `DerivesTest` |
| glue | `ForjPlugin.java`, `ForjParserFactory.java` | install the parser, wire the stages, map positions back | all |

Runtime pieces live in `core/` and are deliberately small:
- `forj.For` is what comprehensions call;
- `forj.Instances` holds instances for JDK types;
- `forj.Syntax` holds the default extensions;
- `forj.typeclass.*` is the CE2 ladder plus `Effect`/`ConcurrentEffect`, and `forj.effect.IO`
  is the effect;
- `forj.Language` holds the lifting switches.

### Invariants: breaking these breaks things far away

- **The text rewrite never adds or removes newlines,** and every edit is recorded in
  `PositionMap`, so diagnostics, stack traces and debug info point at the user's file. Edits
  must not overlap: `apply` throws `IndexOutOfBoundsException` if they do. When two rules could
  rewrite the same token, dedupe.
- **Generated trees need unique positions.** javac's `ArgumentAttr` caches argument types by
  start position, so two generated lambdas at one position get each other's types ("bad
  return type in lambda"). Give each generated node its own position (`Deriver.lambda`), and
  prefer explicit type arguments on generated generic calls, so they aren't poly expressions.
- **Speculative attribution must not leak.** Everything the resolver type checks on copies
  goes through `swapArgumentCache` and a discarding diagnostic handler (see
  `CallSite.speculate`). Half-resolved code has errors that aren't real.
- **Symbols on generated trees don't survive.** `DeferredAttr` copies trees and drops symbols,
  so `QualIdent`-style binding crashes later (TransTypes). Refer to classes by full name, and
  where a local shadows the package root, through `((pkg.Cls) null).member`
  (`CallSite.owner`).
- **A resolved call must look resolved on the next pass.** The pass loop runs until nothing
  changes. A rule that re-matches its own output (for example, a varargs call after the givens
  were inserted) loops forever: check for arguments already in place (`usingsInPlace`).
- **Pass order is fixed in one place, `ImplicitResolver.pass`:** unshadow, the
  `forjLower`/`forjLift`/step plumbing, extensions, `using`, then narrowing. Keep it there.
- **With `-processorpath`, javac loads `-Xplugin` plugins only from the processor path.** Any
  build recipe must put the plugin there too.

### Working rules

- **Write a failing test first.** It's usually a `compile(Map.of("lib/X.java", ..., "app/App.java", ...))` case using
  `ImplicitsTest.compile`, then `c.call("app.App", "run")`. Confirm it fails for the
  reported reason before fixing. After the fix, check that the test fails again with the fix
  switched off.
- **Match the surrounding code:**
  - the code is 4 spaces for now; a move to 2 spaces is planned in `TODO.md`;
  - javadoc on non-trivial methods, saying *why*;
  - one-line comments for javac quirks.
- **Time-box hard javac debugging** and record findings in `TODO.md` rather than going deep
  silently.
- Commit only when asked. Don't modify the forj-codec, forj-http or planning repos
  (`../forj-mega-project/`) from a forj session without asking; other sessions work there.
- **Finishing a change that affects users:**
  - update `docs/java-developers.md` (and `docs/scala-developers.md` if Scala users would
    expect otherwise);
  - update `TODO.md`;
  - run `./mill __.test`, then `./mill __.publishM2Local && bin/check-publish`.
