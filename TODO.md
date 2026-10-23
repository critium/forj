# TODO

## Publish forj for the forj-http libraries

**Status:** done 2026-10-06 for local use. `./mill __.publishM2Local` publishes
`forj:forj-core` and `forj:forj-plugin` `0.1.0-SNAPSHOT` to `~/.m2`, and `bin/check-publish`
builds and runs a downstream project against them (the `ForjModule` recipe is in the README,
"Using forj from another build"). forj-http lives in `../forj-mega-project/`, planned in
`../forj-mega-project/planning/lets-make-something-meaningfully-warm-lark.md`.

Follow-ups:

- Downstream builds hard-code the path to forj's `.jdk/preview`; the Mill plugin below
  should own fetching the JDK instead.
- Later: publish `PinnedJdk`/`ForjJava`/`ForjTests`/`ForjModule` as a Mill plugin so the
  libraries stop copying them (and `bin/fetch-jdk`, `bin/make-preview-jdk`).
- `@Derives` + the `derives` rewrite (step 6): **done 2026-10-22.**
  - The generic `product`/`field` convention, plus the optional specialized
    `deriveProduct`/`deriveField` encoder; see the Java guide, "Deriving instances".
  - Follow-ups:
    - specialized decoding (`deriveRead` overloads picked by a witness argument);
    - the specialized form for generic records;
    - sealed-interface sums;
    - self-recursive records (needs a lazy instance, e.g. an optional `X.lazy(Supplier)`
      convention);
    - forj-codec: implement `deriveProduct`/`deriveField` in `Codec`, then rerun the encode
      benchmarks. (`Effect`/`ConcurrentEffect`, step 1, are done: `forj.typeclass`, with `IO` a
  `ConcurrentEffect` and `Callable` an `Effect`.) No `Schema<T>` in core any more: `derives`
  generates a call to the type class's own `product(...)` (forj-codec's `Codec.product`).
  - **Consequence for Smithy (forj-http milestone 2):** the plan had records and `.smithy`
    shapes produce one shared `Schema`, with protocols interpreting it, so adding a
    protocol needed no regenerated code. Without it, Smithy codegen emits a
    `Codec.product(...)` call per format, which is fine for JSON, but each new protocol needs
    its own generated code.
  - If pluggable protocols matter then, add `Schema<T>` back as an extra: `derives` keeps
    calling `X.product(...)` and additionally passes a schema. Decide at Smithy time.

## Annotation processors break forj syntax

