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
- Still in forj, needed by the libraries afterwards: `Effect`/`ConcurrentEffect` (step 1),
  `Schema<T>` + `@Derives` + the `derives` rewrite (step 6).

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
- Return-type inference for `using` methods works from a typed local, a `return` or an
  assignment, but not from an argument position: `describe(checkout(order))` can't infer
  `checkout`'s `F` from `describe`'s parameter (which itself comes from `describe`'s target).
  Needs inference across nested calls.

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
   - `Resource`, `Timer`/`Clock`, `Effect`/`ConcurrentEffect`, `LiftIO`;
   - law tests for the instances (functor/monad laws, `MonadError` and `Bracket` laws);
   - automatic lifting and lowering of JDK types at call sites of `F<_>` methods
     (`addBoth(List.of(1), List.of(2))` instead of `new ListK<>(...)`, and a `List` back);
   - a "most specific instance wins" rule if a type ever gets two instances on one ladder.
3. **Extension syntax.** Done: `extension` methods and `forj.Syntax` (`fa.map(f)`,
   `a.combine(b)`, ...). Follow-ups:
   - explicit type arguments on extension calls (`fa.<B>map(f)`);
   - extensions on JDK types that aren't a `Kind` (`list.map(f)` on a `java.util.List`
     needs a lift; see auto-lifting above);
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
