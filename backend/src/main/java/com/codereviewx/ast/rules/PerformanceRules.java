package com.codereviewx.ast.rules;

import static com.codereviewx.ast.rules.BaseRule.rule;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.codereviewx.ast.dataflow.DataFlow;
import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;

/** Time and space rules. Loop nesting and recursion are estimated by ComplexityAnalyzer, not here. */
final class PerformanceRules {
    private PerformanceRules() {}

    static final Pattern CONTAINER_TYPE = Pattern.compile("^(const\\s+)?(std::)?(vector|string|map|unordered_map|set|unordered_set|deque|list|multimap|multiset)\\b.*");

    static List<Rule> all() {
        return List.of(LINEAR_IN_LOOP, STRLEN_IN_CONDITION, SORT_IN_LOOP, STRING_CONCAT_IN_LOOP, CONTAINER_BY_VALUE,
            ALLOCATION_IN_LOOP, COPY_IN_LOOP, UNBOUNDED_ACCUMULATION);
    }

    static final Rule LINEAR_IN_LOOP = rule("time.linear-in-loop", "time", "medium").langs("javascript", "java", "python").max(2)
        .title("Linear operation inside a loop")
        .explain("This call scans or shifts the whole collection, and it runs once per loop iteration.")
        .why("It multiplies the loop's cost by n, so a single loop costs O(n²) in total.")
        .changes("Use a structure with O(1) lookup or removal, such as a hash set or a deque.")
        .benefits("Removes the hidden factor of n.")
        .tradeoffs("Uses O(n) extra memory, and hash sets don't preserve order (use LinkedHashSet / insertion-ordered Set if needed).")
        .check((ctx, self) -> {
            Map<String, String> tpl = Map.of(
                "javascript", "const seen = new Set();\n// ...\nif (!seen.has(x)) { seen.add(x); }",
                "python", "from collections import deque\nq = deque(items)\nq.popleft()   # O(1) instead of list.pop(0)",
                "java", "Set<Integer> seen = new HashSet<>();\n// seen.contains(x) is O(1) on average");
            ctx.root().walk(n -> {
                if (!ctx.inLoop(n)) return;
                String why = linearOp(ctx, n);
                if (why == null) return;
                ctx.report(self, n, f -> {
                    f.template(ctx.lang("python") && why.equals("membership")
                        ? "seen = set(items)   # build once\nif x in seen:       # O(1) on average\n    ..." : tpl.get(ctx.lang()));
                    f.bump = 1;
                    f.complexity("O(n²) in this loop", "O(1)", "O(n)", "O(n)");
                    if (why.endsWith("?")) f.confidence("medium");
                });
            });
        });

    /** Why {@code n} is a linear-time library operation, or null. A trailing "?" marks an assumed receiver type. */
    private static String linearOp(RuleContext ctx, UNode n) {
        if (n.is(Kind.CALL) && n.field("receiver") != null) {
            String m = n.attr("method");
            List<UNode> a = n.list("args");
            switch (ctx.lang()) {
                case "javascript" -> {
                    if (Set.of("indexOf", "includes", "lastIndexOf", "shift", "unshift").contains(m)) return m;
                }
                case "python" -> {
                    if (Set.of("index", "count", "remove").contains(m) && isList(ctx, n.field("receiver"), n)) return m;
                    if ("pop".equals(m) && a.size() == 1 && CorrectnessRules.isNumber(a.get(0), "0")) return "pop(0)";
                    if ("insert".equals(m) && !a.isEmpty() && CorrectnessRules.isNumber(a.get(0), "0")) return "insert(0)";
                }
                case "java" -> {
                    if (Set.of("contains", "indexOf", "lastIndexOf").contains(m)) {
                        String t = n.field("receiver").is(Kind.IDENT) ? ctx.typeOf(n.field("receiver").name(), n) : null;
                        if (t == null) return m + "?";
                        if (t.matches("(Array|Linked)?List<.*|List|String|.*\\[\\]")) return m;
                    }
                    if ("remove".equals(m) && a.size() == 1 && CorrectnessRules.isNumber(a.get(0), "0")) return "remove(0)";
                }
                default -> { }
            }
        }
        if (ctx.lang("python") && n.is(Kind.BINARY) && ("in".equals(n.op()) || "not in".equals(n.op()))
            && n.parent() != null && !n.parent().is(Kind.LOOP) && n.field("right") != null && isList(ctx, n.field("right"), n)) return "membership";
        return null;
    }

