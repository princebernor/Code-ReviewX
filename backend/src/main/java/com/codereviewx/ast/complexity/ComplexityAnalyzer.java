package com.codereviewx.ast.complexity;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;
import com.codereviewx.ast.rules.Finding;
import com.codereviewx.ast.rules.RuleContext;

/**
 * Estimates time and auxiliary-space complexity from the unified AST.
 *
 * <p>What is structural (read directly from the tree): loop nesting, which loops have literal bounds, recursive
 * calls and how many can run per invocation, allocation sites. What is heuristic: that loop bounds grow with the
 * input (n), that halving/doubling updates make a loop logarithmic, the cost of library calls, and the shape of
 * recursion. Every estimate lists its evidence and a confidence level.
 */
public final class ComplexityAnalyzer {
    /** Evidence entry: what was seen, where, and whether it is a structural fact or an inference. */
    public record Evidence(int line, String path, String detail, String basis) {}

    public record Result(String time, String space, int timeRank, int spaceRank, int linear, int log, boolean exponential,
                         String confidence, List<Evidence> evidence, List<Finding> findings) {}

    private static final String[] SUP = {"", "", "²", "³", "⁴"};
    private static final Set<String> MEMO_NAMES = Set.of("dp", "seen", "visited", "lookup", "table");

    public Result analyze(RuleContext ctx, List<Finding> ruleFindings) {
        List<Evidence> ev = new ArrayList<>();
        Map<UNode, String> loopClass = new IdentityHashMap<>();
        for (UNode l : ctx.all(Kind.LOOP)) loopClass.put(l, classify(ctx, l));
        boolean heuristic = false;

        // 1) deepest chain of nested loops (per function), compared by linear depth, then log depth
        int[] best = {0, 0};
        List<UNode> bestChain = new ArrayList<>();
        for (UNode l : ctx.all(Kind.LOOP)) {
            if ("constant".equals(loopClass.get(l))) continue;
            List<UNode> chain = new ArrayList<>(ctx.enclosingLoops(l));
            java.util.Collections.reverse(chain);
            chain.add(l);
            chain.removeIf(x -> "constant".equals(loopClass.get(x)));
            int lin = (int) chain.stream().filter(x -> "linear".equals(loopClass.get(x))).count(), lg = chain.size() - lin;
            if (lin > best[0] || lin == best[0] && lg > best[1]) { best[0] = lin; best[1] = lg; bestChain = chain; }
        }
        int structuralLinear = best[0];
        int lin = best[0], lg = best[1];
        for (UNode l : bestChain) {
            String c = loopClass.get(l);
            ev.add(new Evidence(l.line(), l.path(), ("log".equals(c) ? "loop variable is halved/doubled each iteration (logarithmic)"
                : "loop over the input (linear)") + " at nesting depth " + (bestChain.indexOf(l) + 1), "log".equals(c) ? "heuristic" : "structural"));
            if ("log".equals(c)) heuristic = true;
        }
        for (UNode l : ctx.all(Kind.LOOP)) {
            if ("constant".equals(loopClass.get(l))) ev.add(new Evidence(l.line(), l.path(), "loop has a literal bound, counted as O(1)", "structural"));
        }

        // 2) linear library work inside loops (reported by rules with a bump)
        for (Finding f : ruleFindings) {
            if (f.bump <= 0 || f.node == null) continue;
            List<UNode> loops = new ArrayList<>(ctx.enclosingLoops(f.node));
            if (f.node.is(Kind.LOOP)) loops.add(f.node); // e.g. strlen() in this loop's own condition
            int l2 = (int) loops.stream().filter(x -> "linear".equals(loopClass.get(x))).count() + f.bump;
            int g2 = (int) loops.stream().filter(x -> "log".equals(loopClass.get(x))).count();
            if (l2 > lin || l2 == lin && g2 > lg) { lin = l2; lg = g2; }
            ev.add(new Evidence(f.line, f.ast == null ? null : f.ast.get("path"), "linear-time call inside a loop (" + f.title + ")", "heuristic"));
            heuristic = true;
        }

        // 3) sorting costs n log n at its loop depth
        for (UNode c : ctx.all(Kind.CALL)) {
            if (!isSort(c)) continue;
            List<UNode> loops = ctx.enclosingLoops(c);
            int l2 = (int) loops.stream().filter(x -> "linear".equals(loopClass.get(x))).count() + 1;
            int g2 = (int) loops.stream().filter(x -> "log".equals(loopClass.get(x))).count() + 1;
            if (l2 > lin || l2 == lin && g2 > lg) { lin = l2; lg = g2; }
            ev.add(new Evidence(c.line(), c.path(), "sort call: O(n log n) per call", "heuristic"));
            heuristic = true;
        }

        // 4) recursion
        boolean exponential = false, recursion = false;
        UNode branchingFn = null;
        for (UNode fn : ctx.functions()) {
            if (fn.name() == null) continue;
            List<UNode> calls = selfCalls(fn);
            if (calls.isEmpty()) continue;
            recursion = true;
            int perInvocation = pathCalls(fn, fn, calls);
            boolean memo = memoized(fn);
            boolean halving = calls.stream().allMatch(ComplexityAnalyzer::halvesArgument);
            if (perInvocation >= 2 && !memo && !halving) {
                exponential = true;
                branchingFn = branchingFn == null ? fn : branchingFn;
                ev.add(new Evidence(fn.line(), fn.path(), fn.name() + "() makes up to " + perInvocation + " recursive calls per invocation without caching", "heuristic"));
            } else if (perInvocation >= 2 && halving) {
                if (lin < 1 || lin == 1 && lg < 1) { lin = Math.max(lin, 1); lg = Math.max(lg, 1); }
                ev.add(new Evidence(fn.line(), fn.path(), fn.name() + "() splits its input in half on each of " + perInvocation + " calls (divide and conquer, n log n assumed)", "heuristic"));
            } else if (halving) {
                if (lin == 0) lg = Math.max(lg, 1);
                ev.add(new Evidence(fn.line(), fn.path(), fn.name() + "() recurses on half of its input (logarithmic depth)", "heuristic"));
            } else {
                if (lin == 0) lin = 1;
                ev.add(new Evidence(fn.line(), fn.path(), fn.name() + "() is recursive" + (memo ? " with a cache" : "") + " (linear depth assumed)", "heuristic"));
            }
        }

        int timeRank = exponential ? 6 : lin >= 3 ? 5 : lin == 2 ? 4 : lin == 1 && lg > 0 ? 3 : lin == 1 ? 2 : lg > 0 ? 1 : 0;
        String time = exponential ? "O(2ⁿ)" : bigO(lin, lg);

        // 5) auxiliary space
        int spaceRank = 0;
        for (UNode n : allocations(ctx)) {
            int r = twoDimensional(ctx, n) ? 2 : 1;
            if (r > spaceRank) {
                spaceRank = r;
                ev.add(new Evidence(n.line(), n.path(), (r == 2 ? "2-D allocation" : "allocation that can grow with the input"), "heuristic"));
            }
        }
        if (recursion && spaceRank == 0) {
            spaceRank = 1;
            ev.add(new Evidence(0, null, "recursion uses call-stack space", "heuristic"));
        }
        String space = new String[] {"O(1)", "O(n)", "O(n²)"}[spaceRank];

        String confidence = recursion ? "low" : heuristic || !ctx.findings().isEmpty() && lin > 0 ? "medium" : "high";
        if (lin == 0 && lg == 0 && !recursion) confidence = "high";
        if (heuristic && !recursion) confidence = "medium";

        List<Finding> extra = new ArrayList<>();
        if (structuralLinear >= 2) extra.add(nestedLoopFinding(ctx, bestChain, loopClass, structuralLinear));
        if (exponential) extra.add(branchingFinding(ctx, branchingFn));
        return new Result(time, space, timeRank, spaceRank, lin, lg, exponential, confidence, ev, extra);
    }

