package com.codereviewx.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codereviewx.ast.complexity.ComplexityAnalyzer;
import com.codereviewx.ast.fingerprint.Fingerprints;
import com.codereviewx.ast.lang.LanguageModule;
import com.codereviewx.ast.lang.LanguageRegistry;
import com.codereviewx.ast.model.Diagnostic;
import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.ParseResult;
import com.codereviewx.ast.rules.Finding;
import com.codereviewx.ast.rules.RuleEngine;
import com.codereviewx.config.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The analysis pipeline, independent of HTTP and storage: validate → parse → rules → complexity → score. The
 * returned map has the shape the existing review UI renders (see docs/frontend-baseline.md) plus AST evidence.
 * Submitted code is only parsed; it is never compiled, interpreted or executed.
 */
public final class ReviewEngine {
    public static final String ENGINE = "ast-v1";

    private final LanguageRegistry languages;
    private final InputValidator validator;
    private final RuleEngine rules = new RuleEngine();
    private final ComplexityAnalyzer complexity = new ComplexityAnalyzer();
    private final ExplanationProvider explainer;

    public ReviewEngine(LanguageRegistry languages, InputValidator validator, ExplanationProvider explainer) {
        this.languages = languages;
        this.validator = validator;
        this.explainer = explainer;
    }

    public LanguageModule module(String lang) {
        LanguageModule m = languages.get(lang).orElseThrow(() -> ApiException.badRequest("unsupported_language",
            "That language isn't supported. Choose C, C++, Java, Python, JavaScript or Go."));
        if (m.unavailableReason() != null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "parser_unavailable", "The " + m.label() + " parser isn't available on this server.");
        }
        return m;
    }

    public ParseResult parse(String code, String lang) {
        LanguageModule m = module(lang);
        return m.parse(validator.validate(code));
    }

    public Map<String, Object> review(String code, String lang, boolean explain) {
        LanguageModule m = module(lang);
        String src = validator.validate(code);
        ParseResult pr = m.parse(src);
        if (!pr.hasTree()) throw ApiException.badRequest("unparsable", "The code couldn't be parsed at all. Check the selected language.");

        RuleEngine.Result rr = rules.run(pr, m.rules());
        ComplexityAnalyzer.Result cx = complexity.analyze(rr.context(), rr.findings());
        List<Finding> findings = new ArrayList<>(syntaxFindings(pr));
        findings.addAll(rr.findings());
        findings.addAll(cx.findings());
        findings.sort(Comparator.comparingInt((Finding f) -> Scorer.SEVERITIES.indexOf(f.sev)).thenComparingInt(f -> f.line));
        for (int i = 0; i < findings.size(); i++) findings.get(i).uid = "f" + i;
        Scorer.Score score = Scorer.score(findings, cx.timeRank(), cx.spaceRank());

        String[] lines = src.split("\n", -1);
        long nonblank = java.util.Arrays.stream(lines).filter(l -> !l.strip().isEmpty()).count();
        List<Diagnostic> diags = new ArrayList<>(pr.diagnostics());
        diags.addAll(rr.diagnostics());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("engine", ENGINE);
        r.put("lang", lang);
        r.put("lines", lines.length);
        r.put("nonblank", nonblank);
        r.put("overall", score.overall());
        r.put("weighted", score.weighted());
        r.put("efficiency", score.efficiency());
        r.put("time", cx.time());
        r.put("space", cx.space());
        r.put("categories", score.categories());
        r.put("findings", findings);
        r.put("counts", score.counts());
        r.put("sampleId", null);
        r.put("guessed", LanguageHints.guess(src));
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("sort", rr.context().all(Kind.CALL).stream().anyMatch(c -> "sort".equals(c.attr("method")) || String.valueOf(c.name()).contains("sort")));
        notes.put("loopDepth", cx.linear() + cx.log());
        r.put("notes", notes);
        r.put("parser", pr.parser());
        r.put("partial", pr.partial());
        r.put("diagnostics", diags);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("time", cx.time());
        c.put("space", cx.space());
        c.put("confidence", cx.confidence());
        c.put("evidence", cx.evidence());
        c.put("note", "Estimated from the code's structure. Loop bounds are assumed to grow with the input unless they are literal constants; it is not a benchmark.");
        r.put("complexity", c);
        r.put("fingerprint", Fingerprints.fingerprint(pr.root()));
        if (explain) {
            r.put("explanation", explainer.explain(new ExplanationProvider.ReviewContext(lang, score.overall(), score.efficiency(), cx.time(), cx.space(), findings)));
        }
        return r;
    }

    /** One finding per line with a parser error (at most 3): code that doesn't parse doesn't run. */
    static List<Finding> syntaxFindings(ParseResult pr) {
        List<Finding> out = new ArrayList<>();
        java.util.Set<Integer> lines = new java.util.HashSet<>();
        for (Diagnostic d : pr.diagnostics()) {
            if (!"error".equals(d.severity()) || d.line() <= 0 || !lines.add(d.line()) || out.size() >= 3) continue;
            Finding f = new Finding();
            f.id = f.ruleId = "correctness.syntax-error";
            f.cat = "correctness";
            f.sev = "high";
            f.title = d.message().startsWith("Missing") ? "Syntax error: " + d.message() : "Syntax error";
            f.line = d.line();
            f.end = Math.max(d.line(), Math.min(d.endLine(), d.line() + 2));
            f.col = d.col();
            f.original = pr.source().lines(f.line, f.end);
            f.explain = "The " + pr.parser() + " parser couldn't read this code: " + d.message();
            f.why = "Code with a syntax error doesn't compile or run at all. Findings after this point come from a partially recovered tree, so they may be incomplete.";
            f.changes = "Fix the syntax at the marked position (unclosed brackets or quotes, a missing colon or semicolon, or a misspelt keyword are the usual causes).";
            f.benefits = "The code compiles/runs, and the rest of the review covers all of it.";
            f.tradeoffs = "None.";
            f.note(("python".equals(pr.language()) ? "# " : "// ") + "Fix the syntax here, then review again.");
            f.confidence = d.message().equals("Syntax error") ? "medium" : "high";
            f.source = "parser";
            f.ast = java.util.Map.of("path", "", "nodeType", "ERROR", "kind", "ERROR");
            out.add(f);
        }
        return out;
    }
}
