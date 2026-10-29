package com.codereviewx.review;

import java.util.ArrayList;
import java.util.List;

import com.codereviewx.ast.rules.Finding;

/**
 * Optional prose summary of a review ("explain": true). Scores and findings never depend on it. The only
 * implementation is deterministic; an LLM-backed implementation would plug in here, server-side, behind
 * crx.llm.enabled (disabled by default, and no LLM provider is implemented in this build).
 */
public interface ExplanationProvider {
    record ReviewContext(String lang, int overall, int efficiency, String time, String space, List<Finding> findings) {}

    record Explanation(String provider, String summary, List<String> priorities) {}

    Explanation explain(ReviewContext context);

    /** Builds the summary from the findings themselves. No external calls. */
    final class Deterministic implements ExplanationProvider {
        @Override
        public Explanation explain(ReviewContext c) {
            StringBuilder s = new StringBuilder();
            s.append("Overall ").append(c.overall()).append("/100 with an estimated ").append(c.time()).append(" time and ")
                .append(c.space()).append(" auxiliary space (estimates from the code's structure, not measurements). ");
            if (c.findings().isEmpty()) s.append("No rule matched; that is not proof the code is correct, so test edge cases.");
            else s.append(c.findings().size()).append(c.findings().size() == 1 ? " finding" : " findings")
                .append(", most severe first; fix the top ones before polishing style.");
            List<String> top = new ArrayList<>();
            for (Finding f : c.findings().subList(0, Math.min(3, c.findings().size()))) {
                top.add(f.sev.toUpperCase() + " · line " + f.line + " · " + f.title + ": " + f.changes);
            }
            return new Explanation("deterministic", s.toString(), top);
        }
    }
}
