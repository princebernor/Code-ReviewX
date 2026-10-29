package com.codereviewx.ast.fingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.UNode;

/**
 * Structural hashing over the unified AST. Identifiers and literal values are ignored (type-2 clones), and wrapper
 * kinds that differ between grammars (OTHER, PAREN, EXPR_STMT, COMMENT) are transparent, so equivalent code in
 * different languages produces overlapping hashes.
 */
public final class Fingerprints {
    private static final Set<Kind> TRANSPARENT = Set.of(Kind.OTHER, Kind.PAREN, Kind.EXPR_STMT);
    private static final int SHINGLE_DEPTH = 3;

    private Fingerprints() {}

    /** Structural hash of every node (post-order). */
    public static Map<UNode, Long> hashes(UNode root) {
        Map<UNode, Long> out = new IdentityHashMap<>();
        hash(root, out);
        return out;
    }

    private static long hash(UNode n, Map<UNode, Long> out) {
        long h = mix(n.kind().ordinal() + 1L);
        if (n.op() != null && (n.is(Kind.BINARY) || n.is(Kind.ASSIGN) || n.is(Kind.UNARY))) h = mix(h ^ n.op().hashCode());
        if (n.is(Kind.LOOP)) h = mix(h ^ String.valueOf(n.attr("loop")).hashCode());
        for (UNode c : n.children()) {
            if (c.is(Kind.COMMENT)) continue;
            h = mix(h * 31 + hash(c, out));
        }
        out.put(n, h);
        return h;
    }

    /** Hash of {@code n} with transparent wrappers removed, limited to {@code depth} levels. */
    private static long shape(UNode n, int depth) {
        if (TRANSPARENT.contains(n.kind()) && n.children().size() == 1) return shape(n.children().get(0), depth);
        long h = mix(n.kind().ordinal() + 1L);
        if (n.op() != null && (n.is(Kind.BINARY) || n.is(Kind.ASSIGN))) h = mix(h ^ n.op().replace("===", "==").hashCode());
        if (depth > 0) {
            for (UNode c : n.children()) {
                if (c.is(Kind.COMMENT) || c.is(Kind.IMPORT)) continue;
                if (TRANSPARENT.contains(c.kind()) && c.children().size() != 1) {
                    for (UNode g : c.children()) h = mix(h * 31 + shape(g, depth - 1));
                } else {
                    h = mix(h * 31 + shape(c, depth - 1));
                }
            }
        }
        return h;
    }

    public static String fingerprint(UNode root) { return Long.toHexString(hashes(root).get(root)); }

    /** Fingerprint per named function. */
    public static Map<String, String> functions(UNode root) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<UNode, Long> h = hashes(root);
        for (UNode f : root.find(Kind.FUNCTION)) out.putIfAbsent(f.name() == null ? "<anonymous>@" + f.line() : f.name(), Long.toHexString(h.get(f)));
        return out;
    }

    /** Multiset Jaccard similarity of depth-limited structural shingles, 0..1. */
    public static double similarity(UNode a, UNode b) {
        Map<Long, Integer> sa = shingles(a), sb = shingles(b);
        long inter = 0, union = 0;
        Set<Long> keys = new java.util.HashSet<>(sa.keySet());
        keys.addAll(sb.keySet());
        for (Long k : keys) {
            int x = sa.getOrDefault(k, 0), y = sb.getOrDefault(k, 0);
            inter += Math.min(x, y);
            union += Math.max(x, y);
        }
        return union == 0 ? 0 : (double) inter / union;
    }

    private static Map<Long, Integer> shingles(UNode root) {
        Map<Long, Integer> out = new HashMap<>();
        root.walk(n -> {
            if (TRANSPARENT.contains(n.kind()) || n.is(Kind.COMMENT) || n.is(Kind.IMPORT) || n.is(Kind.PROGRAM)) return;
            if (n.is(Kind.IDENT) || n.is(Kind.NUMBER) || n.is(Kind.STRING)) return; // leaves carry no structure
            out.merge(shape(n, SHINGLE_DEPTH), 1, Integer::sum);
        });
        return out;
    }

    /**
     * Maximal duplicated subtrees (statement blocks, loops, conditionals) of at least {@code minSize} nodes spanning
     * at least {@code minLines} lines. Each result is {@code [first occurrence, later occurrence]}.
     */
    public static List<UNode[]> duplicates(UNode root, int minSize, int minLines) {
        Map<UNode, Long> h = hashes(root);
        List<UNode> candidates = root.find(n -> (n.is(Kind.BLOCK) || n.is(Kind.LOOP) || n.is(Kind.IF))
            && n.endLine() - n.line() + 1 >= minLines && n.size() >= minSize);
        candidates.sort(Comparator.comparingInt(UNode::size).reversed());
        Map<Long, UNode> firstSeen = new HashMap<>();
        List<UNode> covered = new ArrayList<>();
        List<UNode[]> out = new ArrayList<>();
        List<UNode> byPosition = new ArrayList<>(candidates);
        byPosition.sort(Comparator.comparingInt(n -> n.span().start()));
        Map<Long, List<UNode>> groups = new LinkedHashMap<>();
        for (UNode n : byPosition) groups.computeIfAbsent(h.get(n), k -> new ArrayList<>()).add(n);
        for (UNode n : candidates) {
            List<UNode> g = groups.get(h.get(n));
            if (g.size() < 2 || covered.stream().anyMatch(n::isWithin)) continue;
            UNode first = g.get(0);
            if (first == n || n.isWithin(first) || first.isWithin(n)) continue;
            firstSeen.putIfAbsent(h.get(n), first);
            out.add(new UNode[] {first, n});
            covered.add(n);
            covered.add(first);
        }
        out.sort(Comparator.comparingInt(p -> p[1].span().start()));
        return out;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
