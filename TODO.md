# TODO

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
- Return-type inference for `using` methods: see "Toward real type classes" below.

## Toward real type classes

What `examples/.../typeclasses/` has today is type classes over plain types (`Show<Integer>`):
chosen by type at compile time, generic constraints (`using Show<A>`), derived and
retroactive instances. These are the gaps, most valuable first.

1. **Return-type polymorphism.** Infer type parameters from the expected type, not only from
   arguments, so `Integer zero = Monoid.empty();` finds `Monoid<Integer>` without writing
   `Monoid.<Integer>empty()`. Needs the call's target type (assignment, return, argument
   position) during resolution. Then add a `Monoid` example (`empty`, `combine`,
   `combineAll`): it shows what type classes do that interfaces can't.
2. **Higher-kinded type classes.** Java has no `F<_>`, so `Functor<F>`, `Monad<F>` and
   `Traverse<F>` can't be written, and forj's monads are static-import overloads rather than
   a type class (no generic code over "any monad"). Use the encoding from Arrow-Java and
   HighJ: a witness type per container (`ListKind.Witness`), `Kind<F, A>` with
   `narrow`/`widen`, then `given Monad<ListKind.Witness>` instances. Then decide:
   - whether forj comprehensions should desugar to `Monad<F>` calls instead of overloads;
   - whether the plugin can hide the `Kind` wrapping and unwrapping at call sites.
3. **Extension syntax.** `money.show()` instead of `Show.show(money)`, like Scala 3
   `extension` methods: would need the plugin to rewrite unresolved method calls on a
   receiver to type class calls.
4. **Coherence (decide, don't necessarily build).** Like Scala, forj allows more than one
   instance per type (local givens), so a value can be shown differently at different call
   sites. Haskell forbids that. Decide whether to offer a strict mode.
5. **A better example.** `Show` is little more than `Function<A, String>`. Replace or extend
   the package with `Monoid` (after 1) and `Functor`/`Monad` (after 2); until then, consider
   renaming the package to `givens` so it doesn't promise more than it shows.

## More Scala syntax

Most of these only need the text-rewrite stage (`SourceRewriter`), sometimes plus the
desugarer. Grouped by effort.

### Easy (about an hour each)

- **`val`:** `val total = price * qty;` becomes `final var total = ...;`.
- **Scala-style guards:** `if x > 1;` inside `forj { }` instead of `guard(x > 1);`.
- **Value definitions without `var`:** `y = x * 2;` inside `forj { }` becomes
  `var y = x * 2;`. Unambiguous there: assigning an outer local inside the generated
  lambdas is illegal anyway.
- **String interpolation:** `s"Hi $name, you owe ${total / 100}"` becomes
  `"Hi " + name + ", you owe " + (total / 100)`. javac's tokenizer sees `s"..."` as an
  identifier followed by a string literal, so it's easy to find. Java's own string templates
  were withdrawn after JDK 22, so this fills a real gap.

### Easy to medium (half a day)

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

- **Extension methods:** see "Toward real type classes" (item 3).
- **Tuples:** `(a, b) <- pairs;`, `return (x, y)`. Needs tuple types in `core` and
  destructuring.
- **Named and default arguments:** `connect(host = "x", retries = 3)`. Needs the method
  signature at the call site, so it belongs in the resolution stage with `given`/`using`.
