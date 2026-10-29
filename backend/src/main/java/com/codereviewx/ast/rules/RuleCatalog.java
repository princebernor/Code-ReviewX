package com.codereviewx.ast.rules;

import java.util.ArrayList;
import java.util.List;

/** Every rule, in a fixed order so results are deterministic. */
public final class RuleCatalog {
    private static final List<Rule> ALL;

    static {
        List<Rule> all = new ArrayList<>();
        all.addAll(SecurityRules.all());
        all.addAll(CorrectnessRules.all());
        all.addAll(PerformanceRules.all());
        all.addAll(QualityRules.all());
        all.addAll(PracticeRules.all());
        ALL = List.copyOf(all);
    }

    private RuleCatalog() {}

    public static List<Rule> all() { return ALL; }

    public static List<Rule> forLanguage(String lang) {
        return ALL.stream().filter(r -> r.meta().appliesTo(lang)).toList();
    }
}
