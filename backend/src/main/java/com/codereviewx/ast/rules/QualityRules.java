package com.codereviewx.ast.rules;

import static com.codereviewx.ast.rules.BaseRule.rule;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.codereviewx.ast.fingerprint.Fingerprints;
import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;

/** Code quality and readability. Thresholds are conventional defaults, not measured limits. */
final class QualityRules {
    private QualityRules() {}

    static final int MAX_FUNCTION_LINES = 60, MAX_COMPLEXITY = 10, MAX_NESTING = 4, MAX_PARAMS = 5, MAX_LINE = 120;
    private static final Pattern TODO = Pattern.compile("\\b(TODO|FIXME|HACK|XXX)\\b");
    private static final Set<String> COMMON_NUMBERS = Set.of("0", "1", "2", "10", "100", "1000", "0.5", "0.0", "1.0");
    private static final Set<String> COMPARISONS = Set.of("<", "<=", ">", ">=", "==", "!=", "===", "!==");

    static List<Rule> all() {
        return List.of(LONG_FUNCTION, CYCLOMATIC, DEEP_NESTING, TOO_MANY_PARAMS, MAGIC_NUMBER, DUPLICATE_CODE, DEBUG_PRINT, TODO_COMMENT,
            EMPTY_CATCH, SWALLOWED_EXCEPTION, LONG_LINE, NAMING);
    }

    static final Rule LONG_FUNCTION = rule("quality.long-function", "quality", "low")
        .title("Very long function")
        .explain("This function is over " + MAX_FUNCTION_LINES + " lines long.")
        .why("Long functions usually do several jobs, which makes them hard to test, review and reuse.")
        .changes("Extract self-contained steps into well-named helper functions.")
        .benefits("Each piece can be understood and tested on its own.")
        .tradeoffs("More functions to navigate; keep helpers close to their caller.")
        .check((ctx, self) -> {
            for (UNode f : ctx.all(Kind.FUNCTION)) {
                int n = f.endLine() - f.line() + 1;
                if (n > MAX_FUNCTION_LINES) ctx.report(self, f, f, f2 -> {
                    f2.explain = name(f) + " is " + n + " lines long (threshold " + MAX_FUNCTION_LINES + ").";
                    f2.end = f2.line;
                    f2.original = ctx.src().line(f.line());
                });
            }
        });

    static final Rule CYCLOMATIC = rule("quality.cyclomatic-complexity", "quality", "medium")
        .title("High cyclomatic complexity")
        .explain("This function has many independent paths through it.")
        .why("Every decision point doubles the cases to test; above about 10 paths, bugs hide in combinations nobody tried.")
        .changes("Split the function, replace nested conditionals with early returns, or use a lookup table.")
        .benefits("Fewer paths per function, so each is easier to test completely.")
        .tradeoffs("Over-splitting can scatter simple logic; aim for functions that do one thing.")
        .check((ctx, self) -> {
            for (UNode f : ctx.all(Kind.FUNCTION)) {
                int cc = cyclomatic(f);
                if (cc > MAX_COMPLEXITY) ctx.report(self, f, f, x -> {
                    x.explain = name(f) + " has a cyclomatic complexity of " + cc + " (threshold " + MAX_COMPLEXITY + ").";
                    x.end = x.line;
                    x.original = ctx.src().line(f.line());
                });
            }
        });

    /** 1 + decision points (if, loops, non-default cases, catches, ternaries, && / ||) excluding nested functions. */
    static int cyclomatic(UNode fn) {
        int[] n = {1};
        fn.walk(x -> {
            if (x != fn && (x.is(Kind.FUNCTION) || x.is(Kind.LAMBDA))) return;
            if (x.ancestor(Kind.FUNCTION, Kind.LAMBDA) != fn && x != fn) return;
            if (x.is(Kind.IF) || x.is(Kind.LOOP) || x.is(Kind.CATCH) || x.is(Kind.TERNARY)) n[0]++;
            if (x.is(Kind.CASE) && !x.flag("default")) n[0]++;
            if (x.is(Kind.BINARY) && Set.of("&&", "||", "and", "or").contains(x.op())) n[0]++;
        });
        return n[0];
    }