    private static boolean isList(RuleContext ctx, UNode e, UNode at) {
        if (e.is(Kind.LIST)) return true;
        if (!e.is(Kind.IDENT)) return false;
        UNode v = ctx.valueOf(e.name(), at);
        if (v != null) return v.is(Kind.LIST) || v.is(Kind.CALL) && Set.of("list", "sorted").contains(v.name());
        for (UNode p : ctx.scopeOf(at).list("params")) {
            if (e.name().equals(p.name()) && p.field("default") != null && p.field("default").is(Kind.LIST)) return true;
            if (e.name().equals(p.name()) && p.attr("type") != null && p.attr("type").matches("(?i)(list|List\\[.*\\]|list\\[.*\\])")) return true;
        }
        return false;
    }

    static final Rule STRLEN_IN_CONDITION = rule("time.strlen-in-condition", "time", "medium").langs("c", "cpp")
        .title("strlen() in the loop condition")
        .explain("The condition is re-evaluated on every iteration, and strlen() walks the whole string each time.")
        .why("A loop that looks linear becomes O(n²) for long strings.")
        .changes("Compute the length once before the loop.")
        .benefits("Restores O(n).")
        .tradeoffs("If the loop changes the string's length, recompute deliberately.")
        .check((ctx, self) -> {
            for (UNode l : ctx.all(Kind.LOOP)) {
                UNode c = l.field("cond");
                if (c == null) continue;
                UNode call = c.find(Kind.CALL).stream().filter(x -> "strlen".equals(x.name()) && x.list("args").size() == 1).findFirst().orElse(null);
                if (call == null) continue;
                String s = call.list("args").get(0).text();
                String indent = " ".repeat(Math.max(0, l.span().startCol() - 1));
                ctx.report(self, l, c, f -> {
                    ctx.applyEdits(f, List.of(new Edit(l.span().start(), l.span().start(), "size_t len = strlen(" + s + ");\n" + indent), Edit.replace(call, "len")));
                    f.bump = 1;
                    f.complexity("O(n²) in this loop", "O(1)", "O(n)", "O(1)");
                });
            }
        });

    static final Set<String> SORT_CALLS = Set.of("sorted", "sort", "qsort", "Arrays.sort", "Collections.sort", "std::sort",
        "std::stable_sort", "sort.Ints", "sort.Strings", "sort.Slice", "sort.SliceStable", "slices.Sort");

    static boolean isSort(UNode n) {
        return n.is(Kind.CALL) && (SORT_CALLS.contains(n.name()) || "sort".equals(n.attr("method")) || "sorted".equals(n.name()));
    }

    static final Rule SORT_IN_LOOP = rule("time.sort-in-loop", "time", "medium")
        .title("Sorting inside a loop")
        .explain("The collection is sorted on every iteration.")
        .why("Each sort costs O(n log n), so the loop costs O(n² log n) in total.")
        .changes("Sort once before the loop, or keep the data ordered with a heap or balanced tree as you insert.")
        .benefits("Removes a repeated O(n log n) factor.")
        .tradeoffs("A heap/tree changes how you read the data; sorting once only works if the loop doesn't change the order.")
        .check((ctx, self) -> {
            for (UNode c : ctx.all(Kind.CALL)) {
                if (!isSort(c) || !ctx.inLoop(c)) continue;
                ctx.report(self, c, f -> f.complexity("O(n² log n)", "O(1)", "O(n log n)", "O(1)"));
            }
        });

