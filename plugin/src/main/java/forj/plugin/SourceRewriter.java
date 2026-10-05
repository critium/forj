package forj.plugin;

import com.sun.tools.javac.parser.Scanner;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens.Token;
import com.sun.tools.javac.parser.Tokens.TokenKind;
import com.sun.tools.javac.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites forj syntax, which javac cannot parse, into Java it can, before parsing:
 *
 * <pre>{@code
 * forj {                     forj (() -> {
 *     x <- xs;         ==>       x =  xs;
 *     y <- ys(x);                y =  ys(x);
 * } yield f(x, y);            yield f(x, y); });
 * }</pre>
 *
 * It also expands Scala string interpolation ({@link Interpolations}).
 *
 * No newlines are added or removed, so line numbers are unchanged. Positions in the result
 * index the rewritten text; {@link PositionMap} converts them back to the original file,
 * which is what the plugin does to the whole tree right after parsing.
 */
final class SourceRewriter {

    /**
     * @param source       the rewritten text javac parses
     * @param original     the file as written
     * @param positions    rewritten-text positions to original-file positions
     * @param arrows       position of each generator's {@code =}, to whether its binder was {@code _}
     * @param yields       position of each generated {@code yield}
     * @param missingYield position of each {@code forj} not followed by {@code yield}
     */
    record Result(char[] source, char[] original, PositionMap positions,
                  Map<Integer, Boolean> arrows, Set<Integer> yields, Set<Integer> missingYield) {

        /** The same result with arrows, yields and missing yields in original-file positions. */
        Result inOriginalPositions() {
            Map<Integer, Boolean> a = new HashMap<>();
            arrows.forEach((pos, anonymous) -> a.put(positions.toOriginal(pos), anonymous));
            Set<Integer> y = new HashSet<>();
            yields.forEach(pos -> y.add(positions.toOriginal(pos)));
            Set<Integer> m = new HashSet<>();
            missingYield.forEach(pos -> m.add(positions.toOriginal(pos)));
            return new Result(source, original, positions, a, y, m);
        }
    }

    /**
     * Each edit replaced original [origStart, origEnd) with rewritten [newStart, newEnd), sorted.
     * Positions inside inserted text map to where the insertion happened.
     */
    record PositionMap(int[] newStart, int[] newEnd, int[] origStart, int[] origEnd) {
        int toOriginal(int pos) {
            if (pos < 0) {
                return pos; // NOPOS
            }
            int i = java.util.Arrays.binarySearch(newStart, pos);
            if (i < 0) {
                i = -i - 2; // last edit starting before pos
            } else {
                while (i + 1 < newStart.length && newStart[i + 1] == pos) {
                    i++;
                }
            }
            if (i < 0) {
                return pos;
            }
            if (pos < newEnd[i]) {
                int origLength = origEnd[i] - origStart[i];
                return origStart[i] + Math.min(pos - newStart[i], Math.max(origLength - 1, 0));
            }
            return origEnd[i] + (pos - newEnd[i]);
        }
    }

    /** Replace [start, end) with text; ties at the same start apply in ascending order. */
    private record Edit(int start, int end, String text, int order, Kind kind) {}

    private enum Kind { TEXT, ARROW, ANONYMOUS_ARROW, YIELD, FORJ_WITHOUT_YIELD }

    private static final String FORJ = "forj";
    private static final String YIELD = "yield";
    private static final String GIVEN = "given";
    private static final String USING = "using";
    private static final String GIVEN_ANNOTATION = "@forj.Given ";
    private static final String USING_ANNOTATION = "@forj.Using ";

    private SourceRewriter() {}