    static final Rule DEEP_NESTING = rule("quality.deep-nesting", "quality", "low")
        .title("Deeply nested code")
        .explain("Control structures are nested more than " + MAX_NESTING + " levels deep here.")
        .why("Each level adds a condition the reader must keep in mind; deep nesting is a common source of logic errors.")
        .changes("Return early for edge cases, or extract the inner block into a function.")
        .benefits("Flatter code with fewer conditions to track at once.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            for (UNode f : ctx.functions()) {
                UNode deepest = null;
                int max = 0;
                for (UNode n : f.find(n -> isControl(n) && ctx.scopeOf(n) == f)) {
                    int d = nesting(n, f);
                    if (d > max) { max = d; deepest = n; }
                }
                if (max > MAX_NESTING) {
                    int depth = max;
                    UNode at = deepest;
                    ctx.report(self, at, at, x -> {
                        x.explain = "This " + at.kind().name().toLowerCase() + " is nested " + depth + " levels deep (threshold " + MAX_NESTING + ").";
                        x.end = x.line;
                        x.original = ctx.src().line(at.line());
                    });
                }
            }
        });

    private static boolean isControl(UNode n) {
        return n.is(Kind.IF) || n.is(Kind.LOOP) || n.is(Kind.SWITCH) || n.is(Kind.TRY) || n.is(Kind.WITH);
    }

    /** Nesting level of control node {@code n} inside {@code fn}; else-if chains count as one level. */
    static int nesting(UNode n, UNode fn) {
        int d = 0;
        for (UNode c = n; c != null && c != fn; c = c.parent()) {
            if (!isControl(c)) continue;
            if (c.is(Kind.IF) && isElseIf(c)) continue;
            d++;
        }
        return d;
    }

    private static boolean isElseIf(UNode i) {
        UNode p = i.parent();
        if (p == null) return false;
        if (i.type().equals("elif_clause")) return true;
        if (p.is(Kind.IF) && p.field("else") == i) return true;
        return p.type().equals("else_clause") && p.parent() != null && p.parent().is(Kind.IF);
    }

    static final Rule TOO_MANY_PARAMS = rule("quality.too-many-parameters", "quality", "low")
        .title("Too many parameters")
        .explain("This function takes more than " + MAX_PARAMS + " parameters.")
        .why("Long parameter lists are easy to call with arguments in the wrong order and usually signal a missing data type.")
        .changes("Group related parameters into an object/struct, or split the function.")
        .benefits("Calls are clearer and harder to get wrong.")
        .tradeoffs("Adds a small type to maintain.")
        .check((ctx, self) -> {
            for (UNode f : ctx.functions()) {
                int n = f.list("params").stream().mapToInt(p -> p.attr("names") == null ? 1 : Integer.parseInt(p.attr("names"))).sum();
                if (n > MAX_PARAMS) ctx.report(self, f, f, x -> {
                    x.explain = name(f) + " takes " + n + " parameters (threshold " + MAX_PARAMS + ").";
                    x.end = x.line;
                    x.original = ctx.src().line(f.line());
                });
            }
        });

    static final Rule MAGIC_NUMBER = rule("quality.magic-number", "quality", "info").max(2)
        .title("Unnamed numeric constant")
        .explain("A literal number is used in a comparison or calculation without a name.")
        .why("Readers can't tell what the number means, and changing it later means finding every copy.")
        .changes("Give the number a descriptive constant name.")
        .benefits("Intent is visible and the value lives in one place.")
        .tradeoffs("None for values that have a meaning; obvious values like 0 and 1 are fine inline.")
        .check((ctx, self) -> {
            for (UNode n : ctx.all(Kind.NUMBER)) {
                String v = n.text().replaceAll("[uUlLfF]+$", "");
                if (COMMON_NUMBERS.contains(v)) continue;
                UNode p = n.parent();
                if (p == null || !p.is(Kind.BINARY) || !(COMPARISONS.contains(p.op()) || Set.of("*", "/", "%").contains(p.op()))) continue;
                UNode owner = n.ancestor(Kind.DECL, Kind.ASSIGN);
                if (owner != null && (owner.flag("const") || owner.flag("final") || "const".equals(owner.attr("declKind"))
                    || owner.name() != null && owner.name().matches("[A-Z][A-Z0-9_]*"))) continue;
                ctx.report(self, n, x -> x.note(ctx.commentPrefix() + "e.g. " + (ctx.lang("python") ? "LIMIT = " + n.text()
                    : ctx.lang("go") ? "const limit = " + n.text() : ctx.lang("java") ? "static final int LIMIT = " + n.text() + ";"
                    : ctx.lang("javascript") ? "const LIMIT = " + n.text() + ";" : "#define LIMIT " + n.text()) + "  (use a name that says what it means)"));
            }
        });

    static final Rule DUPLICATE_CODE = rule("quality.duplicate-code", "quality", "low").max(2)
        .title("Duplicated code block")
        .explain("This block has the same structure as another block in the file (names and literals aside).")
        .why("Duplicated logic must be fixed in every copy; copies drift apart and bugs get fixed in only one of them.")
        .changes("Extract the shared logic into a function and call it from both places.")
        .benefits("One place to read, test and fix.")
        .tradeoffs("If the copies are expected to diverge soon, a little duplication can be the simpler choice.")
        .check((ctx, self) -> {
            for (UNode[] pair : Fingerprints.duplicates(ctx.root(), 25, 3)) {
                ctx.report(self, pair[1], pair[1], x -> {
                    x.explain = "Lines " + pair[1].line() + "–" + pair[1].endLine() + " have the same structure as lines "
                        + pair[0].line() + "–" + pair[0].endLine() + " (identifier names and literal values aside).";
                    x.confidence("medium");
                });
            }
        });

    static final Rule DEBUG_PRINT = rule("quality.console-output", "quality", "info").max(1)
        .title("Console output in logic code")
        .explain("This function writes directly to the console.")
        .why("Output mixed into logic makes the function harder to test and to reuse. Return values and let the caller decide what to print.")
        .changes("Move output to the caller or a logger.")
        .benefits("Testable, reusable function.")
        .tradeoffs("For quick contest or DSA practice this is often fine.")
        .check((ctx, self) -> {
            ctx.root().walk(n -> {
                boolean out = n.is(Kind.CALL) && Set.of("console.log", "System.out.println", "fmt.Println").contains(n.name())
                    || n.is(Kind.CALL) && ctx.lang("python") && "print".equals(n.name()) && n.parent() != null && n.parent().is(Kind.EXPR_STMT)
                    || n.is(Kind.BINARY) && "<<".equals(n.op()) && ctx.lang("cpp") && leftmost(n).text().matches("(std::)?cout");
                if (!out) return;
                if (n.is(Kind.BINARY) && n.parent() != null && n.parent().is(Kind.BINARY) && "<<".equals(n.parent().op())) return;
                UNode fn = n.ancestor(Kind.FUNCTION);
                if (fn != null && "main".equals(fn.name())) return;
                ctx.report(self, n, x -> x.note(ctx.commentPrefix() + "Return the value and print it from the caller (or use a logger with levels)."));
            });
        });

    private static UNode leftmost(UNode b) {
        UNode n = b;
        while (n.is(Kind.BINARY) && n.field("left") != null) n = n.field("left");
        return n;
    }

    static final Rule TODO_COMMENT = rule("quality.todo", "quality", "info").max(2)
        .title("Unresolved TODO")
        .explain("A TODO/FIXME marker is left in the code.")
        .why("TODOs signal known gaps. Here, an edge case is acknowledged but not handled.")
        .changes("Handle the case or create a tracked issue.")
        .benefits("Known gaps don't get forgotten.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            for (UNode c : ctx.all(Kind.COMMENT)) {
                if (!TODO.matcher(c.text()).find()) continue;
                ctx.report(self, c, x -> x.note(ctx.commentPrefix() + "Resolve it now, or track it in an issue and reference the ID."));
            }
        });

    static final Rule EMPTY_CATCH = rule("quality.empty-catch", "quality", "medium").langs("java", "javascript", "cpp")
        .title("Empty catch block")
        .explain("The error is caught and discarded.")
        .why("Failures disappear without a trace, which makes bugs extremely hard to diagnose.")
        .changes("Handle, log or rethrow.")
        .benefits("Failures become observable.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            for (UNode c : ctx.all(Kind.CATCH)) {
                UNode body = c.field("body");
                if (body == null) body = c.children().stream().filter(x -> x.is(Kind.BLOCK)).findFirst().orElse(null);
                if (body == null || body.children().stream().anyMatch(x -> !x.is(Kind.COMMENT))) continue;
                boolean commented = body.children().stream().anyMatch(x -> x.is(Kind.COMMENT))
                    || ctx.all(Kind.COMMENT).stream().anyMatch(cm -> cm.span().start() > c.span().start() && cm.span().end() <= c.span().end());
                ctx.report(self, c, x -> {
                    x.note(ctx.commentPrefix() + "At minimum log the error, or rethrow it wrapped with context.");
                    if (commented) x.confidence("low");
                });
            }
        });

    static final Rule SWALLOWED_EXCEPTION = rule("quality.swallowed-exception", "quality", "medium").langs("python")
        .title("Exception silently ignored")
        .explain("except ...: pass discards the error.")
        .why("Failures disappear without a trace.")
        .changes("Handle or log.")
        .benefits("Failures become observable.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            for (UNode c : ctx.all(Kind.CATCH)) {
                UNode body = c.field("body");
                if (body == null) continue;
                List<UNode> stmts = body.children().stream().filter(x -> !x.is(Kind.COMMENT)).toList();
                if (stmts.size() == 1 && stmts.get(0).type().equals("pass_statement")) {
                    ctx.report(self, c, x -> x.note("# Log the error or handle the specific case explicitly."));
                }
            }
        });

    static final Rule LONG_LINE = rule("readability.long-line", "readability", "low").max(2)
        .title("Very long line")
        .explain("This line is over " + MAX_LINE + " characters.")
        .why("Long lines force horizontal scrolling in reviews and side-by-side diffs and usually hide several steps in one expression.")
        .changes("Break the line at logical boundaries.")
        .benefits("Easier to read and to diff.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            List<String> lines = ctx.src().lines();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).length() <= MAX_LINE) continue;
                int line = i + 1;
                UNode at = deepestStartingOn(ctx.root(), line);
                ctx.report(self, at, at, x -> {
                    x.line = line;
                    x.end = line;
                    x.original = ctx.src().line(line);
                    x.source = "text";
                    x.note(ctx.commentPrefix() + "Split the expression into named intermediate variables.");
                });
            }
        });

    /** The outermost node that starts on {@code line}, else the root (used to anchor text-metric findings). */
    private static UNode deepestStartingOn(UNode root, int line) {
        UNode[] found = {null};
        root.walk(n -> { if (found[0] == null && n != root && n.line() == line) found[0] = n; });
        return found[0] == null ? root : found[0];
    }

    static final Rule NAMING = rule("readability.naming", "readability", "info").max(2)
        .title("Name doesn't follow the language's convention")
        .explain("This function name doesn't follow the usual naming style for the language.")
        .why("Consistent names make code predictable to read; linters and teammates expect the convention.")
        .changes("Rename following the convention (snake_case in Python, camelCase in Java/JavaScript, MixedCaps in Go).")
        .benefits("Code reads like the rest of the ecosystem.")
        .tradeoffs("Renaming public functions breaks callers; do it before the API is published.")
        .check((ctx, self) -> {
            for (UNode f : ctx.all(Kind.FUNCTION)) {
                String n = f.name();
                if (n == null || n.startsWith("__") || n.equals("main")) continue;
                String problem = null;
                if (n.length() == 1) problem = "a single letter";
                else if (ctx.lang("python") && n.matches(".*[a-z][A-Z].*")) problem = "camelCase (Python uses snake_case)";
                else if (ctx.lang("java", "javascript") && n.matches("[a-z]+(_[a-z0-9]+)+") && !n.startsWith("test")) problem = "snake_case (camelCase is conventional)";
                else if (ctx.lang("go") && n.contains("_") && !n.startsWith("Test")) problem = "contains underscores (Go uses MixedCaps)";
                if (problem == null) continue;
                String why = problem;
                ctx.report(self, f, f, x -> {
                    x.explain = "The function name " + n + " is " + why + ".";
                    x.end = x.line;
                    x.original = ctx.src().line(f.line());
                });
            }
        });

    static String name(UNode f) { return f.name() == null ? "This function" : f.name() + "()"; }
}