**Status:** fixed 2026-10-22 (found 2026-10-06 in forj-codec's JMH benchmarks).

- **Cause:** not forj's parser. When a build declares annotation processors, it passes
  `-processorpath`, and javac then loads `-Xplugin` plugins from the processor path only, not
  the classpath. The forj plugin sat on the classpath, so it never loaded
  (`plug-in not found: Forj`), and every forj file failed to parse.
- **Fix:** `ForjModule` overrides `annotationProcessorsResolvedMvnDeps`. When a module has
  processors, it adds the forj plugin to the processor path. This is in forj's `build.mill` and
  in the downstream recipe (README, "Using forj from another build"), and `bin/check-publish`
  compiles forj syntax next to JMH's processor to keep it fixed.
- **Downstream:** copy the new `annotationProcessorsResolvedMvnDeps` into the library's
  `ForjModule`. forj-codec can then drop `-proc:none` and its bytecode-generator workaround,
  and use JMH's processor directly.

## Generated code can be shadowed by locals named like a package

**Status:** fixed 2026-10-22 (found 2026-10-06).

- **Symptom:** the plugin generates fully qualified references (`forj.For.forjFlatMap(...)`,
  `forj.Syntax.map(...)`, `lib.Ords.integer`). A variable named like the first package
  (`byte[] forj = ...;`, `int lib = 1;`) captured them: `cannot find symbol`,
  `int cannot be dereferenced`, or, inside a generic call, a javac crash in `TransTypes`.
- **Why not symbols:** attaching symbols (`make.QualIdent`) isn't enough. javac re-type-checks
  some arguments on its own copies of the tree, and its copier drops symbols, so the copy is
  captured again.
- **Fix:** where such a variable is actually in scope, the reference goes through a cast:
  `((forj.Syntax) null).map(...)`. A cast's type is resolved as a type, which a variable never
  hides, and a static member can be called through it. Otherwise references stay plain dotted
  names.
  - The `Desugarer` records the `forj` identifiers it generates, so the resolver rewrites only
    those, never user code.
  - Covered by `ShadowingTest`: a given, an extension call, a comprehension and a lifted
    receiver.
- **Remaining cost:** in that shadowed case only, `-Xlint:static` warns about a static member
  accessed through an expression.

## Found while building forj-http (2026-10-22)

**Status:** all fixed (2026-10-22, `ForjHttpLimitsTest`). forj-http can drop the workarounds.

- **Fixed: a nested type that redeclares `F<_>` crashed the rewriter**
  (`IndexOutOfBoundsException` from `SourceRewriter.apply`). The outer and inner scopes both
  covered the inner body, so each `F<X>` there got two overlapping edits. Now each is rewritten
  once. forj-http's `G<_>` renames (`Route.Endpoint`, `Routes.Node`) can go back to `F<_>`.
- **Fixed: `using` after a varargs parameter.** `of(Route<F>... routes)(using Applicative<F> ap)`
  now compiles to `of(@forj.Using Applicative<F> ap, Route<F>... routes)`:
  - the rewriter writes the `...` as `[]`, and the Desugarer moves the parameter last and makes
    it varargs again;
  - the resolver puts the instances before the varargs arguments;
  - explicit `Routes.of(a, b)(using ap)` works too, and so does a plain Java caller writing
    `Routes.of(ap, a, b)`.
- **Fixed: instance methods ignored the receiver's type arguments.** `routes.orNotFound()` on
  a `Routes<IO>` now looks for `Applicative<IO>`: the resolver binds the owner class's type
  variables from `asSuper(receiverType, owner)` (wildcard arguments stay unbound).
- **Fixed: arguments that leave `F` unconstrained no longer win over the target.**
  `Routes<IO> r = Routes.of(constant("a"))`, where `constant` is `<F<_>> Route<F>` and takes
  no `using`, used to speculate as `Route<Object>` and bind `F = Object`. Now a binding to
  exactly `Object` from an argument yields to the target when there is one.
- **Fixed: inferring `F` in argument position** (forj-http's middleware):
  `app.through(errors())`, where `errors()` is `<F<_>> Middleware<F> errors()(using
  MonadError<F, Throwable>)`. The parameter `errors()` is passed to (`through(Middleware<F>)`,
  with `F = IO` from the `HttpApp<IO>` receiver) is now a target, like a typed local. Every
  method the outer call could be must agree on that parameter's type.
  - Still open: if that parameter mentions the outer method's own type variables
    (`describe(checkout(order))`, with `describe` generic), the outer call must be inferred
    first; see "given/using follow-ups".

### Found in forj-http step 3 (2026-10-22)

**Status:** all fixed (2026-10-22): the resolver bugs in `ForjHttpLimitsTest`, javadoc in `bin/check-publish`.
forj-http can drop the renames (`errorsWith`, `accessLogTo`) and call `start` on the class.

- **Fixed: an overload whose `using` count equals the call's argument count took the
  arguments as its instances.** `log(s -> {})` next to `log()(using Applicative<F>)` picked
  `log()`, with the lambda as `ap`. The "every argument already passed" check compared
  against the ordinary parameters and let any lambda through. It now checks against all
  parameters, and a lambda or method reference only fits a functional interface. Passing the
  instance explicitly (`log(IO.concurrent)`) still picks `log()`.
- **Fixed: calling an overridden `using` method on the implementing class was "ambiguous".**
  The method search found both `Impl.start` and the `Starter.start` it overrides. Now
  overridden methods are dropped, as javac does (`MethodSymbol.overrides`).
- **Fixed: javadoc couldn't read forj sources.** The `javadoc` tool parses sources without
  the plugin. Now `forj.plugin.ExportSources` writes them as the text stage rewrites them, and
  `ForjModule` (and the downstream recipe in the README) points `docSources` there.
  `bin/check-publish` builds a downstream javadoc jar.
  - Docs show declarations as plain Java callers see them: `forj.Kind<F, A>` for `F<A>`, and
    `using` instances as ordinary parameters. Showing forj syntax would take a custom doclet.
  - A varargs parameter followed by a `using` clause is documented as an array (that move
    happens after parsing).
  - forj-http can replace its placeholder javadoc jar with the recipe's
    `forjExportedSources`/`docSources`.
  - **Fixed (found in forj-codec, 2026-10-22): the downstream recipe needed
    `javadocOptions`.** Some forj-core classes are preview class files (`IO`, `For`, `Par`,
    `Instances`, `forj.data.Either`; `Kind` and the type classes aren't), and javadoc reads
    them only with `--enable-preview --source 28`. The README recipe and `bin/check-publish`
    now set it, and check-publish's `app` documents a signature with `IO` in it.
  - **Fixed (found in forj-codec): `{@link}` to a method with a `using` clause.** Authors
    write `{@link #encode(Object, OutputStream)}`, which didn't resolve against the exported
    signature. `ExportSources` now appends the using parameters' types to `{@link}`,
    `{@linkplain}` and `@see` references that name such a method by its ordinary parameters,
    including `Other#m(...)` links from other files (`ExportSourcesTest`). Links that already
    list every parameter, or match no such method or more than one, stay as written.

## Enforce a coding style (2-space indentation)

**Status:** open, added 2026-10-06. The preferred style is 2 spaces; the code is 4 spaces
today.

- **Formatter:** google-java-format. Its Google style is 2-space indentation and not
  configurable. Palantir's format is 4 spaces, so it's out.
  - Mill 1.1.10 has it built in (`mill.javalib.spotless`, and `mill.javalib.palantirformat`
    for Palantir).
  - Configure Spotless with google-java-format, plus a check task that fails the build on
    unformatted files, for CI or a pre-commit hook.
- **`.editorconfig`** (`indent_style = space`, `indent_size = 2`), so IntelliJ and VS Code
  type the right indentation in the first place.
- **The catch, forj syntax:** every Java formatter (google-java-format, Palantir, Eclipse,
  Checkstyle) parses plain Java, so files using `forj { }`, `<-`, `given`, `(using ...)`,
  `F<_>` or `s"..."` don't parse.
  - `core/` and `plugin/` are plain Java (forj snippets in the plugin tests are inside
    strings): google-java-format, fully enforced.
  - `examples/` and downstream code using forj syntax: excluded from google-java-format.
    Only `.editorconfig` and a whitespace check apply there (2-space indentation, no tabs,
    no trailing spaces, final newline).
- **Forj-aware formatter (later):** a reversible masking mode in `SourceRewriter`.
  1. Replace each forj construct with a same-shape Java placeholder.
  2. Run google-java-format.
  3. Put the constructs back.

  Belongs with the editor-support work below.
- **Switching over:** the one-time reformat touches every file. Do it as its own commit and
  list that commit in `.git-blame-ignore-revs`, so `git blame` skips it
  (`git config blame.ignoreRevsFile .git-blame-ignore-revs`).

## semanticdb (Metals index) crashes on files that use `forj`

**Status:** open, time-boxed and parked on 2026-10-03.

**Why it matters:** Metals gets go-to-definition, find-references and symbol search from
semanticdb files that Mill builds (`./mill show examples.semanticDbData`). When the
semanticdb javac plugin crashes on a file, Metals has no index for that file. Compiling,
running and tests are not affected; only the editor index is.

**Repro:**

```sh
./mill show examples.semanticDbData
# semanticdb-javac: CompilationUnitException: .../Examples.java
#   Caused by: StringIndexOutOfBoundsException: Index -1 out of bounds for length 2981
#   at SemanticdbVisitor.correctForTabs(SemanticdbVisitor.java:457)
```

`StreamMonad.java` (same package, no `forj` blocks) indexes fine.

### What we know

- semanticdb-javac 0.12.3 has a bug: `correctForTabs` calls `lineMap.getPosition(line, 0)`,
  but columns start at 1, so any occurrence on **line 1** reads `source.charAt(-1)`.
- An instrumented copy of `SemanticdbVisitor` (recipe below) shows that the crashing
  occurrence is the `forj` identifier in `package forj.examples;` (line 1, start=8, end=12).
  So semanticdb resolves the package-declaration identifier to a symbol in this file, which
  is what pushes it into the line-1 path.
- Plain javac + semanticdb **without** the forj plugin does *not* crash, even for a file in
  `package forj.examples` that does `import static forj.For.*;` and calls `run(...)`
  (tested in the scratchpad). So something the plugin does makes `trees.getElement(...)`
  return a symbol for the package-decl `forj` identifier.
- Fixed already along the way (keep these):
  - the plugin now maps every tree position back to the original file after parsing
    (`ForjParserFactory.restoreOriginalPositions`), which fixed an earlier
    `Index 3039 out of bounds` crash;
  - the generated `import static forj.For.*;` has no position (`Position.NOPOS`);
  - generator lambda parameters are positioned on the binder `x` of `x <- e`;
  - `ForjModule` puts the plugin on the compile classpath, not `-processorpath`, so it can
    sit next to semanticdb's plugin.

### Suspects, in the order I'd check them

1. `Desugarer.run` does `unit.defs = translate(unit.defs)`, which also passes the
   `JCPackageDecl` through `TreeTranslator`, and `ensureStaticImport` rebuilds `unit.defs`.
   Check whether the package decl or its `pid` comes out as a different or shared node, or
   gets attributed differently (e.g. `TreeInfo.symbol` on the pid).
2. `restoreOriginalPositions` scans the whole unit with a `TreeScanner`; confirm it doesn't
   disturb the package decl (the positions look right: 8..12).
3. Reduce: one-line file in `package forj.examples` with a single `forj { x <- List.of(1); } yield x;`
   compiled with `-Xplugin:Forj` plus semanticdb. Then try `package com.acme;` and see if the
   crash follows the `forj` package name or not.

### Workarounds if the root cause is a semanticdb bug

- Upstream fix in semanticdb-javac: `lineMap.getPosition(line, 1)` in `correctForTabs`
  (sourcegraph/scip-java).
- Skip occurrences whose start is on line 1 in package declarations.
- Move the examples out of `forj.*` (e.g. `package examples;`) if only the `forj` package
  name triggers it.

### Instrumented semanticdb recipe

```sh
# sources: https://repo1.maven.org/maven2/com/sourcegraph/semanticdb-javac/0.12.3/semanticdb-javac-0.12.3-sources.jar
# 1. copy SemanticdbVisitor.java, rewrite packages/imports to the shaded names:
#    com.sourcegraph.semanticdb_javac -> com.sourcegraph.shaded.com.sourcegraph.semanticdb_javac
#    com.google.protobuf              -> com.sourcegraph.shaded.com.google.protobuf
# 2. print the tree right before `range = correctForTabs(range, lineMap, start);` in semanticdbRange
# 3. compile against the semanticdb jar, then run javac with
#    -processorpath <patched classes>:<semanticdb jar>:out/plugin/compile.dest/classes:plugin/src/main/resources
#    -Xplugin:Forj '-Xplugin:semanticdb -sourceroot:<repo> -targetroot:<out>'
#    and the -J--add-exports/--add-opens flags from build.mill (pluginJvmOptions)
```

## Editor syntax support

Editors parse `.java` files with their own parsers, without the plugin, so `forj { ... } yield e`
and `x <- e;` show up as syntax errors, and completion and hover don't work inside forj blocks.
`./mill` builds are unaffected.

What already parses as plain Java, for code that must stay editor friendly: `@Using` and
`@Given` instead of the `using`/`given` keywords, `<F>` with `Kind<F, A>` instead of `<F<_>>`
with `F<A>` (`F<A>` alone parses, it only fails type checking). Calls that leave `using`
arguments out still show as type errors. `forj { }`, `<-` and `s"..."` have no plain-Java
spelling.

### IntelliJ IDEA

- Write an IntelliJ plugin that teaches the Java PSI the forj syntax. Two routes:
  - **Language injection / lexer layer**: extend the Java lexer so `forj {`, `<-` and
    `} yield` are recognised, and map them onto Java PSI the way the plugin rewrites the
    text (`forj (() -> {`, `x = e`, `yield e; })`). That gives highlighting, completion and
    navigation for free.
  - **Lombok-style**: suppress the syntax and "cannot resolve symbol" errors inside forj
    blocks (`HighlightInfoFilter`), and add type inference for binders through
    `PsiAugmentProvider`. Less invasive, but weaker completion.
- Reuse `SourceRewriter`'s rules (statement-start `<-`, the yield-expression end, the
  same-length edits) so the editor and javac agree on what is forj syntax.
- Also needs: a code style rule so the formatter doesn't mangle `<-` into `< -`.

### VS Code

- TextMate grammar injection (`injectionSelector: L:source.java`) that highlights `forj`,
  `<-` and `yield` as keywords. That fixes colours only.
- For red squiggles and completion: VS Code's Java support (Red Hat's extension, built on
  Eclipse JDT) parses with JDT, not javac. Options:
  - a JDT language server extension that runs the `SourceRewriter` step on the source text
    before JDT parses it (the javac plugin's approach, ported to JDT);
  - or, for Metals users, have the presentation compiler load the forj javac plugin
    (see the Metals note below).

### Metals

- Metals' Java presentation compiler (completions, hover) runs javac without the plugin.
  Teach it to load javac plugins from the build's `javacOptions`, or contribute an option
  upstream. Diagnostics already come from the Mill build over BSP and are correct.

## `given` / `using` follow-ups

- When resolution fails, javac also reports "method cannot be applied" for the same call;
  suppress that duplicate.
- Calls to `using` methods are only considered when the method is declared in the sources
  being compiled, in an imported class, or in a class of the same package; a method reached
  through an instance of some other library type is skipped.
- Ambiguous givens are an error even where Scala would pick the more specific one.
- Each call site re-attributes its enclosing method up to the call (`Trees.getScope`), which
  is quadratic in method size. Cache scopes per method if it shows up in build times.
- Return-type inference for `using` methods works from a typed local, a `return`, an
  assignment, and an argument whose parameter type is known (`app.through(errors())`, fixed
  2026-10-22). It doesn't work when that parameter mentions the outer method's own type
  variables: `describe(checkout(order))` can't infer `checkout`'s `F` from `describe`'s
  parameter, which itself comes from `describe`'s target. Needs inference across nested calls.
- **No inference from method references.** `Codec.field("id", User::id)(using Codec<F>)`
  can't infer `F` from `User::id`, so forj-codec's hand-written codecs spell it out:
  `Codec.<User, Long>field("id", User::id)`. `derives` knows the component types and can
  generate that form, but inferring from a method reference's return type would make
  hand-written code read better.
  - **Cost:** about 40–60 lines in `ImplicitResolver.inferTypeArguments`, plus tests. For an
    argument `Q::m`:
    1. type-check `Q`;
    2. find `m`, choosing an overload by arity (only when it's unambiguous);
    3. bind the function's result type to `m`'s return type.

    That's one more speculative type-check per such argument.
  - **Implicit lambdas** (`u -> u.id()`) are much harder: typing the body depends on the very
    inference being done.
  - **Not urgent:** `derives` generates the explicit form, so this only helps hand-written
    codecs.
- **Overloads that take `using` parameters** are now chosen by their ordinary arguments
  (`decode(byte[])` vs `decode(InputStream)`, fixed 2026-10-06), so forj-codec can go back to
  real overloads instead of `read`/`write`. Still ambiguous: overloads that only differ in a
  parameter whose argument type isn't known yet (an implicit lambda), and no "most specific
  overload" ranking (e.g. `decode(Object)` vs `decode(String)` with a `String`).

## Toward real type classes

What `examples/.../typeclasses/` has today is type classes over plain types (`Show<Integer>`):
chosen by type at compile time, generic constraints (`using Show<A>`), derived and
retroactive instances. These are the gaps, most valuable first.

1. **Return-type polymorphism.** Done for a typed local, a `return` and an assignment
   (`IO<Receipt> p = checkout(order);` infers `F = IO`). Still missing: from an argument
   position. (`Semigroup`/`Monoid`/`Foldable` are done; see `hkt/Generic.combineAll`.)
2. **Higher-kinded type classes.** Done: `Kind<F, A>` with raw classes as witnesses, the CE2
   ladder in `forj.typeclass`, `IO`, `F<_>` syntax, comprehensions through `Monad<F>`.
   Follow-ups:
   - `Resource`, `Timer`/`Clock`, `LiftIO` (`Effect`/`ConcurrentEffect` are done);
   - law tests for the instances (functor/monad laws, `MonadError` and `Bracket` laws);
   - automatic lifting and lowering of JDK types: done 2026-10-22, opt-in per file
     (`import static forj.Language.liftResults` / `liftReceivers` / `liftArguments` /
     `lifting`). Gaps:
     - not in expression lambdas;
     - not for methods without `using` parameters;
     - not for arguments of unknown type (implicit lambdas);
     - fixed 2026-10-22 (`57970c9`): `liftResults` used to rewrite calls returning a bare
       type variable too (`List<String> xs = Json.decode(bytes);` inferred
       `A = Kind<List, String>`). forj-codec's `ForjApiTest` now covers both cases in one
       lifted file.
   - a "most specific instance wins" rule if a type ever gets two instances on one ladder.
3. **Extension syntax.** Done: `extension` methods and `forj.Syntax` (`fa.map(f)`,
   `a.combine(b)`, ...). Follow-ups:
   - explicit type arguments on extension calls (`fa.<B>map(f)`);
   - extensions on JDK types that aren't a `Kind`: done with `liftReceivers` (opt-in);
   - operators: Java has no user operators, so `|+|`, `>>`, `*>` stay named methods;
   - extensions declared in a compiled library are found through `import static`, the
     receiver's classes and `forj.Syntax`; a library class reachable only some other way
     isn't searched.
4. **Coherence (decide, don't necessarily build).** Like Scala, forj allows more than one
   instance per type (local givens), so a value can be shown differently at different call
   sites. Haskell forbids that. Decide whether to offer a strict mode.
5. **Applicative comprehensions (`ApplicativeDo`).** Today every comprehension becomes a
   chain of `flatMap`, so it needs `Monad<F>` even when its generators don't depend on each
   other:

   ```java
   forj { x <- a; y <- b; } yield x + y;     // b doesn't use x: map2(a, b, ...) is enough
   ```

   The desugarer would look at which earlier binders each generator (and guard and value
   definition) mentions, group the independent ones into `forjMap2`/`forjMapN` calls that
   need only `Applicative<F>`, and use `flatMap` only where a step really depends on an
   earlier one. Wins:
   - generic code can ask for `Applicative` and still use the syntax (works for
     applicatives with no `Monad`, like an error-accumulating `Validated`);
   - an effect's `Applicative` could run independent steps at the same time: `IO`'s
     `map2` would become `parMap2`, the way the dashboard example uses `Par.mapN` by hand
     today. That is a semantic choice (cats keeps `IO`'s `map2` sequential and puts the
     parallel one in `Parallel`), so it probably wants a separate `parForj { }` or a
     `Parallel<F>` instance rather than changing `forj { }`.

   Needs: a free-variable analysis of each step (binders are plain identifiers, so a
   `TreeScanner` over the step's expression suffices, minding shadowing in lambdas),
   `forjMap2`..`forjMapN` combinators in `For`, and diagnostics that still say `Monad` when
   a dependent step makes it necessary. Haskell's `ApplicativeDo` is the reference design,
   including its rule that the final `yield` must be a plain expression of the binders.
6. **Laws for the value type classes.** `Semigroup` associativity and `Monoid` identity, run
   against the `forj.Instances` monoids and `Money`, alongside the functor/monad law tests
   in 2.

## More Scala syntax

Most of these only need the text-rewrite stage (`SourceRewriter`), sometimes plus the
desugarer. Grouped by effort.

### Easy (about an hour each)

- **`val`:** `val total = price * qty;` becomes `final var total = ...;`.
- **Scala-style guards:** `if x > 1;` inside `forj { }` instead of `guard(x > 1);`.
- **Value definitions without `var`:** `y = x * 2;` inside `forj { }` becomes
  `var y = x * 2;`. Unambiguous there: assigning an outer local inside the generated
  lambdas is illegal anyway.

### Easy to medium (half a day)

- **String interpolation follow-ups** (`s`, `f` and `raw` are done):
  - triple-quoted `s"""..."""` multi-line strings (mapping to Java text blocks, whose
    indentation rules differ from Scala's);
  - compile-time checking of `f` format specs against the value types, like scalac;
  - custom interpolators (`json"..."`, `sql"..."`), e.g. a static method per prefix.

- **`forj { ... } do { ... }`:** the side-effect form without `yield`. Needs a
  `forjForeach` method in each monad instance.
- **Semicolon-free generators:** `x <- xs` ended by a newline, like Scala's brace syntax.
  The rewriter has line positions; the work is deciding when an expression continues on the
  next line.

### Medium (a day or more)

- **Placeholder lambdas:** `xs.map(_ * 2)` becomes `x -> x * 2`. The simple case is easy;
  Scala's rules for how far `_` reaches are subtle.
- **Pattern generators:** `Point(x, y) <- points;` using Java record patterns, filtering out
  elements that don't match (Scala's refutable patterns).

### Hard

- **Tuples:** `(a, b) <- pairs;`, `return (x, y)`. Needs tuple types in `core` and
  destructuring.
- **Named and default arguments:** `connect(host = "x", retries = 3)`. Needs the method
  signature at the call site, so it belongs in the resolution stage with `given`/`using`.