    public static String bigO(int lin, int lg) {
        List<String> parts = new ArrayList<>();
        if (lin == 1) parts.add("n");
        else if (lin > 1) parts.add("n" + SUP[Math.min(lin, 4)]);
        if (lg == 1) parts.add("log n");
        else if (lg > 1) parts.add("log" + SUP[Math.min(lg, 3)] + " n");
        return "O(" + (parts.isEmpty() ? "1" : String.join(" ", parts)) + ")";
    }

    /** constant | log | linear */
    static String classify(RuleContext ctx, UNode l) {
        UNode cond = l.field("cond"), iter = l.field("iter"), body = l.field("body");
        // literal bound: i < 10, range(5), for x in [1, 2, 3]
        if (iter != null && (iter.is(Kind.LIST) && !iter.flag("comprehension")
            || iter.is(Kind.CALL) && "range".equals(iter.name()) && !iter.list("args").isEmpty() && iter.list("args").stream().allMatch(a -> a.is(Kind.NUMBER)))) return "constant";
        UNode init = l.field("init");
        if ("for".equals(l.attr("loop")) && init != null && cond != null && cond.is(Kind.BINARY)
            && Set.of("<", "<=", ">", ">=", "!=").contains(cond.op())) {
            UNode a = cond.field("left"), b = cond.field("right");
            if (a != null && b != null && (a.is(Kind.IDENT) && b.is(Kind.NUMBER) || b.is(Kind.IDENT) && a.is(Kind.NUMBER))) {
                UNode var = a.is(Kind.IDENT) ? a : b, lit = a.is(Kind.NUMBER) ? a : b;
                boolean initConst = init.find(Kind.IDENT).stream().allMatch(x -> var.name().equals(x.name()));
                if (initConst && Long.parseLong(digits(lit.text())) <= 10_000) return "constant";
            }
        }
        // logarithmic: a condition variable is halved/doubled, directly or via a midpoint
        Set<String> condVars = new java.util.HashSet<>();
        if (cond != null) for (UNode id : cond.find(Kind.IDENT)) condVars.add(id.name());
        if (condVars.isEmpty()) return "linear";
        List<UNode> scopeParts = new ArrayList<>();
        if (body != null) scopeParts.add(body);
        if (l.field("update") != null) scopeParts.add(l.field("update"));
        Set<String> halves = new java.util.HashSet<>();
        for (UNode part : scopeParts) {
            for (UNode n : part.find(x -> x.is(Kind.ASSIGN) || x.is(Kind.DECL))) {
                String target = n.is(Kind.DECL) ? n.name() : n.field("target") != null ? n.field("target").name() : null;
                UNode v = n.field("value");
                if (target == null) continue;
                boolean compound = n.is(Kind.ASSIGN) && Set.of("/=", ">>=", "*=", "<<=", "//=").contains(n.op()) && v != null && v.is(Kind.NUMBER)
                    && Set.of("2", "1").contains(v.text());
                boolean halving = v != null && isHalving(v);
                if (compound && condVars.contains(target) || halving && condVars.contains(target)) return "log";
                if (halving) halves.add(target);
            }
        }
        if (!halves.isEmpty()) {
            for (UNode part : scopeParts) {
                for (UNode n : part.find(Kind.ASSIGN)) {
                    UNode t = n.field("target"), v = n.field("value");
                    if (t != null && v != null && condVars.contains(t.name()) && v.any(x -> x.is(Kind.IDENT) && halves.contains(x.name()))) return "log";
                }
            }
        }
        return "linear";
    }

