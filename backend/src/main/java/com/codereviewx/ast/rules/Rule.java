package com.codereviewx.ast.rules;

import java.util.Set;

/** A check over the unified AST. Implementations report through {@link RuleContext#report}. */
public interface Rule {
    Meta meta();

    void check(RuleContext ctx);

    /**
     * Rule metadata. {@code languages} contains language ids or "*". {@code max} caps findings per review (the
     * same caps the browser engine used, so scores stay comparable).
     */
    record Meta(String id, String category, String severity, Set<String> languages, String title, String explain,
                String why, String changes, String benefits, String tradeoffs, int max) {
        public boolean appliesTo(String lang) { return languages.contains("*") || languages.contains(lang); }
    }
}
