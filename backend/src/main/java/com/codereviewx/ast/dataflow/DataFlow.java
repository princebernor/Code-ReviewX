package com.codereviewx.ast.dataflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;

/**
 * Lightweight, intra-procedural definition/use and taint tracking.
 *
 * <p>Limits (by design): one function at a time; no aliasing, field or container sensitivity; branches are merged
 * (a value tainted on any path counts as tainted); loops get a second pass; calls to user functions do not
 * propagate taint. A "tainted" verdict means "derived from a recognised input source", never "exploitable".
 */
public final class DataFlow {
    private static final Map<String, Set<String>> SOURCE_CALLS = Map.of(
        "python", Set.of("input", "raw_input", "sys.stdin.read", "sys.stdin.readline", "request.args.get", "request.form.get",
            "request.get_json", "request.values.get"),
        "javascript", Set.of("prompt", "readline", "req.get", "request.get"),
        "java", Set.of("next", "nextLine", "nextInt", "readLine", "getParameter", "getHeader", "getQueryString", "getInputStream"),
        "c", Set.of("getenv"),
        "cpp", Set.of("getenv"),
        "go", Set.of("FormValue", "PostFormValue", "Get", "Text", "ReadString", "Getenv"));
    private static final Set<String> SOURCE_MEMBERS = Set.of("sys.argv", "request.args", "request.form", "request.values",
        "request.json", "request.cookies", "request.headers", "req.query", "req.body", "req.params", "req.headers", "req.cookies",
        "request.query", "request.body", "request.params", "process.argv", "location.search", "location.hash", "location.href",
        "document.cookie", "os.Args", "r.URL", "r.Form", "r.PostForm");
    /** C/C++/Go calls that write input into one of their arguments. */
    private static final Set<String> FILLING_CALLS = Set.of("scanf", "fscanf", "sscanf", "gets", "fgets", "getline", "read",
        "recv", "fmt.Scan", "fmt.Scanln", "fmt.Scanf", "fmt.Sscan", "fmt.Fscan");

    private final String lang;
    private final Map<UNode, Map<String, Integer>> taintByScope = new IdentityHashMap<>();

    public DataFlow(String lang) { this.lang = lang; }

    /** True if {@code expr} contains an input source or a variable assigned from one earlier in its scope. */
    public boolean isTainted(UNode expr, UNode scope) {
        Map<String, Integer> tainted = taint(scope);
        return expr.any(n -> isSource(n) || n.is(Kind.IDENT) && tainted.containsKey(n.name())
            && tainted.get(n.name()) <= n.span().start());
    }

    /** True if {@code expr} contains a parameter of the enclosing function (possibly caller-controlled). */
    public boolean usesParameter(UNode expr, UNode scope) {
        if (scope == null || scope.list("params").isEmpty()) return false;
        Set<String> params = new java.util.HashSet<>();
        for (UNode p : scope.list("params")) if (p.name() != null) params.add(p.name());
        return expr.any(n -> n.is(Kind.IDENT) && params.contains(n.name()));
    }

    public boolean isSource(UNode n) {
        if (n.is(Kind.CALL)) {
            Set<String> calls = SOURCE_CALLS.getOrDefault(lang, Set.of());
            String name = n.name(), method = n.attr("method");
            if (name == null) return false;
            if (calls.contains(name)) return true;
            if (method != null && calls.contains(method) && (lang.equals("java") || lang.equals("go"))) {
                // Only receiver-qualified calls: scanner.nextLine(), r.FormValue(), q.Get() ...
                return !name.equals(method);
            }
            return false;
        }
        if (n.is(Kind.MEMBER)) return SOURCE_MEMBERS.contains(n.text().replaceAll("\\s+", ""));
        if (n.is(Kind.IDENT)) return "argv".equals(n.name()) && (lang.equals("c") || lang.equals("cpp"));
        return false;
    }