    private static String digits(String s) {
        String d = s.replaceAll("[^0-9]", "");
        return d.isEmpty() || d.length() > 12 ? "999999999999" : d;
    }

    static boolean isHalving(UNode v) {
        return v.any(x -> x.is(Kind.BINARY) && x.field("right") != null && x.field("right").is(Kind.NUMBER)
            && ("/".equals(x.op()) || "//".equals(x.op())) && "2".equals(x.field("right").text())
            || x.is(Kind.BINARY) && ">>".equals(x.op()) && x.field("right") != null && "1".equals(x.field("right").text())
            || x.is(Kind.BINARY) && "*".equals(x.op()) && x.field("right") != null && "2".equals(x.field("right").text()));
    }

    private static boolean isSort(UNode c) {
        String n = c.name(), m = c.attr("method");
        return n != null && (Set.of("sorted", "qsort", "Arrays.sort", "Collections.sort", "std::sort", "std::stable_sort", "sort.Ints",
            "sort.Strings", "sort.Slice", "sort.SliceStable", "slices.Sort", "sort").contains(n) || "sort".equals(m) && c.field("receiver") != null);
    }

    /** Calls to {@code fn} itself inside its body (not inside nested functions). */
    static List<UNode> selfCalls(UNode fn) {
        List<UNode> out = new ArrayList<>();
        String name = fn.name();
        fn.walk(n -> {
            if (!n.is(Kind.CALL) || n.ancestor(Kind.FUNCTION, Kind.LAMBDA) != fn) return;
            String callee = n.name();
            if (callee == null) return;
            if (callee.equals(name) || callee.equals("this." + name) || callee.equals("self." + name)) out.add(n);
        });
        return out;
    }

    /** Maximum recursive calls that can execute in one invocation: alternatives of a branch don't add up. */
    static int pathCalls(UNode n, UNode fn, List<UNode> calls) {
        if (n != fn && (n.is(Kind.FUNCTION) || n.is(Kind.LAMBDA))) return 0;
        int self = calls.contains(n) ? 1 : 0;
        if (n.is(Kind.IF)) {
            UNode c = n.field("cond"), t = n.field("then"), e = n.field("else");
            return self + (c == null ? 0 : pathCalls(c, fn, calls)) + Math.max(t == null ? 0 : pathCalls(t, fn, calls), e == null ? 0 : pathCalls(e, fn, calls));
        }
        if (n.is(Kind.SWITCH)) {
            int max = 0;
            for (UNode c : n.children()) max = Math.max(max, pathCalls(c, fn, calls));
            return self + max;
        }
        return self + sequence(n.children(), 0, fn, calls);
    }

