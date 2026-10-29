package com.codereviewx.ast.rules;

import static com.codereviewx.ast.rules.BaseRule.rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codereviewx.ast.dataflow.DataFlow;
import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;

final class CorrectnessRules {
    private CorrectnessRules() {}

    static final Set<String> BOUND_NAMES = Set.of("high", "hi", "right", "end", "r");
    static final Set<Kind> STATEMENTS = Set.of(Kind.EXPR_STMT, Kind.RETURN, Kind.BREAK, Kind.CONTINUE, Kind.THROW, Kind.IF,
        Kind.LOOP, Kind.BLOCK, Kind.SWITCH, Kind.TRY, Kind.WITH, Kind.ASSIGN, Kind.DECL, Kind.UNARY);

    static List<Rule> all() {
        return List.of(UNDEFINED_NAME, MISSING_RETURN, MUTABLE_DEFAULT, BARE_EXCEPT, LOOSE_EQUALITY, STRING_REF_EQUALITY, ASSIGN_IN_CONDITION, MIDPOINT_OVERFLOW,
            LOOP_PAST_END, INCLUSIVE_BOUND, IGNORED_ERROR, SIGNED_UNSIGNED, MISSING_BREAK, UNREACHABLE, UNCHECKED_ALLOC,
            USE_AFTER_FREE, DOUBLE_FREE, NULL_DEREFERENCE);
    }

    static final Set<String> PY_BUILTINS = Set.of("abs", "aiter", "all", "anext", "any", "ascii", "bin", "bool", "breakpoint",
        "bytearray", "bytes", "callable", "chr", "classmethod", "compile", "complex", "copyright", "credits", "delattr", "dict", "dir",
        "divmod", "enumerate", "eval", "exec", "exit", "filter", "float", "format", "frozenset", "getattr", "globals", "hasattr", "hash",
        "help", "hex", "id", "input", "int", "isinstance", "issubclass", "iter", "len", "license", "list", "locals", "map", "max",
        "memoryview", "min", "next", "object", "oct", "open", "ord", "pow", "print", "property", "quit", "range", "repr", "reversed",
        "round", "set", "setattr", "slice", "sorted", "staticmethod", "str", "sum", "super", "tuple", "type", "vars", "zip",
        "__import__", "__name__", "__file__", "__doc__", "__builtins__", "__spec__", "__package__", "__debug__", "__loader__",
        "NotImplemented", "Ellipsis", "BaseException", "BaseExceptionGroup", "Exception", "ExceptionGroup", "ArithmeticError",
        "AssertionError", "AttributeError", "BlockingIOError", "BrokenPipeError", "BufferError", "ChildProcessError",
        "ConnectionAbortedError", "ConnectionError", "ConnectionRefusedError", "ConnectionResetError", "EOFError", "EncodingWarning",
        "EnvironmentError", "FileExistsError", "FileNotFoundError", "FloatingPointError", "GeneratorExit", "IOError", "ImportError",
        "IndentationError", "IndexError", "InterruptedError", "IsADirectoryError", "KeyError", "KeyboardInterrupt", "LookupError",
        "MemoryError", "ModuleNotFoundError", "NameError", "NotADirectoryError", "NotImplementedError", "OSError", "OverflowError",
        "PermissionError", "ProcessLookupError", "RecursionError", "ReferenceError", "RuntimeError", "StopAsyncIteration",
        "StopIteration", "SyntaxError", "SystemError", "SystemExit", "TabError", "TimeoutError", "TypeError", "UnboundLocalError",
        "UnicodeDecodeError", "UnicodeEncodeError", "UnicodeError", "UnicodeTranslateError", "ValueError", "ZeroDivisionError",
        "Warning", "UserWarning", "DeprecationWarning", "PendingDeprecationWarning", "RuntimeWarning", "SyntaxWarning",
        "FutureWarning", "ImportWarning", "UnicodeWarning", "BytesWarning", "ResourceWarning");