    static final Rule STRING_CONCAT_IN_LOOP = rule("time.string-concat-in-loop", "time", "low").langs("java", "python").max(1)
        .title("String concatenation in a loop")
        .explain("Strings are immutable, so each += copies the whole string built so far.")
        .why("Building a string of n pieces this way costs O(n²) character copies.")
        .changes("Append to a builder (StringBuilder, a list joined once) and convert once at the end.")
        .benefits("Linear time.")
        .tradeoffs("Slightly more verbose. CPython sometimes optimises += in place, so the Python cost is implementation-dependent.")
        .check((ctx, self) -> {
            for (UNode a : ctx.all(Kind.ASSIGN)) {
                UNode t = a.field("target"), v = a.field("value");
                if (t == null || v == null || !t.is(Kind.IDENT) || !ctx.inLoop(a)) continue;
                boolean plusEq = "+=".equals(a.op());
                boolean selfConcat = "=".equals(a.op()) && v.is(Kind.BINARY) && "+".equals(v.op()) && v.field("left") != null
                    && v.field("left").is(Kind.IDENT) && t.name().equals(v.field("left").name());
                if (!plusEq && !selfConcat) continue;
                boolean str;
                if (ctx.lang("java")) str = "String".equals(ctx.typeOf(t.name(), a));
                else {
                    UNode init = ctx.valueOf(t.name(), ctx.enclosingLoops(a).get(ctx.enclosingLoops(a).size() - 1));
                    str = init != null && init.is(Kind.STRING);
                }
                if (!str) continue;
                ctx.report(self, a, f -> {
                    f.template(ctx.lang("java") ? "StringBuilder sb = new StringBuilder();\nfor (String part : parts) sb.append(part);\nString result = sb.toString();"
                        : "parts = []\nfor item in items:\n    parts.append(str(item))\nresult = \"\".join(parts)");
                    if (ctx.lang("python")) f.confidence("low");
                    f.complexity("O(n²) in this loop", "O(n)", "O(n)", "O(n)");
                });
            }
        });

    static final Rule CONTAINER_BY_VALUE = rule("space.container-by-value", "space", "medium").langs("cpp")
        .title("Container passed by value")
        .explain("The parameter is a full container, so every call copies all of its elements.")
        .why("That's O(n) extra time and memory per call, which adds up quickly inside recursion or loops.")
        .changes("Take the container by const reference.")
        .benefits("No copy. The function reads the caller's data directly.")
        .tradeoffs("If the function needs a private copy to modify, keep pass-by-value. That's a deliberate choice.")
        .check((ctx, self) -> {
            for (UNode p : ctx.all(Kind.PARAM)) {
                String type = p.attr("type");
                if (type == null || p.flag("reference") || p.flag("pointer") || !CONTAINER_TYPE.matcher(type).matches()) continue;
                if (p.ancestor(Kind.LAMBDA) != null && p.ancestor(Kind.FUNCTION) == null) continue;
                UNode t = p.field("type");
                ctx.report(self, p, f -> ctx.applyEdits(f, List.of(
                    Edit.insertBefore(p, p.flag("const") ? "" : "const "), new Edit(t.span().end(), t.span().end(), "&"))));
            }
        });

    static final Rule ALLOCATION_IN_LOOP = rule("space.allocation-in-loop", "space", "low").max(1)
        .title("Collection allocated on every iteration")
        .explain("A new collection is created inside the loop and only used within that iteration.")
        .why("Allocating and discarding it every time adds garbage-collection or allocator work proportional to the loop count.")
        .changes("Allocate once before the loop and clear it at the start of each iteration.")
        .benefits("One allocation instead of n.")
        .tradeoffs("The reused collection must be cleared correctly; don't reuse it if it is stored elsewhere.")
        .check((ctx, self) -> {
            for (UNode d : ctx.all(Kind.DECL)) {
                if (d.name() == null || !ctx.inLoop(d) || !allocates(ctx, d)) continue;
                UNode loop = ctx.enclosingLoops(d).get(0);
                boolean escapes = DataFlow.uses(d.name(), ctx.scopeOf(d)).stream().anyMatch(u -> u.isWithin(loop) && escapes(u));
                if (escapes) continue;
                ctx.report(self, d, f -> f.confidence("medium"));
            }
        });

    private static boolean allocates(RuleContext ctx, UNode d) {
        UNode v = d.field("value");
        if (ctx.lang("cpp") && d.attr("type") != null && CONTAINER_TYPE.matcher(d.attr("type")).matches()
            && !d.attr("type").matches("(const\\s+)?(std::)?string.*")) return true;
        if (v == null) return false;
        if (v.is(Kind.NEW)) return v.flag("array") || String.valueOf(v.name()).matches("(Array|Hash|Linked|Tree)\\w*(List|Map|Set|Deque)|StringBuilder|Map|Set|Array");
        if (v.is(Kind.CALL)) return Set.of("malloc", "calloc", "make").contains(v.name());
        return false;
    }

