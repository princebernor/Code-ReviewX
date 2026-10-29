package com.codereviewx.ast.rules;

import static com.codereviewx.ast.rules.BaseRule.rule;

import java.util.List;
import java.util.Set;

import com.codereviewx.ast.dataflow.DataFlow;
import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;

final class PracticeRules {
    private PracticeRules() {}

    private static final Set<String> JAVA_RESOURCES = Set.of("FileInputStream", "FileOutputStream", "FileReader", "FileWriter",
        "BufferedReader", "BufferedWriter", "PrintWriter", "Socket", "ServerSocket", "ObjectInputStream", "ObjectOutputStream",
        "RandomAccessFile", "Scanner", "InputStreamReader");

    static List<Rule> all() {
        return List.of(NONE_COMPARISON, VAR_DECLARATION, PREFER_CONST, BITS_STDCPP, USING_NAMESPACE, GENERIC_CATCH, RESOURCE_LEAK,
            MISSING_ERROR_CONTEXT, MISSING_DOC);
    }

    static final Rule NONE_COMPARISON = rule("practices.none-comparison", "practices", "low").langs("python")
        .title("Comparison to None with ==")
        .explain("None is a singleton and should be tested by identity.")
        .why("== can be overridden by a class's __eq__, so it may return surprising results. PEP 8 recommends is / is not.")
        .changes("Use is None / is not None.")
        .benefits("Always correct, slightly faster, idiomatic.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            for (UNode b : ctx.all(Kind.BINARY)) {
                if (!"==".equals(b.op()) && !"!=".equals(b.op())) continue;
                UNode l = b.field("left"), r = b.field("right");
                if (l == null || r == null || !(r.is(Kind.NULL) || l.is(Kind.NULL))) continue;
                UNode other = r.is(Kind.NULL) ? l : r;
                String fix = other.text() + ("==".equals(b.op()) ? " is None" : " is not None");
                ctx.report(self, b, f -> ctx.applyEdits(f, List.of(Edit.replace(b, fix))));
            }
        });

    static final Rule VAR_DECLARATION = rule("practices.var-declaration", "practices", "low").langs("javascript").max(2)
        .title("var declaration")
        .explain("var is function-scoped and hoisted.")
        .why("Variables leak out of blocks, and closures in loops capture the same variable, a common source of subtle bugs.")
        .changes("Use let (or const when the value never changes).")
        .benefits("Block scoping matches how the code reads.")
        .tradeoffs("None in modern runtimes.")
        .check((ctx, self) -> {
            for (UNode d : ctx.all(Kind.DECL)) {
                if (!"var".equals(d.attr("declKind")) || d.parent() == null) continue;
                UNode decl = d.parent();
                int s = decl.span().start();
                if (!ctx.src().slice(s, s + 3).equals("var")) continue;
                ctx.report(self, decl, f -> ctx.applyEdits(f, List.of(new Edit(s, s + 3, "let"))));
            }
        });

    static final Rule PREFER_CONST = rule("practices.prefer-const", "practices", "info").langs("javascript").max(1)
        .title("let that is never reassigned")
        .explain("This variable is declared with let but never reassigned.")
        .why("const tells the reader (and the engine) the binding never changes, which removes a class of accidental reassignments.")
        .changes("Declare it with const.")
        .benefits("Intent is explicit and reassignment becomes an error.")
        .tradeoffs("const doesn't freeze objects or arrays; their contents can still change.")
        .check((ctx, self) -> {
            for (UNode d : ctx.all(Kind.DECL)) {
                if (!"let".equals(d.attr("declKind")) || d.name() == null || d.field("value") == null || d.parent() == null) continue;
                if (d.parent().parent() != null && d.parent().parent().is(Kind.LOOP)) continue; // for (let i = 0; ...)
                UNode scope = ctx.scopeOf(d);
                String n = d.name();
                boolean mutated = DataFlow.defs(n, scope).size() > 1 || scope.any(x -> x.is(Kind.UNARY) && Set.of("++", "--").contains(x.op())
                    && x.field("operand") != null && n.equals(x.field("operand").name()));
                if (mutated) continue;
                int s = d.parent().span().start();
                if (!ctx.src().slice(s, s + 3).equals("let")) continue;
                ctx.report(self, d.parent(), f -> ctx.applyEdits(f, List.of(new Edit(s, s + 3, "const"))));
            }
        });

    static final Rule BITS_STDCPP = rule("practices.bits-stdcpp", "practices", "low").langs("cpp")
        .title("Non-standard umbrella header")
        .explain("<bits/stdc++.h> is a GCC internal header that includes the entire standard library.")
        .why("It's fine for timed contests, but it doesn't compile on MSVC or Clang/libc++ and slows builds noticeably.")
        .changes("Include only the headers you use.")
        .benefits("Portable and faster to compile.")
        .tradeoffs("You need to know which header declares what.")
        .check((ctx, self) -> {
            for (UNode i : ctx.all(Kind.IMPORT)) {
                if (!"<bits/stdc++.h>".equals(i.attr("path"))) continue;
                ctx.report(self, i, f -> ctx.applyEdits(f, List.of(Edit.replace(i, "#include <vector>\n#include <iostream>"))));
            }
        });

    static final Rule USING_NAMESPACE = rule("practices.using-namespace-std", "practices", "low").langs("cpp")
        .title("using namespace std at file scope")
        .explain("Every std name becomes visible unqualified.")
        .why("Names like count, distance or data can collide with your own identifiers and cause confusing overload errors.")
        .changes("Qualify names (std::vector) or import specific ones.")
        .benefits("No accidental name collisions.")
        .tradeoffs("Slightly more typing. Many teams accept it in .cpp files but never in headers.")
        .check((ctx, self) -> {
            for (UNode i : ctx.all(Kind.IMPORT)) {
                if (!i.flag("usingNamespace") || !i.text().matches("(?s).*\\bstd\\s*;\\s*") || i.ancestor(Kind.FUNCTION) != null) continue;
                ctx.report(self, i, f -> ctx.applyEdits(f, List.of(Edit.replace(i, "using std::vector;  // import only what you need"))));
            }
        });

    static final Rule GENERIC_CATCH = rule("practices.generic-catch", "practices", "low").langs("java")
        .title("Catching a generic exception")
        .explain("Catching Exception or Throwable handles every failure the same way.")
        .why("Programming errors (NullPointerException) get handled as if they were expected conditions.")
        .changes("Catch the specific checked or expected exception.")
        .benefits("Unexpected failures propagate and get noticed.")
        .tradeoffs("You may need several catch blocks or a multi-catch.")
        .check((ctx, self) -> {
            for (UNode c : ctx.all(Kind.CATCH)) {
                String t = c.attr("type");
                if (!"Exception".equals(t) && !"Throwable".equals(t)) continue;
                int s = c.span().start();
                String head = ctx.src().slice(s, c.span().end());
                int i = head.indexOf(t);
                ctx.report(self, c, c, f -> {
                    if (i >= 0) ctx.applyEdits(f, List.of(new Edit(s + i, s + i + t.length(), "NumberFormatException")));
                    f.assumptions = "NumberFormatException is an example; use the exception the try block actually throws.";
                    f.end = f.line;
                    f.original = ctx.src().line(f.line);
                    if (f.suggested != null) f.suggested = f.suggested.split("\n")[0];
                });
            }
        });

    static final Rule RESOURCE_LEAK = rule("practices.resource-leak", "practices", "medium").langs("java", "python", "c", "cpp")
        .title("Resource may not be closed")
        .explain("A file, stream or socket is opened without a construct that guarantees it is closed.")
        .why("If an exception happens (or the close is forgotten) the handle leaks; programs eventually run out of file descriptors.")
        .changes("Open it in try-with-resources (Java) or a with block (Python), or close it on every path (C).")
        .benefits("The resource is released even when an error occurs.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            for (UNode d : ctx.all(Kind.DECL)) checkResource(ctx, self, d, d.name(), d.field("value"));
            for (UNode a : ctx.all(Kind.ASSIGN)) {
                UNode t = a.field("target");
                if (t != null && t.is(Kind.IDENT)) checkResource(ctx, self, a, t.name(), a.field("value"));
            }
        });

    private static void checkResource(RuleContext ctx, Rule self, UNode def, String var, UNode value) {
        if (var == null || value == null) return;
        boolean opens = switch (ctx.lang()) {
            case "java" -> value.is(Kind.NEW) && JAVA_RESOURCES.contains(value.name())
                && !("Scanner".equals(value.name()) || "InputStreamReader".equals(value.name())) || value.is(Kind.NEW) && "Scanner".equals(value.name())
                && !value.list("args").isEmpty() && !value.list("args").get(0).text().equals("System.in");
            case "python" -> value.is(Kind.CALL) && "open".equals(value.name());
            default -> value.is(Kind.CALL) && "fopen".equals(value.name());
        };
        if (!opens) return;
        if (ctx.lang("java")) {
            UNode tr = def.ancestor(Kind.TRY);
            if (tr != null && tr.flag("resources") && (tr.field("body") == null || !def.isWithin(tr.field("body")))) return;
        }
        UNode scope = ctx.scopeOf(def);
        String closer = ctx.lang("c", "cpp") ? "fclose" : "close";
        boolean closed = scope.any(n -> n.is(Kind.CALL) && (closer.equals(n.attr("method")) && n.field("receiver") != null && var.equals(n.field("receiver").name())
            || closer.equals(n.name()) && n.list("args").size() == 1 && var.equals(n.list("args").get(0).name())));
        boolean closedInFinally = closed && scope.any(n -> n.is(Kind.TRY) && n.field("finally") != null
            && n.field("finally").any(x -> x.is(Kind.CALL) && x.text().contains(var)));
        boolean returned = scope.any(n -> n.is(Kind.RETURN) && n.any(x -> x.is(Kind.IDENT) && var.equals(x.name())));
        if (returned || closedInFinally) return;
        ctx.report(self, value, f -> {
            f.template(switch (ctx.lang()) {
                case "java" -> "try (var reader = new BufferedReader(new FileReader(path))) {\n    // use reader\n}  // closed automatically";
                case "python" -> "with open(path) as f:\n    data = f.read()\n# closed automatically";
                default -> "FILE *fp = fopen(path, \"r\");\nif (fp == NULL) { /* handle */ }\n/* ... */\nfclose(fp);";
            });
            if (closed) {
                f.sev = "low";
                f.confidence("medium");
                f.explain = "The resource is closed, but not in a way that also runs when an error occurs earlier.";
            }
        });
    }

    static final Rule MISSING_ERROR_CONTEXT = rule("practices.missing-error-context", "practices", "low").langs("java", "python")
        .title("Exception raised without a message")
        .explain("A generic exception is thrown with no message.")
        .why("When it surfaces in a log there is nothing saying what failed or with which value.")
        .changes("Throw a specific exception type with a message that includes the relevant values.")
        .benefits("Failures can be diagnosed from the log alone.")
        .tradeoffs("None.")
        .check((ctx, self) -> {
            for (UNode t : ctx.all(Kind.THROW)) {
                UNode e = t.children().stream().filter(c -> !c.is(Kind.COMMENT)).findFirst().orElse(null);
                if (e == null) continue;
                boolean bare = ctx.lang("java") ? e.is(Kind.NEW) && Set.of("Exception", "RuntimeException", "IllegalStateException", "IllegalArgumentException")
                    .contains(e.name()) && e.list("args").isEmpty()
                    : e.is(Kind.IDENT) && Set.of("Exception", "ValueError", "RuntimeError").contains(e.name())
                    || e.is(Kind.CALL) && Set.of("Exception", "ValueError", "RuntimeError").contains(e.name()) && e.list("args").isEmpty();
                if (!bare) continue;
                ctx.report(self, t, f -> f.note(ctx.lang("java") ? "throw new IllegalArgumentException(\"index out of range: \" + index);"
                    : "raise ValueError(f\"index out of range: {index}\")"));
            }
        });

    static final Rule MISSING_DOC = rule("practices.missing-doc", "practices", "info").langs("java").max(1)
        .title("Public method without documentation")
        .explain("A non-trivial public method has no Javadoc comment.")
        .why("Public methods are an API: callers need to know what arguments are valid and what is returned or thrown.")
        .changes("Add a short Javadoc describing parameters, return value and exceptions.")
        .benefits("Callers can use the method correctly without reading its body.")
        .tradeoffs("Docs must be kept in sync with the code.")
        .check((ctx, self) -> {
            for (UNode f : ctx.all(Kind.FUNCTION)) {
                if (f.flag("doc") || !String.valueOf(f.attr("modifiers")).contains("public") || f.endLine() - f.line() + 1 <= 15) continue;
                UNode cls = f.ancestor(Kind.CLASS);
                if (cls == null || !String.valueOf(cls.attr("modifiers")).contains("public")) continue;
                ctx.report(self, f, f, x -> {
                    x.end = x.line;
                    x.original = ctx.src().line(f.line());
                    x.note("/**\n * What " + f.name() + " does.\n *\n * @param ... what each parameter means\n * @return what is returned\n */");
                });
            }
        });
}
