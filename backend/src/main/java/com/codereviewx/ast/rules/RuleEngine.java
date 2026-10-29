package com.codereviewx.ast.rules;

import java.util.ArrayList;
import java.util.List;

import com.codereviewx.ast.model.Diagnostic;
import com.codereviewx.ast.model.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs a language's rules over a parsed tree. A failing rule is reported as a diagnostic, never as a crash. */
public final class RuleEngine {
    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    public record Result(List<Finding> findings, List<Diagnostic> diagnostics, RuleContext context) {}

    public Result run(ParseResult parse, List<Rule> rules) {
        RuleContext ctx = new RuleContext(parse);
        List<Diagnostic> diags = new ArrayList<>();
        for (Rule r : rules) {
            try {
                r.check(ctx);
            } catch (RuntimeException | StackOverflowError e) {
                log.warn("Rule {} failed on {} input", r.meta().id(), parse.language(), e);
                diags.add(Diagnostic.info("Rule " + r.meta().id() + " could not run on this input and was skipped."));
            }
        }
        return new Result(ctx.findings(), diags, ctx);
    }
}
