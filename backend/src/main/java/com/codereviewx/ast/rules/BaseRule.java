package com.codereviewx.ast.rules;

import java.util.Set;

/** Convenience base: metadata via a fluent builder, check() supplied by subclasses or lambdas. */
public abstract class BaseRule implements Rule {
    private final Meta meta;

    protected BaseRule(Meta meta) { this.meta = meta; }

    @Override
    public Meta meta() { return meta; }

    public static Builder rule(String id, String category, String severity) { return new Builder(id, category, severity); }

    /** Functional body for compact rule definitions. */
    @FunctionalInterface
    public interface Check {
        void run(RuleContext ctx, Rule self);
    }

    public static final class Builder {
        private final String id, category, severity;
        private Set<String> langs = Set.of("*");
        private String title, explain, why, changes, benefits, tradeoffs = "None.";
        private int max = 99;

        Builder(String id, String category, String severity) {
            this.id = id;
            this.category = category;
            this.severity = severity;
        }

        public Builder langs(String... l) { langs = Set.of(l); return this; }
        public Builder title(String s) { title = s; return this; }
        public Builder explain(String s) { explain = s; return this; }
        public Builder why(String s) { why = s; return this; }
        public Builder changes(String s) { changes = s; return this; }
        public Builder benefits(String s) { benefits = s; return this; }
        public Builder tradeoffs(String s) { tradeoffs = s; return this; }
        public Builder max(int m) { max = m; return this; }

        public Rule check(Check c) {
            Meta m = new Meta(id, category, severity, langs, title, explain, why, changes, benefits, tradeoffs, max);
            return new BaseRule(m) {
                @Override
                public void check(RuleContext ctx) { c.run(ctx, this); }
            };
        }
    }
}
