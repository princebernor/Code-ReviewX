package com.codereviewx.ast.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A node of the unified AST.
 *
 * <p>Roles are normalized across languages so rules can be written once:
 * <ul>
 *   <li>FUNCTION/LAMBDA: name, list {@code params}, field {@code body}</li>
 *   <li>PARAM: name, attr {@code type}, field {@code default}</li>
 *   <li>DECL: name, attr {@code type}, field {@code value}</li>
 *   <li>ASSIGN: op, fields {@code target}, {@code value}; lists {@code targets}, {@code values} for tuples</li>
 *   <li>LOOP: attr {@code loop} (for|while|do|foreach|infinite), fields {@code init cond update body iter var}</li>
 *   <li>IF: fields {@code cond then else}</li>
 *   <li>CALL/NEW: name (callee text), attr {@code method} (last segment), field {@code callee}, {@code receiver}, list {@code args}</li>
 *   <li>MEMBER: name (property), field {@code object}; INDEX: fields {@code object index}</li>
 *   <li>BINARY: op, fields {@code left right}; UNARY: op, field {@code operand}</li>
 *   <li>STRING: attr {@code value}; attr {@code interpolated}=true with the embedded expressions as children</li>
 *   <li>CATCH: attr {@code type} (absent for a bare handler), field {@code body}</li>
 * </ul>
 */
public final class UNode {
    private final Kind kind;
    private final String type;
    private final SourceText src;
    private Span span;
    private String name;
    private String op;
    private UNode parent;
    private String path;
    private final List<UNode> children = new ArrayList<>();
    private Map<String, UNode> fields;
    private Map<String, List<UNode>> lists;
    private Map<String, String> attrs;

    public UNode(Kind kind, String type, Span span, SourceText src) {
        this.kind = kind;
        this.type = type;
        this.span = span;
        this.src = src;
    }

    public Kind kind() { return kind; }
    public boolean is(Kind k) { return kind == k; }
    public String type() { return type; }
    public Span span() { return span; }
    public void span(Span s) { this.span = s; }
    public String name() { return name; }
    public UNode name(String n) { this.name = n; return this; }
    public String op() { return op; }
    public UNode op(String o) { this.op = o; return this; }
    public UNode parent() { return parent; }
    public String path() { return path; }
    void path(String p) { this.path = p; }
    public SourceText source() { return src; }

    public int line() { return span.startLine(); }
    public int endLine() { return span.endLine(); }
    public String text() { return src.slice(span.start(), span.end()); }

    public List<UNode> children() { return Collections.unmodifiableList(children); }

    public UNode add(UNode child) {
        if (child != null) {
            child.parent = this;
            children.add(child);
        }
        return this;
    }

    public UNode field(String role) { return fields == null ? null : fields.get(role); }

    public UNode field(String role, UNode n) {
        if (n == null) return this;
        if (fields == null) fields = new HashMap<>(4);
        fields.put(role, n);
        return this;
    }

    public List<UNode> list(String role) {
        List<UNode> l = lists == null ? null : lists.get(role);
        return l == null ? List.of() : l;
    }

    public UNode list(String role, List<UNode> l) {
        if (lists == null) lists = new HashMap<>(2);
        lists.put(role, new ArrayList<>(l));
        return this;
    }

    public UNode addTo(String role, UNode n) {
        if (n == null) return this;
        if (lists == null) lists = new HashMap<>(2);
        lists.computeIfAbsent(role, k -> new ArrayList<>()).add(n);
        return this;
    }

    public String attr(String k) { return attrs == null ? null : attrs.get(k); }
    public boolean flag(String k) { return "true".equals(attr(k)); }

    public UNode attr(String k, String v) {
        if (v == null) return this;
        if (attrs == null) attrs = new HashMap<>(4);
        attrs.put(k, v);
        return this;
    }

    public Map<String, String> attrs() { return attrs == null ? Map.of() : Collections.unmodifiableMap(attrs); }

    /** Pre-order traversal including this node. */
    public void walk(Consumer<UNode> visitor) {
        visitor.accept(this);
        for (UNode c : children) c.walk(visitor);
    }

    public List<UNode> find(Kind... kinds) {
        EnumSet<Kind> set = EnumSet.noneOf(Kind.class);
        Collections.addAll(set, kinds);
        List<UNode> out = new ArrayList<>();
        walk(n -> { if (set.contains(n.kind)) out.add(n); });
        return out;
    }

    public List<UNode> find(Predicate<UNode> p) {
        List<UNode> out = new ArrayList<>();
        walk(n -> { if (p.test(n)) out.add(n); });
        return out;
    }

    public boolean any(Predicate<UNode> p) {
        if (p.test(this)) return true;
        for (UNode c : children) if (c.any(p)) return true;
        return false;
    }

    /** Nearest proper ancestor of one of the given kinds, or null. */
    public UNode ancestor(Kind... kinds) {
        for (UNode p = parent; p != null; p = p.parent) for (Kind k : kinds) if (p.kind == k) return p;
        return null;
    }

    public boolean isWithin(UNode other) {
        for (UNode p = this; p != null; p = p.parent) if (p == other) return true;
        return false;
    }

    /** Strips any number of enclosing parentheses. */
    public UNode unparen() {
        UNode n = this;
        while (n.kind == Kind.PAREN && !n.children.isEmpty()) n = n.children.get(0);
        return n;
    }

    public int size() {
        int[] n = {0};
        walk(x -> n[0]++);
        return n[0];
    }

    @Override
    public String toString() {
        return kind + (name != null ? "[" + name + "]" : "") + "@" + span.startLine() + ":" + span.startCol();
    }
}
