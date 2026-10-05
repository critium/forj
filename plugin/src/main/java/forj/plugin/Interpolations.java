package forj.plugin;

import com.sun.tools.javac.parser.Tokens.Token;
import com.sun.tools.javac.parser.Tokens.TokenKind;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scala string interpolation: {@code s"..."}, {@code f"..."} and {@code raw"..."}.
 *
 * <pre>{@code
 * s"Hi $name, you owe ${total / 100}"   ->  ("Hi " + (name) + ", you owe " + (total / 100) + "")
 * f"$item%-10s $price%8.2f"             ->  java.lang.String.format("%-10s %8.2f", (item), (price))
 * raw"C:\temp\$file"                    ->  ("C:\\temp\\" + (file) + "")
 * }</pre>
 *
 * Only the text around each interpolated expression is replaced; the expressions themselves
 * stay where they were, so errors inside {@code ${...}} point at the right column.
 * {@code $$} is a literal {@code $}. In {@code f} strings a value without a format spec gets
 * {@code %s}, and a literal percent is written {@code %%}, as in Scala.
 */
final class Interpolations {

    private static final Pattern PREFIX = Pattern.compile("(?<![\\w$])(s|f|raw)\"");
    private static final Pattern FORMAT_SPEC = Pattern.compile("%[-#+ 0,(<]*\\d*(\\.\\d+)?([tT][a-zA-Z]|[a-zA-Z%])");

    /** One interpolated string literal: [start, end) from the interpolator name to the closing quote. */
    record Literal(int start, int end, List<Edit> edits) {}

    /** Replace [start, end) of the original text with {@code text}. */
    record Edit(int start, int end, String text) {}

    private Interpolations() {}

    static boolean mayContain(String text) {
        return PREFIX.matcher(text).find();
    }

    /**
     * Finds interpolated strings. Tokens locate them (so comments and ordinary strings are
     * skipped), but each one's extent comes from scanning the text: javac's tokenizer would end
     * the literal at the first quote inside {@code ${...}}.
     */
    static List<Literal> find(String text, List<Token> tokens, Log log) {
        List<Literal> found = new ArrayList<>();
        int resumeAt = 0;
        for (Token name : tokens) {
            // the quote is checked in the text: javac may not see a valid string token there
            // (e.g. raw"C:\$x" has what javac considers an illegal escape)
            int quote = name.endPos;
            if (name.pos < resumeAt || name.kind != TokenKind.IDENTIFIER || quote >= text.length()
                    || text.charAt(quote) != '"' || text.startsWith("\"\"\"", quote)) {
                continue;
            }
            String kind = name.name().toString();
            if (!kind.equals("s") && !kind.equals("f") && !kind.equals("raw")) {
                continue;
            }
            Literal literal = parse(text, name.pos, quote, kind, log);
            if (literal != null) {
                found.add(literal);
                resumeAt = literal.end();
            }
        }
        return found;
    }

    /** The text with each interpolated string replaced by a same-length identifier, for re-tokenizing. */
    static String mask(String text, List<Literal> literals) {
        char[] chars = text.toCharArray();
        for (Literal l : literals) {
            for (int i = l.start(); i < l.end(); i++) {
                chars[i] = 'z';
            }
        }
        return new String(chars);
    }

    // ------------------------------------------------------------------ parsing