    /**
     * Calls along a statement sequence. An {@code if} without {@code else} whose body always returns splits the
     * path: either its body runs, or the statements after it do.
     */
    private static int sequence(List<UNode> stmts, int from, UNode fn, List<UNode> calls) {
        int sum = 0;
        for (int i = from; i < stmts.size(); i++) {
            UNode s = stmts.get(i);
            if (s.is(Kind.IF) && s.field("else") == null && s.field("then") != null && alwaysExits(s.field("then"))) {
                UNode c = s.field("cond");
                return sum + (c == null ? 0 : pathCalls(c, fn, calls))
                    + Math.max(pathCalls(s.field("then"), fn, calls), sequence(stmts, i + 1, fn, calls));
            }
            sum += pathCalls(s, fn, calls);
        }
        return sum;
    }

    private static boolean alwaysExits(UNode s) {
        if (s.is(Kind.RETURN) || s.is(Kind.THROW)) return true;
        List<UNode> body = s.children().stream().filter(c -> !c.is(Kind.COMMENT)).toList();
        return (s.is(Kind.BLOCK) || s.type().equals("block")) && !body.isEmpty() && alwaysExits(body.get(body.size() - 1));
    }

    static boolean memoized(UNode fn) {
        String deco = fn.attr("decorators");
        if (deco != null && deco.contains("cache")) return true;
        return fn.any(n -> (n.is(Kind.IDENT) || n.is(Kind.MEMBER)) && n.name() != null
            && (n.name().toLowerCase().contains("memo") || n.name().toLowerCase().contains("cache") || MEMO_NAMES.contains(n.name())));
    }

    static boolean halvesArgument(UNode call) {
        return call.list("args").stream().anyMatch(a -> isHalving(a) || a.any(x -> x.is(Kind.IDENT) && x.name() != null && x.name().matches("mid|middle|m"))
            || a.is(Kind.INDEX) && a.field("index") != null && a.field("index").text().contains(":"));
    }

    /** Allocation sites whose size can depend on the input. */
    static List<UNode> allocations(RuleContext ctx) {
        List<UNode> out = new ArrayList<>();
        ctx.root().walk(n -> {
            boolean alloc = switch (ctx.lang()) {
                case "python" -> n.is(Kind.LIST) || n.is(Kind.MAP) || n.is(Kind.SET)
                    || n.is(Kind.CALL) && Set.of("list", "dict", "set", "deque", "defaultdict", "Counter", "bytearray").contains(n.name());
                case "javascript" -> (n.is(Kind.LIST) || n.is(Kind.MAP)) && n.parent() != null && (n.parent().is(Kind.DECL) || n.parent().is(Kind.ASSIGN))
                    || n.is(Kind.NEW) && String.valueOf(n.name()).matches("Map|Set|Array|WeakMap")
                    || n.is(Kind.CALL) && Set.of("map", "filter", "slice", "concat").contains(n.attr("method")) && n.field("receiver") != null;
                case "java" -> n.is(Kind.NEW) && (n.flag("array") || String.valueOf(n.name()).matches("ArrayList|HashMap|HashSet|LinkedList|ArrayDeque|StringBuilder|TreeMap|TreeSet|PriorityQueue|LinkedHashMap|LinkedHashSet"));
                case "cpp" -> n.is(Kind.DECL) && n.attr("type") != null && n.attr("type").matches("(const\\s+)?(std::)?(vector|unordered_map|unordered_set|map|set|deque|queue|stack|priority_queue)\\s*<.*")
                    || n.is(Kind.NEW) && n.text().contains("[")
                    || n.is(Kind.CALL) && Set.of("malloc", "calloc", "realloc").contains(n.name());
                case "c" -> n.is(Kind.CALL) && Set.of("malloc", "calloc", "realloc").contains(n.name()) || n.is(Kind.DECL) && n.flag("vla");
                case "go" -> n.is(Kind.CALL) && "make".equals(n.name()) || n.is(Kind.LIST) || n.is(Kind.MAP);
                default -> false;
            };
            if (alloc) out.add(n);
        });
        return out;
    }

