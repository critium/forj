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

    private SourceRewriter() {}

    static Result rewrite(CharSequence input, ScannerFactory scanners, Log log) {
        String text = input.toString();
        if (!text.contains("<-") && !text.contains(FORJ)) {
            return null;
        }
        List<Token> tokens = tokenize(text, scanners, log);
        List<Edit> edits = new ArrayList<>();
        findForjBlocks(tokens, edits);
        findArrows(tokens, edits);
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
