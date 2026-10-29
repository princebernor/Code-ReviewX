package com.codereviewx.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codereviewx.ast.rules.Finding;

/**
 * Exact port of the scoring block in js/review-engine.js (analyze(), "4) scores"). Category ids, weights,
 * penalties, base tables, clamps and rounding are identical, so the same findings give the same numbers.
 */
public final class Scorer {
    public record Category(String id, String label, double weight) {}

    public static final List<Category> CATEGORIES = List.of(
        new Category("correctness", "Logic & correctness", 0.22),
        new Category("security", "Security", 0.15),
        new Category("time", "Time complexity", 0.15),
        new Category("quality", "Code quality", 0.14),
        new Category("readability", "Readability & maintainability", 0.14),
        new Category("practices", "Best-practice compliance", 0.12),
        new Category("space", "Space complexity", 0.08));
    public static final List<String> SEVERITIES = List.of("critical", "high", "medium", "low", "info");
    public static final Map<String, Integer> PENALTY = Map.of("critical", 32, "high", 20, "medium", 11, "low", 5, "info", 2);
    static final int[] TIME_BASE = {100, 99, 95, 90, 68, 46, 32};
    static final int[] EFF_BASE = {97, 95, 88, 82, 58, 38, 20};
    static final int[] SPACE_BASE = {100, 92, 76};

    /** {@code categories}: id → {score, n}; {@code weighted} is reported rounded, as the UI expects. */
    public record Score(Map<String, Map<String, Integer>> categories, Map<String, Integer> counts, double weightedRaw,
                        int weighted, int overall, int efficiency) {}

    public static Score score(List<Finding> findings, int timeRank, int spaceRank) {
        Map<String, double[]> cats = new LinkedHashMap<>(); // [score, n]
        for (Category c : CATEGORIES) cats.put(c.id(), new double[] {100, 0});
        cats.get("time")[0] = TIME_BASE[timeRank];
        cats.get("space")[0] = SPACE_BASE[spaceRank];
        for (Finding f : findings) {
            double[] c = cats.get(f.cat);
            c[1]++;
            if (!Boolean.TRUE.equals(f.noPenalty)) c[0] -= PENALTY.get(f.sev);
        }
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (Category c : CATEGORIES) {
            double[] v = cats.get(c.id());
            int s = (int) Math.max(10, Math.min(100, jsRound(v[0])));
            v[0] = s;
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("score", s);
            m.put("n", (int) v[1]);
            out.put(c.id(), m);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : SEVERITIES) counts.put(s, 0);
        for (Finding f : findings) counts.merge(f.sev, 1, Integer::sum);
        double weighted = 0;
        for (Category c : CATEGORIES) weighted += cats.get(c.id())[0] * c.weight();
        int overall = (int) Math.max(10, jsRound(weighted - 8 * counts.get("critical") - 3 * counts.get("high")));
        long timePenalised = findings.stream().filter(f -> "time".equals(f.cat) && !Boolean.TRUE.equals(f.noPenalty)).count();
        int effPenalty = (int) timePenalised * 3 + (spaceRank == 2 ? 4 : 0);
        int efficiency = Math.max(5, Math.min(99, EFF_BASE[timeRank] - effPenalty));
        return new Score(out, counts, weighted, (int) jsRound(weighted), overall, efficiency);
    }

    /** JavaScript Math.round: halves round toward +∞. */
    static long jsRound(double x) { return (long) Math.floor(x + 0.5); }
}