    static final Rule UNDEFINED_NAME = rule("correctness.undefined-name", "correctness", "high").langs("python").max(3)
        .title("Name is never defined")
        .explain("This name is read, but nothing in the file defines it: no assignment, parameter, import, function or class, and it isn't a Python built-in.")
        .why("Python looks names up when the line runs, so this line raises NameError and the program stops there.")
        .changes("Define the name before using it, import it, or fix the spelling. If you meant literal text, put it in quotes.")
        .benefits("The line runs instead of crashing.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            UNode root = ctx.root();
            if (root.any(n -> n.type().equals("wildcard_import"))) return; // `from m import *` can define anything
            java.util.Map<UNode, Set<String>> defs = new java.util.IdentityHashMap<>();
            Set<UNode> binding = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            root.walk(n -> {
                UNode scope = ctx.scopeOf(n);
                List<UNode> bound = new ArrayList<>();
                switch (n.type()) {
                    case "aliased_import" -> { if (n.field("alias") != null) bound.add(n.field("alias")); }
                    case "dotted_name" -> {
                        UNode p = n.parent();
                        boolean imported = p != null && (p.type().equals("import_statement")
                            || p.type().equals("import_from_statement") && n != p.field("module_name"));
                        if (imported && !n.children().isEmpty()) bound.add(n.children().get(0));
                    }
                    case "as_pattern_target", "global_statement", "nonlocal_statement" -> bound.addAll(n.find(Kind.IDENT));
                    case "named_expression" -> { if (n.field("name") != null) bound.add(n.field("name")); }
                    case "for_in_clause" -> { if (n.field("left") != null) bound.addAll(targets(n.field("left"))); }
                    default -> { }
                }
                if (n.is(Kind.ASSIGN) && n.field("target") != null) bound.addAll(targets(n.field("target")));
                if (n.is(Kind.LOOP) && n.field("var") != null) bound.addAll(targets(n.field("var")));
                if ((n.is(Kind.FUNCTION) || n.is(Kind.CLASS)) && n.name() != null) {
                    defs.computeIfAbsent(scope, k -> new java.util.HashSet<>()).add(n.name());
                    if (n.field("name") != null) binding.add(n.field("name"));
                }
                if (n.is(Kind.PARAM) && n.name() != null) {
                    defs.computeIfAbsent(scope, k -> new java.util.HashSet<>()).add(n.name());
                    for (UNode id : n.find(Kind.IDENT)) if (n.field("default") == null || !id.isWithin(n.field("default"))) binding.add(id);
                }
                // Names bound by an import or `global` belong to the module scope.
                UNode target = n.type().equals("global_statement") || n.ancestor(Kind.IMPORT) != null || n.is(Kind.IMPORT) ? root : scope;
                for (UNode id : bound) {
                    binding.add(id);
                    defs.computeIfAbsent(target, k -> new java.util.HashSet<>()).add(id.name());
                }
            });
            boolean dynamic = root.any(n -> n.is(Kind.CALL) && Set.of("exec", "globals", "locals", "setattr", "vars").contains(n.name()));
            for (UNode id : ctx.all(Kind.IDENT)) {
                String name = id.name();
                if (name == null || binding.contains(id) || PY_BUILTINS.contains(name) || !isRead(id)) continue;
                boolean visible = false;
                for (UNode s = ctx.scopeOf(id); s != null && !visible; s = s.parent() == null ? null : ctx.scopeOf(s)) {
                    visible = defs.getOrDefault(s, Set.of()).contains(name);
                    if (s == root) break;
                }
                if (visible) continue;
                boolean quoted = ctx.all(Kind.STRING).stream().anyMatch(s -> name.equals(s.attr("value")));
                ctx.report(self, id, f -> {
                    f.title = "Name '" + name + "' is never defined";
                    f.explain = "'" + name + "' is read here, but nothing in the file defines it: no assignment, parameter, import, function or class, and it isn't a Python built-in.";
                    if (quoted || id.parent() != null && id.parent().type().equals("argument_list")) {
                        ctx.applyEdits(f, List.of(Edit.replace(id, "\"" + name + "\"")));
                        f.assumptions = "The suggestion assumes you meant the text \"" + name + "\". If it is a variable, define or import it instead.";
                        if (!quoted) f.confidence("medium");
                    }
                    if (dynamic) f.confidence("low");
                });
            }
        });

    private static final java.util.regex.Pattern VALUE_NAME = java.util.regex.Pattern.compile(
        "(?i)^(find|get|compute|calc|calculate|count|sum|total|average|avg|max|min|build|make|create|parse|collect|search|merge|"
            + "filter|generate|to)([_A-Z].*)?$|^(is|has|can|should)(_.+|[A-Z].*)$");
    private static final Set<String> MUTATORS = Set.of("append", "extend", "insert", "add", "update", "push", "unshift", "set", "put");
    private static final Set<String> READ_ONLY_BUILTINS = Set.of("len", "max", "min", "sum", "sorted", "print", "any", "all", "str",
        "list", "tuple", "set", "dict", "enumerate", "zip", "reversed", "repr", "bool", "console.log", "JSON.stringify");

    static final Rule MISSING_RETURN = rule("correctness.missing-return", "correctness", "high").langs("python", "javascript")
        .title("Result is computed but never returned")
        .explain("The function builds up a value but has no return statement, so callers always get None.")
        .why("Everything the function computes is thrown away when it ends. Code that uses the result receives None (undefined in JavaScript) and fails later, far from the real cause.")
        .changes("Return the value the function builds.")
        .benefits("Callers receive the computed result.")
        .tradeoffs("If the function is only meant to print or modify its arguments, rename it so the name doesn't promise a result.")
        .check((ctx, self) -> {
            for (UNode fn : ctx.all(Kind.FUNCTION)) {
                String name = fn.name();
                UNode body = fn.field("body");
                if (name == null || body == null || name.startsWith("__") || name.equals("main") || name.startsWith("test")
                    || name.equals("constructor")) continue;
                boolean returnsValue = fn.any(n -> n.is(Kind.RETURN) && ctx.scopeOf(n) == fn && n.children().stream().anyMatch(c -> !c.is(Kind.COMMENT)));
                boolean generator = fn.any(n -> n.type().startsWith("yield") && ctx.scopeOf(n) == fn);
                List<UNode> stmts = body.children().stream().filter(c -> !c.is(Kind.COMMENT)).toList();
                boolean stub = stmts.isEmpty() || stmts.size() == 1 && (stmts.get(0).type().equals("pass_statement") || stmts.get(0).is(Kind.THROW)
                    || stmts.get(0).is(Kind.EXPR_STMT) && stmts.get(0).children().stream().anyMatch(c -> c.is(Kind.STRING) || c.type().equals("ellipsis")));
                if (returnsValue || generator || stub || fn.ancestor(Kind.CLASS) != null && !VALUE_NAME.matcher(name).matches()) continue;
                String acc = accumulator(ctx, fn);
                boolean promises = VALUE_NAME.matcher(name).matches();
                if (acc == null && !promises) continue;
                UNode last = stmts.get(stmts.size() - 1);
                String indent = " ".repeat(Math.max(0, last.span().startCol() - 1));
                String end = ctx.lang("javascript") ? ";" : "";
                ctx.report(self, fn, fn, f -> {
                    f.end = f.line;
                    f.original = ctx.src().line(fn.line());
                    if (acc != null) {
                        f.title = "'" + acc + "' is built but never returned";
                        f.explain = name + "() fills '" + acc + "' but has no return statement, so every call returns "
                            + (ctx.lang("python") ? "None" : "undefined") + " and the result is lost.";
                        // Show the function's last lines with the missing return added.
                        int from = Math.max(fn.line(), last.line() - 3);
                        f.line = from;
                        f.original = ctx.src().lines(from, last.endLine());
                        f.end = last.endLine();
                        f.suggestLine(f.original + "\n" + indent + "return " + acc + end);
                        if (!promises) f.confidence("medium");
                    } else {
                        f.title = name + "() never returns a value";
                        f.explain = "The name " + name + "() suggests it produces a result, but it has no return statement, so every call returns "
                            + (ctx.lang("python") ? "None" : "undefined") + ".";
                        f.note(ctx.commentPrefix() + "Return the result at the end of " + name + "(), or rename it if it only prints or changes state.");
                        f.confidence("medium");
                    }
                });
            }
        });

    /** A local container/total the function fills and never hands out (caller-owned parameters excluded). */
    private static String accumulator(RuleContext ctx, UNode fn) {
        for (UNode n : fn.find(n -> ctx.scopeOf(n) == fn && (n.is(Kind.CALL) || n.is(Kind.ASSIGN)))) {
            String v = null;
            if (n.is(Kind.CALL) && MUTATORS.contains(n.attr("method")) && n.field("receiver") != null && n.field("receiver").is(Kind.IDENT)) {
                v = n.field("receiver").name();
            } else if (n.is(Kind.ASSIGN) && Set.of("+=", "-=", "*=", "|=").contains(n.op()) && n.field("target") != null && n.field("target").is(Kind.IDENT)) {
                v = n.field("target").name();
            }
            if (v == null || !isLocalAccumulator(ctx, fn, v)) continue;
            String name = v;
            boolean escapes = com.codereviewx.ast.dataflow.DataFlow.uses(name, fn).stream().anyMatch(u -> {
                UNode p = u.parent();
                if (p == null) return false;
                if (p.is(Kind.ASSIGN) && p.field("value") != null && u.isWithin(p.field("value"))) return true; // aliased
                UNode call = u.ancestor(Kind.CALL);
                return call != null && call.list("args").stream().anyMatch(a -> a == u) && !READ_ONLY_BUILTINS.contains(call.name());
            });
            if (!escapes) return name;
        }
        return null;
    }

    private static boolean isLocalAccumulator(RuleContext ctx, UNode fn, String v) {
        for (UNode p : fn.list("params")) {
            if (!v.equals(p.name())) continue;
            UNode d = p.field("default");
            return d != null && (d.is(Kind.LIST) || d.is(Kind.MAP) || d.is(Kind.SET)); // only a shared mutable default
        }
        for (UNode d : com.codereviewx.ast.dataflow.DataFlow.defs(v, fn)) {
            UNode val = d.field("value");
            if (val != null && (val.is(Kind.LIST) || val.is(Kind.MAP) || val.is(Kind.SET) || val.is(Kind.NUMBER) || val.is(Kind.STRING)
                || val.is(Kind.NEW) || val.is(Kind.CALL) && Set.of("list", "dict", "set", "defaultdict", "Counter", "deque").contains(val.name()))) return true;
        }
        return false;
    }

    /** Identifiers bound by an assignment/loop target (a, (b, c), [d, *e]); subscripts and attributes bind nothing new. */
    private static List<UNode> targets(UNode t) {
        List<UNode> out = new ArrayList<>();
        if (t.is(Kind.IDENT)) out.add(t);
        else if (!t.is(Kind.MEMBER) && !t.is(Kind.INDEX)) for (UNode c : t.children()) out.addAll(targets(c));
        return out;
    }

    /** False for identifiers that are not variable reads: attribute names, keyword-argument names, import paths. */
    private static boolean isRead(UNode id) {
        UNode p = id.parent();
        if (p == null) return true;
        if (p.is(Kind.MEMBER) && p.field("object") != id) return false;
        if (p.type().equals("keyword_argument") && p.field("name") == id) return false;
        if (id.ancestor(Kind.IMPORT) != null || p.type().equals("dotted_name")) return false;
        return !p.type().equals("global_statement") && !p.type().equals("nonlocal_statement");
    }

    static final Rule MUTABLE_DEFAULT = rule("correctness.mutable-default", "correctness", "high").langs("python")
        .title("Mutable default argument")
        .explain("The default list/dict is created once, when the function is defined, not on every call.")
        .why("Values appended in one call are still there in the next call, so results leak between calls. This bug is easy to miss in tests that call the function once.")
        .changes("Default to None and create a fresh object inside the function.")
        .benefits("Every call starts with its own empty container.")
        .tradeoffs("One extra line. Callers who relied on the shared state (rare and usually accidental) will see different behaviour.")
        .check((ctx, self) -> {
            for (UNode p : ctx.all(Kind.PARAM)) {
                UNode d = p.field("default");
                if (d == null) continue;
                boolean mutable = d.is(Kind.LIST) || d.is(Kind.MAP) || d.is(Kind.SET)
                    || d.is(Kind.CALL) && Set.of("list", "dict", "set", "defaultdict", "deque").contains(d.name());
                if (!mutable) continue;
                UNode fn = p.ancestor(Kind.FUNCTION, Kind.LAMBDA);
                String indent = fn == null ? "" : " ".repeat(Math.max(0, fn.span().startCol() - 1));
                ctx.report(self, p, f -> {
                    ctx.applyEdits(f, List.of(Edit.replace(d, "None")));
                    f.suggested += "\n" + indent + "    if " + p.name() + " is None:\n" + indent + "        " + p.name() + " = " + d.text();
                });
            }
        });

    static final Rule BARE_EXCEPT = rule("correctness.bare-except", "correctness", "medium").langs("python")
        .title("Bare except clause")
        .explain("except: with no exception type catches everything, including KeyboardInterrupt and SystemExit.")
        .why("Real bugs (typos, wrong types) get swallowed, so the program carries on with bad state and the root cause is hidden.")
        .changes("Catch the specific exception the block is meant to handle.")
        .benefits("Unexpected errors surface instead of being hidden.")
        .tradeoffs("You must know which exception to expect. Check the docs of the call inside try.")
        .check((ctx, self) -> {
            for (UNode c : ctx.all(Kind.CATCH)) {
                if (c.attr("type") != null) continue;
                int s = c.span().start();
                int colon = ctx.src().text().indexOf(':', s);
                ctx.report(self, c, c, f -> {
                    ctx.applyEdits(f, List.of(new Edit(s + 6, s + 6, " ValueError"), new Edit(colon + 1, colon + 1, "  # catch only what you expect")));
                    f.end = f.line;
                    f.original = ctx.src().line(f.line);
                });
            }
        });

    static final Rule LOOSE_EQUALITY = rule("correctness.loose-equality", "correctness", "medium").langs("javascript").max(2)
        .title("Loose equality (== / !=)")
        .explain("== converts types before comparing, so '1' == 1 and 0 == '' are both true.")
        .why("Implicit coercion causes comparisons that pass for the wrong reasons, especially with user input, which is always a string.")
        .changes("Use strict equality, which compares type and value.")
        .benefits("Comparisons mean exactly what they say.")
        .tradeoffs("If you relied on coercion, convert explicitly (Number(x) === 1). x == null (null or undefined) is a common deliberate exception.")
        .check((ctx, self) -> {
            Map<Integer, List<UNode>> byLine = new LinkedHashMap<>();
            for (UNode b : ctx.all(Kind.BINARY)) if ("==".equals(b.op()) || "!=".equals(b.op())) byLine.computeIfAbsent(b.line(), k -> new ArrayList<>()).add(b);
            for (List<UNode> group : byLine.values()) {
                List<Edit> edits = new ArrayList<>();
                for (UNode b : group) {
                    Edit e = operatorEdit(ctx, b, b.op(), b.op() + "=");
                    if (e != null) edits.add(e);
                }
                UNode first = group.get(0);
                boolean nullIdiom = group.stream().allMatch(b -> b.field("right") != null && b.field("right").is(Kind.NULL));
                ctx.report(self, first, f -> {
                    if (!edits.isEmpty()) ctx.applyEdits(f, edits);
                    if (nullIdiom) f.confidence("low");
                });
            }
        });

    static final Rule STRING_REF_EQUALITY = rule("correctness.string-reference-equality", "correctness", "high").langs("java")
        .title("String compared with ==")
        .explain("In Java, == on objects compares references, not contents.")
        .why("It may pass in a quick test (string literals are interned) and then fail for strings built at runtime, such as user input.")
        .changes("Use \"literal\".equals(var), which also avoids a NullPointerException when var is null.")
        .benefits("Compares contents, which is what you meant.")
        .tradeoffs("For case-insensitive checks use equalsIgnoreCase.")
        .check((ctx, self) -> {
            for (UNode b : ctx.all(Kind.BINARY)) {
                if (!"==".equals(b.op()) && !"!=".equals(b.op())) continue;
                UNode l = b.field("left"), r = b.field("right");
                if (l == null || r == null) continue;
                if (l.is(Kind.NULL) || r.is(Kind.NULL)) continue;
                boolean literalSide = l.is(Kind.STRING) && !l.flag("char") || r.is(Kind.STRING) && !r.flag("char");
                if (!literalSide && !(isString(ctx, l, b) && isString(ctx, r, b))) continue;
                String neg = "!=".equals(b.op()) ? "!" : "";
                String fix = r.is(Kind.STRING) ? neg + r.text() + ".equals(" + l.text() + ")"
                    : l.is(Kind.STRING) ? neg + l.text() + ".equals(" + r.text() + ")"
                    : neg + "Objects.equals(" + l.text() + ", " + r.text() + ")";
                boolean literal = l.is(Kind.STRING) || r.is(Kind.STRING);
                ctx.report(self, b, f -> {
                    ctx.applyEdits(f, List.of(Edit.replace(b, fix)));
                    if (!literal) f.confidence("medium");
                });
            }
        });

    private static boolean isString(RuleContext ctx, UNode e, UNode at) {
        if (e.is(Kind.STRING)) return !e.flag("char");
        if (e.is(Kind.IDENT)) return "String".equals(ctx.typeOf(e.name(), at));
        if (e.is(Kind.CALL)) return Set.of("toString", "substring", "trim", "toLowerCase", "toUpperCase", "nextLine", "next").contains(e.attr("method"));
        return false;
    }

    static final Rule ASSIGN_IN_CONDITION = rule("correctness.assignment-in-condition", "correctness", "high")
        .langs("c", "cpp", "java", "javascript")
        .title("Assignment inside an if condition")
        .explain("A single = assigns. The condition is then the assigned value, not a comparison.")
        .why("This is almost always a typo for ==, and the branch runs whenever the assigned value is non-zero.")
        .changes("Compare with == (or === in JavaScript).")
        .benefits("The branch tests what it reads as.")
        .tradeoffs("If the assignment was intentional, wrap it in an extra pair of parentheses or move it to its own line.")
        .check((ctx, self) -> {
            for (UNode i : ctx.all(Kind.IF)) {
                UNode c = i.field("cond");
                if (c == null || !c.is(Kind.ASSIGN) || !"=".equals(c.op())) continue;
                String eq = ctx.lang("javascript") ? "===" : "==";
                ctx.report(self, c, f -> {
                    Edit e = operatorEdit(ctx, c, "=", eq);
                    if (e != null) ctx.applyEdits(f, List.of(e));
                });
            }
        });

    static final Rule MIDPOINT_OVERFLOW = rule("correctness.midpoint-overflow", "correctness", "medium").langs("c", "cpp", "java", "go")
        .title("Midpoint can overflow")
        .explain("(low + high) / 2 adds two indices before dividing.")
        .why("For large arrays the sum can exceed the integer range and become negative, giving an invalid index. This bug sat in the JDK's binary search for nine years.")
        .changes("Compute low + (high - low) / 2, which never exceeds high.")
        .benefits("Safe for any valid index range.")
        .tradeoffs("None. It's the same number of operations.")
        .check((ctx, self) -> {
            for (UNode b : ctx.all(Kind.BINARY)) {
                boolean half = "/".equals(b.op()) && isNumber(b.field("right"), "2") || ">>".equals(b.op()) && isNumber(b.field("right"), "1");
                if (!half || b.field("left") == null) continue;
                UNode sum = b.field("left").unparen();
                if (!sum.is(Kind.BINARY) || !"+".equals(sum.op())) continue;
                UNode a = sum.field("left"), c = sum.field("right");
                if (a == null || c == null || !a.is(Kind.IDENT) || !c.is(Kind.IDENT)) continue;
                ctx.report(self, b, f -> ctx.applyEdits(f, List.of(Edit.replace(b, a.text() + " + (" + c.text() + " - " + a.text() + ") / 2"))));
            }
        });

    static final Rule LOOP_PAST_END = rule("correctness.loop-past-end", "correctness", "high").langs("c", "cpp", "java", "javascript", "go")
        .title("Loop runs one index past the end")
        .explain("i <= length visits index length, which doesn't exist.")
        .why("Out-of-bounds read: an exception in Java/JS, undefined behaviour in C/C++, a panic in Go.")
        .changes("Use < instead of <=.")
        .benefits("Stays within bounds.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            for (UNode l : ctx.all(Kind.LOOP)) {
                UNode c = l.field("cond");
                if (c == null || !c.is(Kind.BINARY) || !"<=".equals(c.op())) continue;
                UNode i = c.field("left"), bound = c.field("right");
                if (i == null || bound == null || !i.is(Kind.IDENT) || lengthOf(bound) == null) continue;
                String coll = lengthOf(bound);
                boolean indexes = l.field("body") != null && l.field("body").any(n -> n.is(Kind.INDEX) && n.field("index") != null
                    && n.field("index").is(Kind.IDENT) && i.name().equals(n.field("index").name()));
                ctx.report(self, c, f -> {
                    Edit e = operatorEdit(ctx, c, "<=", "<");
                    if (e != null) ctx.applyEdits(f, List.of(e));
                    if (!indexes) {
                        f.confidence("low");
                        f.assumptions = "The loop variable is not used to index " + coll + " here, so check whether <= was intended.";
                    }
                });
            }
        });

    static final Rule INCLUSIVE_BOUND = rule("correctness.inclusive-bound-at-length", "correctness", "high")
        .langs("c", "cpp", "java", "javascript", "go")
        .title("Inclusive search bound starts at length")
        .explain("The upper bound is set to the array length, but the loop uses <=, which treats it as inclusive.")
        .why("When the target is larger than every element, the search reads index length, which is past the end (ArrayIndexOutOfBounds, or undefined behaviour in C/C++).")
        .changes("Start the inclusive bound at length - 1.")
        .benefits("Every probed index is valid.")
        .tradeoffs("Alternatively keep length and switch to a half-open loop (low < high). Pick one convention and keep it.")
        .check((ctx, self) -> {
            List<UNode> defs = new ArrayList<>();
            for (UNode d : ctx.all(Kind.DECL)) if (d.name() != null && BOUND_NAMES.contains(d.name()) && d.field("value") != null) defs.add(d);
            for (UNode a : ctx.all(Kind.ASSIGN)) {
                UNode t = a.field("target");
                if (t != null && t.is(Kind.IDENT) && BOUND_NAMES.contains(t.name()) && a.field("value") != null) defs.add(a);
            }
            for (UNode d : defs) {
                UNode v = d.field("value");
                if (lengthOf(v) == null) continue;
                String name = d.is(Kind.DECL) ? d.name() : d.field("target").name();
                UNode scope = ctx.scopeOf(d);
                boolean inclusive = scope.any(n -> n.is(Kind.LOOP) && n.field("cond") != null && n.field("cond").any(
                    b -> b.is(Kind.BINARY) && "<=".equals(b.op()) && b.any(x -> x.is(Kind.IDENT) && name.equals(x.name()))));
                if (!inclusive) continue;
                ctx.report(self, d, f -> ctx.applyEdits(f, List.of(new Edit(v.span().end(), v.span().end(), " - 1"))));
            }
        });

    static final Rule IGNORED_ERROR = rule("correctness.ignored-error", "correctness", "medium").langs("go")
        .title("Error value discarded")
        .explain("The function's error return is assigned to _ and ignored.")
        .why("On failure (for example strconv.Atoi(\"x\")) the other result is a zero value, and the program continues as if it succeeded.")
        .changes("Capture err and handle it, by returning it, logging it, or skipping the bad value deliberately.")
        .benefits("Failures become visible and testable.")
        .tradeoffs("The function signature may need an extra error return value.")
        .check((ctx, self) -> {
            for (UNode a : ctx.all(Kind.ASSIGN)) {
                List<UNode> ts = a.list("targets");
                if (ts.size() < 2 || a.list("values").size() != 1 || !a.list("values").get(0).is(Kind.CALL)) continue;
                UNode last = ts.get(ts.size() - 1);
                if (!"_".equals(last.text())) continue;
                String indent = " ".repeat(Math.max(0, a.span().startCol() - 1));
                ctx.report(self, a, f -> {
                    ctx.applyEdits(f, List.of(Edit.replace(last, "err")));
                    f.suggested += "\n" + indent + "if err != nil {\n" + indent + "    // handle it: return it (wrapped with context), log it, or skip the value deliberately\n" + indent + "}";
                });
            }
        });

    static final Rule SIGNED_UNSIGNED = rule("correctness.signed-unsigned-comparison", "correctness", "low").langs("cpp").max(1)
        .title("Signed/unsigned comparison")
        .explain("int is compared with size(), which returns an unsigned size_t.")
        .why("Mixed comparisons convert the int to unsigned. A negative value becomes huge, and compilers warn (-Wsign-compare).")
        .changes("Use size_t (or a range-based for loop).")
        .benefits("No implicit conversion and no warning.")
        .tradeoffs("size_t can't go negative. Count-down loops need care.")
        .check((ctx, self) -> {
            for (UNode l : ctx.all(Kind.LOOP)) {
                UNode init = l.field("init"), c = l.field("cond");
                if (init == null || c == null || !c.is(Kind.BINARY) || !Set.of("<", "<=").contains(c.op())) continue;
                UNode type = init.field("type");
                UNode r = c.field("right");
                if (type == null || !"int".equals(type.text()) || r == null || !r.is(Kind.CALL) || !Set.of("size", "length").contains(r.attr("method"))) continue;
                ctx.report(self, l, c, f -> ctx.applyEdits(f, List.of(Edit.replace(type, "size_t"))));
            }
        });

    static final Rule MISSING_BREAK = rule("correctness.missing-break", "correctness", "medium").langs("c", "cpp", "java", "javascript")
        .title("Switch case falls through")
        .explain("This case runs its statements and then continues into the next case, because it doesn't end with break, return or throw.")
        .why("Accidental fall-through executes the next case's code too, which is a common source of wrong results.")
        .changes("End the case with break (or return/throw). If falling through is intended, say so with a // fallthrough comment.")
        .benefits("Each case does only its own work.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            for (UNode sw : ctx.all(Kind.SWITCH)) {
                List<UNode> cases = sw.find(n -> n.is(Kind.CASE) && n.ancestor(Kind.SWITCH) == sw);
                cases.sort(Comparator.comparingInt(n -> n.span().start()));
                for (int i = 0; i < cases.size() - 1; i++) {
                    UNode cs = cases.get(i);
                    if (cs.flag("arrow")) continue;
                    List<UNode> stmts = statements(cs);
                    if (stmts.isEmpty() || terminates(stmts.get(stmts.size() - 1))) continue;
                    boolean commented = cs.any(n -> n.is(Kind.COMMENT) && n.text().toLowerCase().contains("fall"))
                        || ctx.all(Kind.COMMENT).stream().anyMatch(cm -> cm.span().start() >= cs.span().start()
                            && cm.span().start() <= cases.get(cases.indexOf(cs) + 1).span().start() && cm.text().toLowerCase().contains("fall"));
                    if (commented) continue;
                    UNode lastStmt = stmts.get(stmts.size() - 1);
                    String indent = " ".repeat(Math.max(0, lastStmt.span().startCol() - 1));
                    ctx.report(self, cs, lastStmt, f -> ctx.applyEdits(f, List.of(new Edit(lastStmt.span().end(), lastStmt.span().end(), "\n" + indent + "break;"))));
                }
            }
        });

    static List<UNode> statements(UNode caseNode) {
        List<UNode> out = new ArrayList<>();
        UNode value = caseNode.field("value");
        for (UNode c : caseNode.children()) {
            if (c == value || c.is(Kind.COMMENT) || c.type().contains("label")) continue;
            if (STATEMENTS.contains(c.kind()) || c.type().endsWith("_statement") || c.type().endsWith("Stmt") || c.type().equals("declaration")) out.add(c);
        }
        return out;
    }

    static boolean terminates(UNode s) {
        if (s.is(Kind.BREAK) || s.is(Kind.RETURN) || s.is(Kind.THROW) || s.is(Kind.CONTINUE) || s.type().equals("goto_statement")) return true;
        if (s.is(Kind.BLOCK)) {
            List<UNode> inner = s.children().stream().filter(c -> !c.is(Kind.COMMENT)).toList();
            return !inner.isEmpty() && terminates(inner.get(inner.size() - 1));
        }
        if (s.is(Kind.EXPR_STMT)) return s.any(n -> n.is(Kind.CALL) && Set.of("exit", "abort", "System.exit", "process.exit", "os.Exit", "panic").contains(n.name()));
        return false;
    }

    static final Rule UNREACHABLE = rule("correctness.unreachable-code", "correctness", "medium")
        .title("Unreachable code")
        .explain("These statements come after a return, throw, break or continue in the same block, so they never run.")
        .why("Dead code usually means a mistake: something that was meant to run doesn't.")
        .changes("Remove the unreachable statements, or move them before the jump if they were meant to run.")
        .benefits("The code does what it reads as.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            for (UNode block : ctx.all(Kind.BLOCK)) {
                List<UNode> stmts = block.children().stream().filter(c -> !c.is(Kind.COMMENT)).toList();
                for (int i = 0; i < stmts.size() - 1; i++) {
                    UNode s = stmts.get(i);
                    if (!(s.is(Kind.RETURN) || s.is(Kind.THROW) || s.is(Kind.BREAK) || s.is(Kind.CONTINUE))) continue;
                    List<UNode> dead = stmts.subList(i + 1, stmts.size()).stream()
                        .filter(n -> !n.is(Kind.FUNCTION) && !n.is(Kind.CASE) && !n.type().contains("label") && !n.is(Kind.CLASS)).toList();
                    if (dead.isEmpty()) break;
                    UNode first = dead.get(0), lastDead = dead.get(dead.size() - 1);
                    ctx.report(self, first, lastDead, f -> f.note(ctx.commentPrefix() + "Delete these lines, or move them above the "
                        + s.kind().name().toLowerCase() + " on line " + s.line() + "."));
                    break;
                }
            }
        });

    static final Rule UNCHECKED_ALLOC = rule("correctness.unchecked-allocation", "correctness", "medium").langs("c", "cpp")
        .title("Allocation result not checked")
        .explain("The pointer returned by malloc/calloc/realloc is used without checking it for NULL.")
        .why("When memory runs out these functions return NULL, and dereferencing it crashes (or is undefined behaviour).")
        .changes("Check the pointer right after allocating and handle the failure.")
        .benefits("Out-of-memory becomes a handled error instead of a crash.")
        .tradeoffs("Adds an error path that must do something sensible (free what was allocated, return an error code).")
        .check((ctx, self) -> {
            for (UNode c : ctx.all(Kind.CALL)) {
                if (!Set.of("malloc", "calloc", "realloc").contains(String.valueOf(c.name()))) continue;
                UNode owner = c.ancestor(Kind.DECL, Kind.ASSIGN, Kind.EXPR_STMT);
                if (owner == null || owner.is(Kind.EXPR_STMT)) continue;
                String var = owner.is(Kind.DECL) ? owner.name() : owner.field("target") != null && owner.field("target").is(Kind.IDENT) ? owner.field("target").name() : null;
                if (var == null) continue;
                UNode scope = ctx.scopeOf(c);
                int after = owner.span().end();
                boolean checked = scope.any(n -> n.span().start() >= after && (
                    (n.is(Kind.IF) || n.is(Kind.LOOP) || n.is(Kind.TERNARY)) && n.field("cond") != null && n.field("cond").any(x -> x.is(Kind.IDENT) && var.equals(x.name()))
                    || n.is(Kind.CALL) && "assert".equals(n.name()) && n.any(x -> x.is(Kind.IDENT) && var.equals(x.name()))));
                boolean used = DataFlow.uses(var, scope).stream().anyMatch(u -> u.span().start() >= after && !isFreeArg(u));
                if (checked || !used) continue;
                UNode stmt = owner.ancestor(Kind.EXPR_STMT) != null ? owner.ancestor(Kind.EXPR_STMT) : owner.parent();
                UNode anchor = stmt == null ? owner : stmt;
                String indent = " ".repeat(Math.max(0, anchor.span().startCol() - 1));
                ctx.report(self, c, f -> {
                    ctx.applyEdits(f, List.of(new Edit(anchor.span().end(), anchor.span().end(),
                        "\n" + indent + "if (" + var + " == NULL) {\n" + indent + "    /* handle the allocation failure */\n" + indent + "}")));
                    f.source = "dataflow";
                });
            }
        });

    private static boolean isFreeArg(UNode ident) {
        UNode call = ident.ancestor(Kind.CALL);
        return call != null && "free".equals(call.name());
    }

    static final Rule USE_AFTER_FREE = rule("correctness.use-after-free", "correctness", "high").langs("c", "cpp")
        .title("Pointer used after free()")
        .explain("The pointer is read after the memory it points to was freed.")
        .why("Freed memory may already be reused, so this reads or corrupts unrelated data (undefined behaviour, often exploitable).")
        .changes("Stop using the pointer after free(); set it to NULL right after freeing.")
        .benefits("A later mistake becomes a clean NULL check failure instead of silent corruption.")
        .tradeoffs("None.")
        .check((ctx, self) -> freeAnalysis(ctx, self, false));

    static final Rule DOUBLE_FREE = rule("correctness.double-free", "correctness", "high").langs("c", "cpp")
        .title("Memory freed twice")
        .explain("free() is called again on a pointer that was already freed and not reassigned.")
        .why("A double free corrupts the allocator's bookkeeping and is undefined behaviour, frequently exploitable.")
        .changes("Free each allocation exactly once; set the pointer to NULL after freeing (free(NULL) is a no-op).")
        .benefits("The second call becomes harmless.")
        .tradeoffs("None.")
        .check((ctx, self) -> freeAnalysis(ctx, self, true));

    /** Flags uses (or second frees) of {@code p} after {@code free(p)} in the same or a nested block, before any reassignment. */
    private static void freeAnalysis(RuleContext ctx, Rule self, boolean doubleFree) {
        for (UNode fr : ctx.all(Kind.CALL)) {
            if (!"free".equals(fr.name()) || fr.list("args").size() != 1 || !fr.list("args").get(0).is(Kind.IDENT)) continue;
            String p = fr.list("args").get(0).name();
            UNode scope = ctx.scopeOf(fr), block = fr.ancestor(Kind.BLOCK);
            if (block == null) continue;
            int after = fr.span().end();
            int reassigned = DataFlow.defs(p, scope).stream().mapToInt(d -> d.span().start()).filter(s -> s > after).min().orElse(Integer.MAX_VALUE);
            for (UNode u : DataFlow.uses(p, scope)) {
                int at = u.span().start();
                if (at <= after || at >= reassigned || !u.isWithin(block)) continue;
                UNode call = u.ancestor(Kind.CALL);
                boolean isFree = call != null && "free".equals(call.name()) && call.list("args").size() == 1 && call.list("args").get(0) == u;
                if (isFree != doubleFree) continue;
                if (!doubleFree && !isDereference(u)) continue;
                UNode target = isFree ? call : u;
                ctx.report(self, target, f -> {
                    f.source = "dataflow";
                    f.explain = (doubleFree ? "free(" + p + ") is called again" : p + " is used") + " after free(" + p + ") on line " + fr.line() + ".";
                    f.note(ctx.commentPrefix() + "After free(" + p + "); write " + p + " = NULL; and don't use " + p + " again.");
                    if (!fr.ancestor(Kind.BLOCK).equals(u.ancestor(Kind.BLOCK))) f.confidence("medium");
                });
                break;
            }
        }
    }

    private static boolean isDereference(UNode u) {
        UNode p = u.parent();
        if (p == null) return false;
        if (p.is(Kind.UNARY) && "*".equals(p.op())) return true;
        if (p.is(Kind.INDEX) && p.field("object") == u) return true;
        if (p.is(Kind.MEMBER) && p.field("object") == u) return true;
        UNode call = u.ancestor(Kind.CALL);
        return call != null && call.list("args").contains(u) && !"free".equals(call.name());
    }

    static final Rule NULL_DEREFERENCE = rule("correctness.null-dereference", "correctness", "medium")
        .langs("java", "javascript", "python", "c", "cpp", "go")
        .title("Value is null when it is used")
        .explain("The variable is set to null (None/nil/NULL) and then dereferenced before anything else is assigned to it.")
        .why("The dereference throws (NullPointerException, TypeError, AttributeError) or crashes.")
        .changes("Assign a real value before using it, or check for null first.")
        .benefits("Removes a guaranteed failure on this path.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            List<UNode> defs = new ArrayList<>();
            for (UNode d : ctx.all(Kind.DECL)) if (d.name() != null && isNull(d.field("value"))) defs.add(d);
            for (UNode a : ctx.all(Kind.ASSIGN)) if (a.field("target") != null && a.field("target").is(Kind.IDENT) && "=".equals(a.op()) && isNull(a.field("value"))) defs.add(a);
            for (UNode d : defs) {
                String v = d.is(Kind.DECL) ? d.name() : d.field("target").name();
                UNode scope = ctx.scopeOf(d), block = d.ancestor(Kind.BLOCK) != null ? d.ancestor(Kind.BLOCK) : scope;
                int after = d.span().end();
                int next = DataFlow.defs(v, scope).stream().mapToInt(x -> x.span().start()).filter(s -> s > after).min().orElse(Integer.MAX_VALUE);
                for (UNode u : DataFlow.uses(v, scope)) {
                    int at = u.span().start();
                    if (at <= after || at >= next) continue;
                    // Only straight-line code: no branch, loop or handler between the assignment's block and the use.
                    boolean straight = true;
                    for (UNode p = u.parent(); p != null && p != block; p = p.parent()) {
                        if (p.is(Kind.IF) || p.is(Kind.LOOP) || p.is(Kind.TRY) || p.is(Kind.SWITCH) || p.is(Kind.TERNARY) || p.is(Kind.LAMBDA)
                            || p.is(Kind.BINARY) && Set.of("&&", "||", "and", "or", "??").contains(p.op())) { straight = false; break; }
                    }
                    if (!straight || !u.isWithin(block)) break;
                    UNode p = u.parent();
                    boolean deref = p != null && (p.is(Kind.MEMBER) && p.field("object") == u || p.is(Kind.INDEX) && p.field("object") == u
                        || p.is(Kind.UNARY) && "*".equals(p.op()) && ctx.lang("c", "cpp"));
                    if (p != null && p.is(Kind.CALL) && p.field("receiver") == u) deref = true;
                    if (p != null && p.is(Kind.MEMBER) && p.parent() != null && p.parent().is(Kind.CALL) && p.parent().field("receiver") == u) deref = true;
                    if (deref) {
                        ctx.report(self, u, f -> {
                            f.source = "dataflow";
                            f.explain = v + " is set to " + (d.is(Kind.DECL) ? d.field("value").text() : d.field("value").text()) + " on line " + d.line()
                                + " and dereferenced on line " + u.line() + " with no assignment in between.";
                        });
                    }
                    break;
                }
            }
        });

    private static boolean isNull(UNode v) {
        return v != null && (v.is(Kind.NULL) || v.is(Kind.IDENT) && "NULL".equals(v.name()));
    }

    // ---- helpers shared with other packs ----

    static boolean isNumber(UNode n, String value) { return n != null && n.is(Kind.NUMBER) && value.equals(n.text()); }

    /** The collection whose length {@code e} expresses (a.length, a.size(), a.length(), len(a), strlen(a)), else null. */
    static String lengthOf(UNode e) {
        if (e == null) return null;
        e = e.unparen();
        if (e.is(Kind.MEMBER) && "length".equals(e.name()) && e.field("object") != null) return e.field("object").text();
        if (e.is(Kind.CALL)) {
            String m = e.attr("method");
            if (Set.of("size", "length").contains(m) && e.field("receiver") != null) return e.field("receiver").text();
            if (Set.of("len", "strlen").contains(e.name()) && e.list("args").size() == 1) return e.list("args").get(0).text();
        }
        return null;
    }

    /** Replaces the operator token of a binary/assignment node, located between its two operands. */
    static Edit operatorEdit(RuleContext ctx, UNode n, String op, String replacement) {
        UNode l = n.field("left") != null ? n.field("left") : n.field("target");
        UNode r = n.field("right") != null ? n.field("right") : n.field("value");
        if (l == null || r == null) return null;
        String between = ctx.src().slice(l.span().end(), r.span().start());
        int i = between.indexOf(op);
        if (i < 0) return null;
        int s = l.span().end() + i;
        return new Edit(s, s + op.length(), replacement);
    }
}