    /** Variables that receive input in {@code scope}, with the char offset from which they are tainted. */
    public Map<String, Integer> taint(UNode scope) {
        return taintByScope.computeIfAbsent(scope, s -> {
            Map<String, Integer> t = new HashMap<>();
            if (lang.equals("java") && "main".equals(s.name())) {
                for (UNode p : s.list("params")) if (p.name() != null) t.put(p.name(), 0);
            }
            List<UNode> defs = new ArrayList<>(s.find(n -> (n.is(Kind.DECL) || n.is(Kind.ASSIGN) || n.is(Kind.CALL)
                || n.is(Kind.BINARY) && ">>".equals(n.op())) && scopeOf(n) == s));
            defs.sort(Comparator.comparingInt(n -> n.span().start()));
            for (int pass = 0; pass < 2; pass++) {
                for (UNode d : defs) {
                    if (d.is(Kind.CALL)) {
                        if (FILLING_CALLS.contains(d.name())) {
                            for (UNode a : d.list("args")) for (UNode id : a.find(Kind.IDENT)) t.putIfAbsent(id.name(), d.span().end());
                        }
                        continue;
                    }
                    if (d.is(Kind.BINARY)) { // std::cin >> x
                        UNode l = d.field("left"), r = d.field("right");
                        if (l != null && r != null && l.text().replaceAll("\\s+", "").matches("(std::)?cin(>>.*)?")) {
                            for (UNode id : r.find(Kind.IDENT)) t.putIfAbsent(id.name(), d.span().end());
                        }
                        continue;
                    }
                    UNode value = d.field("value");
                    if (value == null) continue;
                    boolean bad = value.any(x -> isSource(x) || x.is(Kind.IDENT) && t.containsKey(x.name()));
                    if (!bad) continue;
                    if (d.is(Kind.DECL) && d.name() != null) t.putIfAbsent(d.name(), d.span().end());
                    List<UNode> targets = d.list("targets").isEmpty() && d.field("target") != null ? List.of(d.field("target")) : d.list("targets");
                    for (UNode tg : targets) if (tg.is(Kind.IDENT)) t.putIfAbsent(tg.name(), d.span().end());
                }
            }
            return t;
        });
    }

    /** Nearest function/lambda, else the program root. */
    public static UNode scopeOf(UNode n) {
        UNode f = n.ancestor(Kind.FUNCTION, Kind.LAMBDA);
        if (f != null) return f;
        UNode r = n;
        while (r.parent() != null) r = r.parent();
        return r;
    }

    /** Definitions (DECL, assignment targets, parameters) of {@code name} in {@code scope}, in source order. */
    public static List<UNode> defs(String name, UNode scope) {
        List<UNode> out = new ArrayList<>();
        for (UNode p : scope.list("params")) if (name.equals(p.name())) out.add(p);
        scope.walk(n -> {
            if (scopeOf(n) != scope && n != scope) return;
            if (n.is(Kind.DECL) && name.equals(n.name())) out.add(n);
            if (n.is(Kind.ASSIGN)) {
                List<UNode> ts = n.list("targets").isEmpty() && n.field("target") != null ? List.of(n.field("target")) : n.list("targets");
                for (UNode t : ts) if (t.is(Kind.IDENT) && name.equals(t.name())) out.add(n);
            }
        });
        out.sort(Comparator.comparingInt(n -> n.span().start()));
        return out;
    }

    /** Reads of {@code name} in {@code scope} (identifiers that are not the target of an assignment or declaration). */
    public static List<UNode> uses(String name, UNode scope) {
        List<UNode> out = new ArrayList<>();
        scope.walk(n -> {
            if (!n.is(Kind.IDENT) || !name.equals(n.name()) || scopeOf(n) != scope && scope.parent() != null) return;
            UNode p = n.parent();
            if (p != null && p.is(Kind.ASSIGN) && p.field("target") == n && "=".equals(p.op())) return;
            if (p != null && p.is(Kind.ASSIGN) && p.list("targets").contains(n) && ":=".equals(p.op())) return;
            if (p != null && (p.is(Kind.DECL) || p.is(Kind.PARAM)) && p.field("value") != n && p.field("default") != n) return;
            if (p != null && p.is(Kind.OTHER) && p.type().endsWith("_declarator")) return; // char *p = ...
            out.add(n);
        });
        return out;
    }
}
