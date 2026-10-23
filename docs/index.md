---
title: docs
---

# forj guides

Guides for forj, each for a different reader. API reference: [api](../api/), [examples](../examples/).

- **[forj for Java developers](java-developers.md)**: This guide assumes you know Java (lambdas, `Optional`, streams) but not Scala. It starts from the nested `flatMap` chains forj replaces, then covers `F<_>` and `IO`, type classes with `given`/`using`, `derives`, extension methods and string interpolation.
- **[forj for Scala developers](scala-developers.md)**: forj brings Scala's `for`/`yield` to plain `.java` files. It desugars the same way scalac does, so most of what you know carries over. This page covers the differences.
