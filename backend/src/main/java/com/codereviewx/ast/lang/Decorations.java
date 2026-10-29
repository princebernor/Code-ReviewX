package com.codereviewx.ast.lang;

import java.util.ArrayList;
import java.util.List;

import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;

/** Fills the normalized roles documented on {@link UNode} from the native tree-sitter field names. */
final class Decorations {
    private Decorations() {}

    static void generic(UNode u, boolean conditionParens) {
        switch (u.kind()) {
            case FUNCTION, LAMBDA -> function(u);
            case CLASS -> {
                UNode n = u.field("name");
                if (u.name() == null && n != null) u.name(n.text());
            }
            case PARAM -> {
                if (u.name() == null) u.name(nameOf(u));
                u.field("default", first(u, "value", "default_value", "right"));
                UNode t = u.field("type");
                if (t != null) u.attr("type", t.text());
            }
            case DECL -> {
                if (u.name() == null) u.name(nameOf(u));
                u.field("value", first(u, "value", "right"));
                UNode t = u.field("type");
                if (t != null) u.attr("type", t.text());
            }
            case ASSIGN -> {
                u.field("target", first(u, "left"));
                u.field("value", first(u, "right"));
                if (u.op() == null) u.op("=");
            }
            case LOOP -> {
                u.field("cond", first(u, "condition"));
                u.field("init", first(u, "initializer", "init"));
                u.field("update", first(u, "update", "increment"));
                if (conditionParens) stripParens(u, "cond");
            }
            case IF -> {
                u.field("cond", first(u, "condition"));
                u.field("then", first(u, "consequence"));
                u.field("else", first(u, "alternative"));
                if (conditionParens) stripParens(u, "cond");
            }
            case CALL, NEW -> {
                UNode callee = first(u, "function", "constructor", "type");
                if (callee != null) {
                    u.field("callee", callee);
                    u.name(compact(callee.text()));
                    if (callee.is(Kind.MEMBER)) u.field("receiver", callee.field("object"));
                }
                if (u.name() != null) u.attr("method", lastSegment(u.name()));
                UNode args = u.field("arguments");
                List<UNode> list = new ArrayList<>();
                if (args != null) for (UNode a : args.children()) if (!a.is(Kind.COMMENT)) list.add(a);
                u.list("args", list);
            }
            case MEMBER -> {
                u.field("object", first(u, "object", "argument", "operand", "value"));
                UNode p = first(u, "property", "field", "attribute");
                if (p != null) u.name(p.text());
            }
            case INDEX -> {
                u.field("object", first(u, "value", "argument", "object", "operand", "array"));
                u.field("index", first(u, "index", "subscript", "indices"));
            }
            case BINARY -> {
                List<UNode> named = u.children().stream().filter(c -> !c.is(Kind.COMMENT)).toList();
                u.field("left", u.field("left") != null ? u.field("left") : named.isEmpty() ? null : named.get(0));
                u.field("right", u.field("right") != null ? u.field("right") : named.size() < 2 ? null : named.get(named.size() - 1));
            }
            case UNARY -> {
                UNode o = first(u, "argument", "operand");
                u.field("operand", o != null ? o : u.children().isEmpty() ? null : u.children().get(0));
                if (u.op() == null && u.attr("keyword") != null) u.op(u.attr("keyword"));
            }
            case IDENT -> u.name(u.text());
            case NUMBER -> u.attr("value", u.text());
            case STRING -> {
                String t = u.text();
                u.attr("value", unquote(t));
                if (u.any(c -> c != u && (c.type().equals("interpolation") || c.type().equals("template_substitution")))) {
                    u.attr("interpolated", "true");
                }
            }
            default -> { }
        }
    }

    private static void function(UNode u) {
        if (u.name() == null) {
            UNode n = u.field("name");
            if (n != null) u.name(n.text());
            else {
                UNode d = innermost(u.field("declarator"));
                if (d != null) u.name(lastSegment(d.text()));
            }
        }
        UNode params = u.field("parameters");
        if (params == null) {
            // C/C++: the parameter list hangs off the function_declarator inside the declarator chain
            for (UNode d = u.field("declarator"); d != null && params == null; d = d.field("declarator")) params = d.field("parameters");
        }
        if (params == null && u.field("parameter") != null) params = u.field("parameter");
        List<UNode> ps = new ArrayList<>();
        if (params != null) {
            if (params.is(Kind.PARAM)) ps.add(params);
            else for (UNode c : params.children()) if (c.is(Kind.PARAM)) ps.add(c);
        }
        u.list("params", ps);
        u.field("body", u.field("body"));
    }

    static UNode first(UNode u, String... roles) {
        for (String r : roles) {
            UNode n = u.field(r);
            if (n != null) return n;
        }
        return null;
    }

    static void stripParens(UNode u, String role) {
        UNode c = u.field(role);
        if (c != null && c.is(Kind.PAREN) && !c.children().isEmpty()) u.field(role, c.children().get(0));
    }

    /** Name of a PARAM/DECL: the "name" field, else the innermost identifier of the declarator chain, else its own text. */
    static String nameOf(UNode u) {
        UNode n = u.field("name");
        if (n != null) return n.is(Kind.IDENT) || n.children().isEmpty() ? n.text() : innerIdentText(n);
        UNode d = innermost(u.field("declarator"));
        if (d != null) return lastSegment(d.text());
        if (u.children().isEmpty()) return u.text();
        UNode id = u.children().stream().filter(c -> c.is(Kind.IDENT)).findFirst().orElse(null);
        return id == null ? null : id.text();
    }

    private static String innerIdentText(UNode n) {
        List<UNode> ids = n.find(Kind.IDENT);
        return ids.isEmpty() ? n.text() : ids.get(0).text();
    }

    /** Follows "declarator" fields (pointer/array/reference/init declarators) down to the identifier. */
    static UNode innermost(UNode d) {
        while (d != null) {
            if (d.is(Kind.IDENT) || d.is(Kind.PARAM) && d.children().isEmpty()) return d;
            UNode next = d.field("declarator");
            if (next == null) {
                if (d.children().isEmpty() || d.type().endsWith("identifier") || d.type().equals("operator_name")) return d;
                UNode id = d.children().stream().filter(c -> c.is(Kind.IDENT)).findFirst().orElse(null);
                return id != null ? id : d;
            }
            d = next;
        }
        return null;
    }

    static String compact(String s) { return s.replaceAll("\\s+", ""); }

    static String lastSegment(String s) {
        int dot = s.lastIndexOf('.'), arrow = s.lastIndexOf("->"), scope = s.lastIndexOf("::");
        int cut = Math.max(dot >= 0 ? dot + 1 : 0, Math.max(arrow >= 0 ? arrow + 2 : 0, scope >= 0 ? scope + 2 : 0));
        return s.substring(cut);
    }

    static String unquote(String t) {
        String s = t;
        int q = 0;
        while (q < s.length() && Character.isLetter(s.charAt(q))) q++; // f"", r'', b"", u8"" prefixes
        s = s.substring(q);
        for (String d : new String[] {"\"\"\"", "'''", "\"", "'", "`"}) {
            if (s.length() >= 2 * d.length() && s.startsWith(d) && s.endsWith(d)) return s.substring(d.length(), s.length() - d.length());
        }
        return s;
    }
}
