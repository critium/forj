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

## Later
- Scala-style guards (`if cond` inside `forj { }`) instead of `guard(cond);`.
- Semicolon-free generators (`x <- xs` ended by a newline) like Scala's braces syntax.