    private static Literal parse(String text, int start, int quote, String kind, Log log) {
        List<String> parts = new ArrayList<>();          // literal text between expressions
        List<int[]> exprs = new ArrayList<>();           // [start, end) of each expression
        List<String> specs = new ArrayList<>();          // f only: format spec per expression
        StringBuilder part = new StringBuilder();
        int i = quote + 1;
        while (true) {
            if (i >= text.length() || text.charAt(i) == '\n' || text.charAt(i) == '\r') {
                return null; // unterminated: let javac report it
            }
            char c = text.charAt(i);
            if (c == '"') {
                parts.add(part.toString());
                break;
            }
            if (c == '\\' && i + 1 < text.length()) {
                if (text.charAt(i + 1) == '$') {
                    // a backslash never escapes $ (as in Scala): literal in raw, an error otherwise
                    if (!kind.equals("raw")) {
                        return invalid(text, start, i, "write $$ for a literal $, not \\$", log);
                    }
                    part.append(c);
                    i++;
                    continue;
                }
                part.append(c).append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c != '$') {
                part.append(c);
                i++;
                continue;
            }
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            int exprStart;
            int exprEnd;
            if (next == '$') {
                part.append('$');
                i += 2;
                continue;
            } else if (next == '{') {
                exprStart = i + 2;
                exprEnd = closingBrace(text, exprStart);
                if (exprEnd < 0) {
                    return invalid(text, start, i, "unclosed ${ in interpolated string", log);
                }
                if (text.substring(exprStart, exprEnd).isBlank()) {
                    return invalid(text, start, i, "empty ${} in interpolated string", log);
                }
                i = exprEnd + 1;
            } else if (Character.isJavaIdentifierStart(next) && next != '$') {
                exprStart = i + 1;
                exprEnd = exprStart + 1;
                while (exprEnd < text.length() && Character.isJavaIdentifierPart(text.charAt(exprEnd))
                        && text.charAt(exprEnd) != '$') {
                    exprEnd++;
                }
                i = exprEnd;
            } else {
                return invalid(text, start, i, "$ must be followed by a name, {expression} or $", log);
            }
            parts.add(part.toString());
            part.setLength(0);
            exprs.add(new int[] {exprStart, exprEnd});
            if (kind.equals("f")) {
                Matcher spec = FORMAT_SPEC.matcher(text).region(i, text.length());
                if (spec.lookingAt()) {
                    specs.add(spec.group());
                    i = spec.end();
                } else {
                    specs.add("%s");
                }
            }
        }
        int end = i + 1;
        return new Literal(start, end, edits(start, end, kind, parts, exprs, specs));
    }

    /** End of a {@code ${...}} expression: the matching brace, skipping nested strings and chars. */
    private static int closingBrace(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n', '\r' -> {
                    return -1;
                }
                case '{' -> depth++;
                case '}' -> {
                    if (depth-- == 0) {
                        return i;
                    }
                }
                case '"', '\'' -> {
                    i++;
                    while (i < text.length() && text.charAt(i) != c) {
                        if (text.charAt(i) == '\\') {
                            i++;
                        }
                        if (i < text.length() && (text.charAt(i) == '\n' || text.charAt(i) == '\r')) {
                            return -1;
                        }
                        i++;
                    }
                }
                default -> {}
            }
        }
        return -1;
    }

    private static Literal invalid(String text, int start, int at, String message, Log log) {
        log.error(at, new JCDiagnostic.Error(Set.of(), "compiler", "proc.messager", "forj: " + message));
        int end = text.indexOf('\n', at);
        int quote = text.lastIndexOf('"', end < 0 ? text.length() : end);
        if (quote <= at) {
            return null;
        }
        // keep parsing the rest of the file: stand in an empty string for the broken literal
        return new Literal(start, quote + 1, List.of(new Edit(start, quote + 1, "\"\"")));
    }

    // ------------------------------------------------------------------ output

    private static List<Edit> edits(int start, int end, String kind, List<String> parts, List<int[]> exprs,
                                    List<String> specs) {
        List<Edit> edits = new ArrayList<>();
        int n = exprs.size();
        if (kind.equals("f")) {
            StringBuilder format = new StringBuilder(parts.getFirst());
            for (int k = 0; k < n; k++) {
                format.append(specs.get(k)).append(parts.get(k + 1));
            }
            String call = "java.lang.String.format(\"" + format + "\"";
            if (n == 0) {
                edits.add(new Edit(start, end, call + ")"));
                return edits;
            }
            edits.add(new Edit(start, exprs.getFirst()[0], call + ", ("));
            for (int k = 1; k < n; k++) {
                edits.add(new Edit(exprs.get(k - 1)[1], exprs.get(k)[0], "), ("));
            }
            edits.add(new Edit(exprs.getLast()[1], end, "))"));
            return edits;
        }
        boolean raw = kind.equals("raw");
        if (n == 0) {
            edits.add(new Edit(start, end, quoted(parts.getFirst(), raw)));
            return edits;
        }
        // a leading string literal keeps `+` string concatenation even if the first value is a number
        edits.add(new Edit(start, exprs.getFirst()[0], "(" + quoted(parts.getFirst(), raw) + " + ("));
        for (int k = 1; k < n; k++) {
            edits.add(new Edit(exprs.get(k - 1)[1], exprs.get(k)[0], ") + " + quoted(parts.get(k), raw) + " + ("));
        }
        edits.add(new Edit(exprs.getLast()[1], end, ") + " + quoted(parts.get(n), raw) + ")"));
        return edits;
    }

    /** A Java string literal for a part; raw parts keep their backslashes. */
    private static String quoted(String part, boolean raw) {
        if (!raw) {
            return "\"" + part + "\"";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : part.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
