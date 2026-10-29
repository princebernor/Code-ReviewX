package com.codereviewx.ast.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Assigns stable paths such as {@code Function[twoSum] > For[0] > For[0] > If[0]}. Structural nodes are numbered
 * per label among the structural nodes directly inside the same enclosing structural node; other nodes get the
 * enclosing path plus their own label, e.g. {@code Function[main] > For[0] > Call[strlen]}.
 */
public final class AstPaths {
    private AstPaths() {}

    public static void assign(UNode root) {
        root.path("Module");
        visit(root, "", new HashMap<>());
    }

    private static void visit(UNode n, String scope, Map<String, Integer> counters) {
        for (UNode c : n.children()) {
            if (c.kind().structural()) {
                String label = label(c);
                String seg = c.kind() == Kind.FUNCTION || c.kind() == Kind.CLASS
                    ? label + "[" + (c.name() == null ? "<anonymous>" : c.name()) + "]"
                    : label + "[" + (counters.merge(label, 1, Integer::sum) - 1) + "]";
                String p = scope.isEmpty() ? seg : scope + " > " + seg;
                c.path(p);
                visit(c, p, new HashMap<>());
            } else {
                String own = c.name() != null && (c.kind() == Kind.CALL || c.kind() == Kind.DECL || c.kind() == Kind.NEW)
                    ? pretty(c.kind()) + "[" + c.name() + "]" : pretty(c.kind());
                c.path(scope.isEmpty() ? "Module > " + own : scope + " > " + own);
                visit(c, scope, counters);
            }
        }
    }

    static String label(UNode n) {
        if (n.kind() == Kind.LOOP) {
            String l = n.attr("loop");
            return l == null ? "Loop" : switch (l) {
                case "for" -> "For";
                case "while" -> "While";
                case "do" -> "DoWhile";
                case "foreach" -> "ForEach";
                default -> "Loop";
            };
        }
        return pretty(n.kind());
    }

    static String pretty(Kind k) {
        String s = k.name().toLowerCase().replace("_", "");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