    static boolean twoDimensional(RuleContext ctx, UNode n) {
        switch (ctx.lang()) {
            case "python", "javascript", "go" -> {
                for (UNode c : n.children()) {
                    UNode x = c.is(Kind.BINARY) && "*".equals(c.op()) && c.field("left") != null ? c.field("left") : c;
                    if (x.is(Kind.LIST) || x.type().endsWith("slice_type") && n.text().startsWith("[][]")) return true;
                }
                return ctx.lang("go") && n.is(Kind.CALL) && n.text().replaceAll("\\s", "").startsWith("make([][]");
            }
            case "java" -> { return n.is(Kind.NEW) && n.flag("array") && Integer.parseInt(n.attr("dims") == null ? "1" : n.attr("dims")) >= 2; }
            case "cpp" -> { return n.attr("type") != null && n.attr("type").replaceAll("\\s", "").matches(".*vector<(std::)?vector<.*"); }
            default -> { return false; }
        }
    }

    private Finding nestedLoopFinding(RuleContext ctx, List<UNode> chain, Map<UNode, String> cls, int depth) {
        List<UNode> linear = chain.stream().filter(x -> "linear".equals(cls.get(x))).toList();
        UNode outer = linear.get(0), inner = linear.get(linear.size() - 1);
        Finding f = new Finding();
        f.id = f.ruleId = "time.nested-loop";
        f.cat = "time";
        f.sev = depth >= 3 ? "critical" : "high";
        f.noPenalty = true;
        f.title = "Nested loops: estimated " + bigO(depth, 0) + " time";
        f.line = outer.line();
        f.end = outer.endLine();
        f.col = outer.span().startCol();
        f.original = clip(ctx.src().lines(f.line, f.end));
        f.explain = "The loop on line " + inner.line() + " runs fully for every iteration of the loop on line " + outer.line() + ".";
        f.why = "Doubling the input roughly " + (depth >= 3 ? "multiplies the work by eight" : "quadruples the work") + ". At n = 100,000, that's about "
            + (depth >= 3 ? "10¹⁵" : "10¹⁰") + " steps, well past typical time limits of about 10⁸ simple operations per second.";
        f.template(Templates.HASH_LOOKUP.get(ctx.lang()));
        f.changes = "If the inner loop is searching for a matching value, remember what you've seen in a hash set or map and look it up in O(1) instead.";
        f.benefits = "One pass over the data instead of n passes.";
        f.tradeoffs = "This template only applies when the inner loop is a lookup. Sorting + two pointers (O(n log n), O(1) extra space) is the alternative when memory matters. Some problems are genuinely O(n²).";
        f.assumptions = "Assumes the inner loop only looks for a value related to the outer element, and that both loop bounds grow with the input.";
        f.complexity(bigO(depth, 0), "O(1)", "O(n)", "O(n)");
        f.ast = Map.of("path", inner.path(), "nodeType", inner.type(), "kind", "LOOP");
        f.node = outer;
        return f;
    }

    private Finding branchingFinding(RuleContext ctx, UNode fn) {
        Finding f = new Finding();
        f.id = f.ruleId = "time.branching-recursion";
        f.cat = "time";
        f.sev = "high";
        f.noPenalty = true;
        f.title = "Branching recursion without memoisation";
        f.line = fn.line();
        f.end = fn.line();
        f.col = fn.span().startCol();
        f.original = ctx.src().line(fn.line());
        f.explain = fn.name() + "() makes two or more recursive calls, and nothing caches results.";
        f.why = "The same sub-problems are recomputed many times, which can grow exponentially. Naive Fibonacci makes about 1.6ⁿ calls.";
        f.template(Templates.MEMO.get(ctx.lang()));
        f.changes = "Cache each sub-result the first time it's computed (memoisation), or build bottom-up (tabulation).";
        f.benefits = "Each distinct sub-problem is solved once.";
        f.tradeoffs = "Uses memory for the cache. Only helps when sub-problems actually repeat.";
        f.assumptions = "Assumes the function is pure, so the same arguments give the same result.";
        f.complexity("O(2ⁿ) (upper bound)", "O(n) stack", "O(n · states)", "O(states)");
        f.confidence("medium");
        Map<String, String> ast = new LinkedHashMap<>();
        ast.put("path", fn.path());
        ast.put("nodeType", fn.type());
        ast.put("kind", "FUNCTION");
        f.ast = ast;
        f.node = fn;
        return f;
    }

    private static String clip(String s) {
        String[] ls = s.split("\n", -1);
        return ls.length <= 30 ? s : String.join("\n", java.util.Arrays.copyOf(ls, 30)) + "\n…";
    }
}