    static Result rewrite(CharSequence input, ScannerFactory scanners, Log log) {
        String text = input.toString();
        boolean interpolates = Interpolations.mayContain(text);
        if (!interpolates && !text.contains("<-") && !text.contains(FORJ) && !text.contains(GIVEN)
                && !text.contains(USING) && !text.contains("<_>")) {
            return null;
        }
        List<Token> tokens = tokenize(text, scanners, log);
        List<Edit> edits = new ArrayList<>();
        if (interpolates) {
            var literals = Interpolations.find(text, tokens, log);
            if (!literals.isEmpty()) {
                for (var literal : literals) {
                    for (var e : literal.edits()) {
                        edits.add(new Edit(e.start(), e.end(), e.text(), 0, Kind.TEXT));
                    }
                }
                // the other rules see each interpolated string as one plain identifier
                tokens = tokenize(Interpolations.mask(text, literals), scanners, log);
            }
        }
        findForjBlocks(tokens, edits);
        findArrows(tokens, edits);
        findGivens(tokens, edits);
        findUsings(tokens, edits);
        findHigherKinds(tokens, edits);
        return edits.isEmpty() ? null : apply(text, edits);
    }

    // ------------------------------------------------------------ forj { } yield e

    private static void findForjBlocks(List<Token> tokens, List<Edit> edits) {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token forj = tokens.get(i);
            if (!isName(forj, FORJ) || tokens.get(i + 1).kind != TokenKind.LBRACE || !startsExpression(tokens, i)) {
                continue;
            }
            Token open = tokens.get(i + 1);
            int close = matchingBrace(tokens, i + 1);
            if (close < 0) {
                continue; // unbalanced; let javac report it
            }
            edits.add(new Edit(open.pos, open.pos + 1, "(() -> {", 0, Kind.TEXT));

            Token rbrace = tokens.get(close);
            int exprEnd = close + 1 < tokens.size() && isName(tokens.get(close + 1), YIELD)
                    ? expressionEnd(tokens, close + 2) : -1;
            if (exprEnd > close + 2) {
                Token yield = tokens.get(close + 1);
                // `} yield e` -> ` yield e; })`: the yield moves inside the block, same columns
                edits.add(new Edit(rbrace.pos, rbrace.pos + 1, " ", 0, Kind.TEXT));
                edits.add(new Edit(yield.pos, yield.pos, "", 0, Kind.YIELD));
                // inner comprehensions close first when several yields end at one spot
                edits.add(new Edit(tokens.get(exprEnd - 1).endPos, tokens.get(exprEnd - 1).endPos, "; })", -forj.pos, Kind.TEXT));
            } else {
                edits.add(new Edit(forj.pos, forj.pos, "", 0, Kind.FORJ_WITHOUT_YIELD));
                edits.add(new Edit(rbrace.pos + 1, rbrace.pos + 1, ")", 0, Kind.TEXT));
            }
        }
    }

    /** {@code forj} used as a value, not declared ({@code class forj {}}) or selected ({@code a.forj}). */
    private static boolean startsExpression(List<Token> tokens, int i) {
        if (i == 0) {
            return false;
        }
        return switch (tokens.get(i - 1).kind) {
            case DOT, CLASS, INTERFACE, ENUM, MONKEYS_AT -> false;
            default -> !isName(tokens.get(i - 1), "record");
        };
    }

    private static int matchingBrace(List<Token> tokens, int open) {
        int depth = 0;
        for (int j = open; j < tokens.size(); j++) {
            TokenKind k = tokens.get(j).kind;
            if (k == TokenKind.LBRACE) {
                depth++;
            } else if (k == TokenKind.RBRACE && --depth == 0) {
                return j;
            }
        }
        return -1;
    }

    /** Index just past the yield expression: it runs until a ; , or unmatched closer, as far as it can. */
    private static int expressionEnd(List<Token> tokens, int from) {
        int depth = 0;
        for (int j = from; j < tokens.size(); j++) {
            switch (tokens.get(j).kind) {
                case LPAREN, LBRACE, LBRACKET -> depth++;
                case RPAREN, RBRACE, RBRACKET -> {
                    if (depth-- == 0) {
                        return j;
                    }
                }
                case SEMI, COMMA -> {
                    if (depth == 0) {
                        return j;
                    }
                }
                default -> {}
            }
        }
        return tokens.size();
    }

    // ---------------------------------------------------------------- x <- e;

    private static void findArrows(List<Token> tokens, List<Edit> edits) {
        Deque<TokenKind> open = new ArrayDeque<>();
        TokenKind prev = null;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (atStatementStart(prev, open) && isArrowAt(tokens, i)) {
                Token lt = tokens.get(i + 1);
                boolean anonymous = t.kind == TokenKind.UNDERSCORE;
                if (anonymous) {
                    edits.add(new Edit(t.pos, t.endPos, "$", 0, Kind.TEXT));
                }
                edits.add(new Edit(lt.pos, lt.pos + 2, "= ", 0, anonymous ? Kind.ANONYMOUS_ARROW : Kind.ARROW));
            }
            switch (t.kind) {
                case LPAREN, LBRACE, LBRACKET -> open.push(t.kind);
                case RPAREN, RBRACE, RBRACKET -> open.poll();
                default -> {}
            }
            prev = t.kind;
        }
    }

    /** Directly inside a block (not a for header, argument list or array index). */
    private static boolean atStatementStart(TokenKind prev, Deque<TokenKind> open) {
        boolean inBlock = open.isEmpty() || open.peek() == TokenKind.LBRACE;
        return inBlock && (prev == TokenKind.LBRACE || prev == TokenKind.SEMI
                || prev == TokenKind.RBRACE || prev == TokenKind.ELSE);
    }

    /** {@code name <- ... ;} with {@code <-} written as one symbol and the statement ending in {@code ;}. */
    private static boolean isArrowAt(List<Token> tokens, int i) {
        if (i + 3 >= tokens.size()) {
            return false;
        }
        Token name = tokens.get(i);
        Token lt = tokens.get(i + 1);
        Token sub = tokens.get(i + 2);
        return (name.kind == TokenKind.IDENTIFIER || name.kind == TokenKind.UNDERSCORE)
                && lt.kind == TokenKind.LT && sub.kind == TokenKind.SUB && lt.endPos == sub.pos
                && endsAsStatement(tokens, i + 3);
    }

    /** Rules out {@code {a <- b, c}}-style array initializers, where {@code a < -b} is a real comparison. */
    private static boolean endsAsStatement(List<Token> tokens, int from) {
        int end = expressionEnd(tokens, from);
        return end < tokens.size() && tokens.get(end).kind == TokenKind.SEMI;
    }

    // ------------------------------------------------- given T x = e;  given <A> T f(...) { }

    /**
     * {@code given} starting a declaration becomes {@code @forj.Given} plus the modifiers it
     * implies: {@code static final} for a field, {@code static} for a method, {@code final} for
     * a local variable. Like Scala, a given in a class is public unless an access modifier
     * says otherwise.
     */
    private static void findGivens(List<Token> tokens, List<Edit> edits) {
        boolean[] classBody = classBodies(tokens);
        Deque<Boolean> braces = new ArrayDeque<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind == TokenKind.LBRACE) {
                braces.push(classBody[i]);
            } else if (t.kind == TokenKind.RBRACE) {
                braces.poll();
            } else if (isName(t, GIVEN) && startsMember(tokens, i) && i + 1 < tokens.size()
                    && (tokens.get(i + 1).kind == TokenKind.IDENTIFIER || tokens.get(i + 1).kind == TokenKind.LT)) {
                TokenKind declares = firstAtDepthZero(tokens, i + 1, TokenKind.EQ, TokenKind.LPAREN, TokenKind.SEMI);
                if (declares == null || declares == TokenKind.SEMI) {
                    continue;
                }
                boolean inClass = Boolean.TRUE.equals(braces.peek());
                boolean hasAccess = i > 0 && switch (tokens.get(i - 1).kind) {
                    case PUBLIC, PRIVATE, PROTECTED -> true;
                    default -> false;
                };
                String modifiers = !inClass ? "final"
                        : (hasAccess ? "" : "public ") + (declares == TokenKind.EQ ? "static final" : "static");
                edits.add(new Edit(t.pos, t.endPos, GIVEN_ANNOTATION + modifiers, 0, Kind.TEXT));
            }
        }
    }

    /** At a declaration boundary, optionally after an access modifier. */
    private static boolean startsMember(List<Token> tokens, int i) {
        if (i == 0) {
            return true;
        }
        return switch (tokens.get(i - 1).kind) {
            case LBRACE, RBRACE, SEMI, PUBLIC, PRIVATE, PROTECTED -> true;
            default -> false;
        };
    }

    /** For each token index holding a {@code {}, whether it opens a class body. */
    private static boolean[] classBodies(List<Token> tokens) {
        boolean[] result = new boolean[tokens.size()];
        boolean header = false;      // saw class/interface/enum/record since the last ; { }
        Deque<Integer> news = new ArrayDeque<>();   // paren depth of each open `new X(`
        int newClosedAt = -1;        // index of the `)` that closed a `new X(...)`
        int parens = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            TokenKind prev = i > 0 ? tokens.get(i - 1).kind : null;
            switch (t.kind) {
                case CLASS, INTERFACE, ENUM -> header |= prev != TokenKind.DOT;
                case NEW -> news.push(parens);
                case LPAREN -> parens++;
                case RPAREN -> {
                    parens--;
                    if (!news.isEmpty() && news.peek() == parens) {
                        news.pop();
                        newClosedAt = i;
                    }
                }
                case LBRACE -> {
                    // `new X(...) {` opens an anonymous class; `new int[] {` and method bodies don't
                    boolean anonymous = newClosedAt == i - 1;
                    result[i] = header || anonymous;
                    header = false;
                    if (!news.isEmpty() && news.peek() == parens) {
                        news.pop();   // `new int[] {...}` array initializer
                    }
                }
                case SEMI, RBRACE -> header = false;
                default -> {
                    if (isName(t, "record") && i + 2 < tokens.size()
                            && tokens.get(i + 1).kind == TokenKind.IDENTIFIER
                            && (tokens.get(i + 2).kind == TokenKind.LPAREN || tokens.get(i + 2).kind == TokenKind.LT)) {
                        header = true;
                    }
                }
            }
        }
        return result;
    }

    // ------------------------------------------------- (using T x)  and  f(...) using T x {

    private static void findUsings(List<Token> tokens, List<Edit> edits) {
        for (int i = 1; i + 2 < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (!isName(t, USING) || tokens.get(i + 1).kind != TokenKind.IDENTIFIER) {
                continue;
            }
            TokenKind prev = tokens.get(i - 1).kind;
            if (prev == TokenKind.LPAREN || prev == TokenKind.COMMA) {
                // inside a parameter list: `(List<A> xs, using Ordering<A> ord)`
                edits.add(new Edit(t.pos, t.endPos, USING_ANNOTATION.strip(), 0, Kind.TEXT));
            } else if (prev == TokenKind.RPAREN) {
                usingClause(tokens, i, edits);
            }
        }
    }

    /**
     * Scala-style clause after the parameter list, {@code max(List<A> xs) using Ordering<A> ord {},
     * moved into the parameter list as trailing {@code @forj.Using} parameters.
     */
    private static void usingClause(List<Token> tokens, int using, List<Edit> edits) {
        Token rparen = tokens.get(using - 1);
        boolean noParams = tokens.get(using - 2).kind == TokenKind.LPAREN;
        int angles = 0;
        int end = -1;
        List<Token> commas = new ArrayList<>();
        for (int j = using + 1; j < tokens.size() && end < 0; j++) {
            Token t = tokens.get(j);
            switch (t.kind) {
                case LT -> angles++;
                case GT -> angles--;
                case GTGT -> angles -= 2;
                case GTGTGT -> angles -= 3;
                case COMMA -> {
                    if (angles == 0) {
                        commas.add(t);
                    }
                }
                case LBRACE, SEMI, THROWS -> {
                    if (angles == 0) {
                        end = j;
                    }
                }
                default -> {}
            }
        }
        if (end < 0) {
            return;
        }
        Token u = tokens.get(using);
        edits.add(new Edit(rparen.pos, rparen.endPos, noParams ? " " : ",", 0, Kind.TEXT));
        edits.add(new Edit(u.pos, u.endPos, USING_ANNOTATION.strip(), 0, Kind.TEXT));
        for (Token comma : commas) {
            edits.add(new Edit(comma.endPos, comma.endPos, " " + USING_ANNOTATION.strip(), 0, Kind.TEXT));
        }
        int at = tokens.get(end).pos;
        edits.add(new Edit(at, at, ") ", 0, Kind.TEXT));
    }

    // ------------------------------------------------------- F<_> and F<A>

    /**
     * Higher-kinded type parameters: {@code interface Functor<F<_>>} or
     * {@code static <F<_>, A> F<A> pure(A a)}. The {@code <_>} is dropped, and in the
     * parameter's scope (the class body, or the method) every {@code F<X>} becomes
     * {@code forj.Kind<F, X>}.
     */
    private static void findHigherKinds(List<Token> tokens, List<Edit> edits) {
        for (int i = 1; i + 3 < tokens.size(); i++) {
            Token name = tokens.get(i);
            TokenKind before = tokens.get(i - 1).kind;
            TokenKind closer = tokens.get(i + 3).kind;
            if (name.kind != TokenKind.IDENTIFIER || (before != TokenKind.LT && before != TokenKind.COMMA)
                    || tokens.get(i + 1).kind != TokenKind.LT || tokens.get(i + 2).kind != TokenKind.UNDERSCORE
                    || (closer != TokenKind.GT && closer != TokenKind.GTGT && closer != TokenKind.GTGTGT)) {
                continue;
            }
            // drop `<_>`; in `<F<_>>` the tokenizer sees `>>` as one token, so only its first `>` goes
            edits.add(new Edit(tokens.get(i + 1).pos, tokens.get(i + 3).pos + 1, "", 0, Kind.TEXT));
            int listStart = typeParameterListStart(tokens, i);
            int[] scope = higherKindScope(tokens, listStart);
            if (scope == null) {
                continue;
            }
            for (int j = scope[0]; j < scope[1]; j++) {
                Token t = tokens.get(j);
                if (j != i && t.kind == TokenKind.IDENTIFIER && t.name() == name.name()
                        && j + 1 < tokens.size() && tokens.get(j + 1).kind == TokenKind.LT
                        && tokens.get(j - 1).kind != TokenKind.DOT
                        && !(j + 2 < tokens.size() && tokens.get(j + 2).kind == TokenKind.UNDERSCORE)) {
                    edits.add(new Edit(t.pos, tokens.get(j + 1).endPos, "forj.Kind<" + t.name() + ", ", 0, Kind.TEXT));
                }
            }
        }
    }

    /** Index of the {@code <} opening the type parameter list that contains index {@code i}. */
    private static int typeParameterListStart(List<Token> tokens, int i) {
        int depth = 0;
        for (int j = i - 1; j >= 0; j--) {
            switch (tokens.get(j).kind) {
                case GT -> depth++;
                case GTGT -> depth += 2;
                case GTGTGT -> depth += 3;
                case LT -> {
                    if (depth-- == 0) {
                        return j;
                    }
                }
                default -> {}
            }
        }
        return i - 1;
    }

    /**
     * [from, to) token range where a higher-kinded parameter is in scope: for a class's
     * parameter, its body; for a method's, the rest of the declaration up to the end of its
     * body (or the {@code ;} of an abstract method).
     */
    private static int[] higherKindScope(List<Token> tokens, int listStart) {
        boolean classLevel = listStart >= 2 && tokens.get(listStart - 1).kind == TokenKind.IDENTIFIER
                && switch (tokens.get(listStart - 2).kind) {
                    case CLASS, INTERFACE, ENUM -> true;
                    default -> isName(tokens.get(listStart - 2), "record");
                };
        int parens = 0;
        for (int j = listStart; j < tokens.size(); j++) {
            switch (tokens.get(j).kind) {
                case LPAREN -> parens++;
                case RPAREN -> parens--;
                case LBRACE -> {
                    if (parens == 0) {
                        int close = matchingBrace(tokens, j);
                        return close < 0 ? null : new int[] {classLevel ? j : listStart, close};
                    }
                }
                case SEMI -> {
                    if (parens == 0 && !classLevel) {
                        return new int[] {listStart, j};
                    }
                }
                default -> {}
            }
        }
        return null;
    }

    /** The first of {@code kinds} outside parentheses and type arguments, or null. */
    private static TokenKind firstAtDepthZero(List<Token> tokens, int from, TokenKind... kinds) {
        int depth = 0;
        for (int j = from; j < tokens.size(); j++) {
            TokenKind k = tokens.get(j).kind;
            if (depth == 0) {
                for (TokenKind wanted : kinds) {
                    if (k == wanted) {
                        return k;
                    }
                }
            }
            switch (k) {
                case LT, LPAREN, LBRACKET -> depth++;
                case GT, RPAREN, RBRACKET -> depth--;
                case GTGT -> depth -= 2;
                case GTGTGT -> depth -= 3;
                case LBRACE, RBRACE -> {
                    return null;
                }
                default -> {}
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ apply

    private static Result apply(String text, List<Edit> edits) {
        edits.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::order));
        StringBuilder out = new StringBuilder(text.length() + edits.size() * 8);
        Map<Integer, Boolean> arrows = new HashMap<>();
        Set<Integer> yields = new HashSet<>();
        Set<Integer> missingYield = new HashSet<>();
        int n = edits.size();
        int[] newStart = new int[n], newEnd = new int[n], origStart = new int[n], origEnd = new int[n];
        int copied = 0;
        for (int i = 0; i < n; i++) {
            Edit e = edits.get(i);
            out.append(text, copied, e.start());
            newStart[i] = out.length();
            origStart[i] = e.start();
            origEnd[i] = e.end();
            switch (e.kind()) {
                case ARROW -> arrows.put(out.length(), false);
                case ANONYMOUS_ARROW -> arrows.put(out.length(), true);
                case YIELD -> yields.add(out.length());
                case FORJ_WITHOUT_YIELD -> missingYield.add(out.length());
                case TEXT -> {}
            }
            out.append(e.text());
            newEnd[i] = out.length();
            copied = e.end();
        }
        out.append(text, copied, text.length());
        char[] source = new char[out.length()];
        out.getChars(0, out.length(), source, 0);
        return new Result(source, text.toCharArray(), new PositionMap(newStart, newEnd, origStart, origEnd),
                arrows, yields, missingYield);
    }

    // ----------------------------------------------------------------- tokens

    /** Scans with javac's own lexer so strings, comments and text blocks are never touched. */
    private static List<Token> tokenize(String input, ScannerFactory scanners, Log log) {
        // The real parse reports any lexical errors; don't report them twice.
        Log.DiagnosticHandler discard = log.new DiscardDiagnosticHandler();
        try {
            Scanner scanner = scanners.newScanner(input, false);
            List<Token> tokens = new ArrayList<>();
            for (scanner.nextToken(); scanner.token().kind != TokenKind.EOF; scanner.nextToken()) {
                tokens.add(scanner.token());
            }
            return tokens;
        } finally {
            log.popDiagnosticHandler(discard);
        }
    }

    private static boolean isName(Token t, String name) {
        return t.kind == TokenKind.IDENTIFIER && t.name().contentEquals(name);
    }
}
