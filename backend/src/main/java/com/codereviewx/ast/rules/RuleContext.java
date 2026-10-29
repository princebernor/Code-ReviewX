package com.codereviewx.ast.rules;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.codereviewx.ast.dataflow.DataFlow;
import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.ParseResult;
import com.codereviewx.ast.model.SourceText;
import com.codereviewx.ast.model.UNode;

/** Everything a rule needs: the tree, indexes, data flow, and the finding sink (with per-line de-duplication and caps). */
public final class RuleContext {
    private static final int MAX_ORIGINAL_LINES = 30;

    private final String lang;
    private final ParseResult parse;
    private final DataFlow dataFlow;
    private final Map<Kind, List<UNode>> byKind = new EnumMap<>(Kind.class);
    private final List<Finding> findings = new ArrayList<>();
    private final Map<String, Integer> perRule = new HashMap<>();
    private final Set<String> seen = new HashSet<>();

    public RuleContext(ParseResult parse) {
        this.lang = parse.language();
        this.parse = parse;
        this.dataFlow = new DataFlow(lang);
        parse.root().walk(n -> byKind.computeIfAbsent(n.kind(), k -> new ArrayList<>()).add(n));
    }

    public String lang() { return lang; }
    public boolean lang(String... ids) { for (String i : ids) if (i.equals(lang)) return true; return false; }
    public UNode root() { return parse.root(); }
    public SourceText src() { return parse.source(); }
    public DataFlow dataFlow() { return dataFlow; }
    public List<Finding> findings() { return findings; }

    public List<UNode> all(Kind k) { return byKind.getOrDefault(k, List.of()); }

    /** FUNCTION and LAMBDA nodes. */
    public List<UNode> functions() {
        List<UNode> out = new ArrayList<>(all(Kind.FUNCTION));
        out.addAll(all(Kind.LAMBDA));
        return out;
    }

    public UNode scopeOf(UNode n) { return DataFlow.scopeOf(n); }

    /**
     * True when {@code n} runs once per iteration of an enclosing loop of the same function: it sits in the loop's
     * body, condition or update (not in its initializer or iterable, which run once).
     */
    public boolean inLoop(UNode n) { return loopDepth(n) > 0; }

    /** Number of enclosing loops (same function) whose repeated parts contain {@code n}. */
    public int loopDepth(UNode n) {
        int d = 0;
        UNode scope = scopeOf(n);
        for (UNode c = n, p = n.parent(); p != null && p != scope; c = p, p = p.parent()) {
            if (p.is(Kind.LOOP) && c != p.field("init") && c != p.field("iter")) d++;
        }
        return d;
    }

    public List<UNode> enclosingLoops(UNode n) {
        List<UNode> out = new ArrayList<>();
        UNode scope = scopeOf(n);
        for (UNode c = n, p = n.parent(); p != null && p != scope; c = p, p = p.parent()) {
            if (p.is(Kind.LOOP) && c != p.field("init") && c != p.field("iter")) out.add(p);
        }
        return out;
    }

    /** Declared type text of {@code name} visible at {@code at}: local DECL/PARAM, then class fields, then globals. */
    public String typeOf(String name, UNode at) {
        if (name == null) return null;
        UNode scope = scopeOf(at);
        for (UNode d : DataFlow.defs(name, scope)) if (d.attr("type") != null) return d.attr("type");
        for (UNode d : all(Kind.DECL)) if (name.equals(d.name()) && (d.flag("field") || scopeOf(d).parent() == null) && d.attr("type") != null) return d.attr("type");
        return null;
    }

    /** The value most recently assigned to {@code name} before {@code at} in the same scope, if any. */
    public UNode valueOf(String name, UNode at) {
        UNode best = null;
        for (UNode d : DataFlow.defs(name, scopeOf(at))) {
            if (d.span().start() > at.span().start()) break;
            UNode v = d.field("value");
            if (d.is(Kind.ASSIGN) && !d.list("targets").isEmpty()) {
                int i = -1;
                for (int k = 0; k < d.list("targets").size(); k++) if (name.equals(d.list("targets").get(k).name())) i = k;
                v = i >= 0 && i < d.list("values").size() ? d.list("values").get(i) : null;
            }
            if (d.is(Kind.PARAM)) v = null;
            best = v != null ? v : best;
        }
        return best;
    }

    public String commentPrefix() { return lang.equals("python") ? "# " : "// "; }

    /** Reports a finding at {@code at} unless this rule already reported that line or hit its cap. */
    public Finding report(Rule rule, UNode at, Consumer<Finding> customize) {
        return report(rule, at, at, customize);
    }

    public Finding report(Rule rule, UNode at, UNode endNode, Consumer<Finding> customize) {
        Rule.Meta m = rule.meta();
        int line = at.line();
        if (!seen.add(m.id() + "@" + line)) return null;
        int used = perRule.getOrDefault(m.id(), 0);
        if (used >= m.max()) return null;
        perRule.put(m.id(), used + 1);
        Finding f = new Finding();
        f.id = m.id();
        f.ruleId = m.id();
        f.cat = m.category();
        f.sev = m.severity();
        f.title = m.title();
        f.explain = m.explain();
        f.why = m.why();
        f.changes = m.changes();
        f.benefits = m.benefits();
        f.tradeoffs = m.tradeoffs();
        f.line = line;
        f.end = Math.max(line, endNode.endLine());
        f.col = at.span().startCol();
        f.endCol = endNode.span().endCol();
        f.offset = at.span().start();
        f.node = at;
        f.original = clip(src().lines(f.line, f.end));
        Map<String, String> ast = new LinkedHashMap<>();
        ast.put("path", at.path());
        ast.put("nodeType", at.type());
        ast.put("kind", at.kind().name());
        f.ast = ast;
        f.note(commentPrefix() + m.changes());
        if (customize != null) customize.accept(f);
        findings.add(f);
        return f;
    }

    /** Fills original/suggested from span edits. */
    public void applyEdits(Finding f, List<Edit> edits) {
        f.original = clip(Edit.originalLines(src(), edits));
        f.suggestLine(Edit.applyToLines(src(), edits));
        f.end = Math.max(f.end, f.line + (int) f.original.chars().filter(c -> c == '\n').count());
    }

    static String clip(String s) {
        String[] ls = s.split("\n", -1);
        if (ls.length <= MAX_ORIGINAL_LINES) return s;
        return String.join("\n", java.util.Arrays.copyOf(ls, MAX_ORIGINAL_LINES)) + "\n…";
    }
}
