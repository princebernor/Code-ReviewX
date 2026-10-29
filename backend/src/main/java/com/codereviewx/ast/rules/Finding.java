package com.codereviewx.ast.rules;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One finding, serialized with the property names the existing review UI reads ({@code cat, sev, line, end,
 * original, suggested, kind, explain, why, changes, benefits, tradeoffs, before, after}) plus AST evidence.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Finding {
    public String uid;
    public String id;
    public String ruleId;
    public String cat;
    public String sev;
    public String title;
    public int line;
    public int end;
    public Integer col;
    public Integer endCol;
    public String original;
    public String suggested;
    /** line | template | note | rewrite — selects the "Suggested …" label in the UI. */
    public String kind = "note";
    public String explain;
    public String why;
    public String changes;
    public String benefits;
    public String tradeoffs;
    public String assumptions;
    public Map<String, String> before;
    public Map<String, String> after;
    /** Counted in category totals but not subtracted (the complexity class already sets the time score). */
    public Boolean noPenalty;
    /** high | medium | low */
    public String confidence = "high";
    /** ast | dataflow | text — what the finding is derived from. */
    public String source = "ast";
    public Map<String, String> ast;
    public boolean fixAvailable;
    /** Linear work hidden in a loop: raises the loop depth used for the time estimate by this much. */
    @JsonIgnore
    public int bump;
    @JsonIgnore
    public int offset;
    /** The node the finding was reported at (not serialized). */
    @JsonIgnore
    public transient com.codereviewx.ast.model.UNode node;

    public Finding suggestLine(String text) {
        this.suggested = text;
        this.kind = "line";
        this.fixAvailable = true;
        return this;
    }

    public Finding template(String text) {
        this.suggested = text;
        this.kind = "template";
        return this;
    }

    public Finding note(String text) {
        this.suggested = text;
        this.kind = "note";
        return this;
    }

    public Finding confidence(String c) {
        this.confidence = c;
        return this;
    }

    public Finding complexity(String beforeTime, String beforeSpace, String afterTime, String afterSpace) {
        this.before = Map.of("time", beforeTime, "space", beforeSpace);
        this.after = Map.of("time", afterTime, "space", afterSpace);
        return this;
    }
}