    private static boolean escapes(UNode use) {
        UNode p = use.parent();
        if (p == null) return false;
        if (p.is(Kind.RETURN) || p.is(Kind.ASSIGN) && p.field("value") == use) return true;
        if (p.is(Kind.CALL) && p.list("args").contains(use)) return true;
        return p.type().equals("argument_list") || p.type().equals("arguments") || p.type().equals("expression_list");
    }

    static final Rule COPY_IN_LOOP = rule("space.copy-in-loop", "space", "low").max(1)
        .title("Collection copied inside a loop")
        .explain("A full copy of a collection is made on every iteration.")
        .why("Each copy costs O(n) time and memory, so the loop does O(n²) work overall.")
        .changes("Copy once outside the loop, or work on indices/views instead of copies.")
        .benefits("Removes a repeated O(n) copy.")
        .tradeoffs("Without a copy, changes are visible to other references. Copy deliberately when isolation is required.")
        .check((ctx, self) -> {
            ctx.root().walk(n -> {
                if (!ctx.inLoop(n) || !isCopy(ctx, n)) return;
                ctx.report(self, n, f -> {
                    f.confidence("medium");
                    f.complexity("O(n²) in this loop", "O(n)", "O(n)", "O(n)");
                });
            });
        });

    private static boolean isCopy(RuleContext ctx, UNode n) {
        switch (ctx.lang()) {
            case "python" -> {
                if (n.is(Kind.INDEX) && n.field("index") != null && n.field("index").text().equals(":")) return true;
                if (n.is(Kind.CALL) && "copy".equals(n.attr("method")) && n.field("receiver") != null && n.list("args").isEmpty()) return true;
                return n.is(Kind.CALL) && "list".equals(n.name()) && n.list("args").size() == 1 && n.list("args").get(0).is(Kind.IDENT);
            }
            case "javascript" -> {
                if (n.is(Kind.LIST) && n.children().size() == 1 && n.children().get(0).type().equals("spread_element")) return true;
                return n.is(Kind.CALL) && ("slice".equals(n.attr("method")) && n.list("args").isEmpty() || "Array.from".equals(n.name()));
            }
            case "java" -> {
                return n.is(Kind.NEW) && String.valueOf(n.name()).matches("(Array|Linked)List|Hash(Map|Set)") && n.list("args").size() == 1
                    && n.list("args").get(0).is(Kind.IDENT);
            }
            default -> { return false; }
        }
    }

    static final Rule UNBOUNDED_ACCUMULATION = rule("space.unbounded-accumulation", "space", "medium")
        .title("Unbounded growth in an endless loop")
        .explain("Items are added to a collection inside a loop that has no exit (no break, return or throw).")
        .why("The collection grows until memory runs out.")
        .changes("Bound the collection (a ring buffer, a size check, periodic flushing) or give the loop an exit condition.")
        .benefits("Memory use stays bounded.")
        .tradeoffs("You must decide what happens to old items when the bound is reached.")
        .check((ctx, self) -> {
            for (UNode l : ctx.all(Kind.LOOP)) {
                if (!"infinite".equals(l.attr("loop"))) continue;
                boolean exits = l.any(n -> (n.is(Kind.BREAK) || n.is(Kind.RETURN) || n.is(Kind.THROW)) && ctx.scopeOf(n) == ctx.scopeOf(l)
                    || n.is(Kind.CALL) && Set.of("exit", "sys.exit", "os.Exit", "System.exit", "process.exit", "panic").contains(n.name()));
                if (exits) continue;
                UNode add = l.find(n -> n.is(Kind.CALL) && Set.of("append", "push", "add", "put", "push_back", "emplace_back", "insert", "addLast")
                    .contains(n.attr("method")) && n.field("receiver") != null || n.is(Kind.CALL) && "append".equals(n.name())).stream().findFirst().orElse(null);
                if (add != null) ctx.report(self, add, f -> f.confidence("medium"));
            }
        });
}
