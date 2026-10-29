package com.codereviewx.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.codereviewx.ast.lang.LanguageRegistry;
import com.codereviewx.ast.rules.Finding;
import com.codereviewx.config.CrxProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity with the browser engine. The fixture is produced by running the unmodified js/review-engine.js
 * (src/test/js/export-parity.cjs), so these tests compare against the real frontend, not a re-implementation.
 */
class ScoringParityTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final ReviewEngine ENGINE = engine();
    static final List<String> REPORT = new ArrayList<>();

    static ReviewEngine engine() {
        CrxProperties.Review cfg = new CrxProperties.Review(2000, 524288, 5000, 200, 1, 1);
        return new ReviewEngine(new LanguageRegistry(), new InputValidator(cfg), new ExplanationProvider.Deterministic());
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cases() throws IOException {
        try (InputStream in = ScoringParityTest.class.getResourceAsStream("/parity/frontend-results.json")) {
            return JSON.readValue(in, new TypeReference<>() {});
        }
    }

    static Stream<Arguments> fixture() throws IOException {
        return cases().stream().map(c -> Arguments.of(c.get("id"), c));
    }

    /** Same findings and complexity class in → identical numbers out. This pins the formula itself. */
    @ParameterizedTest(name = "formula {0}")
    @MethodSource("fixture")
    @SuppressWarnings("unchecked")
    void scorerReproducesFrontendFormulaExactly(String id, Map<String, Object> c) {
        List<Finding> findings = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) c.get("findings")) {
            Finding x = new Finding();
            x.cat = (String) f.get("cat");
            x.sev = (String) f.get("sev");
            x.noPenalty = Boolean.TRUE.equals(f.get("noPenalty")) ? Boolean.TRUE : null;
            findings.add(x);
        }
        Scorer.Score s = Scorer.score(findings, timeRank((String) c.get("time")), spaceRank((String) c.get("space")));
        assertThat(s.overall()).isEqualTo(c.get("overall"));
        assertThat(s.weighted()).isEqualTo(c.get("weighted"));
        assertThat(s.efficiency()).isEqualTo(c.get("efficiency"));
        assertThat(s.counts()).isEqualTo(c.get("counts"));
        assertThat(JSON.convertValue(s.categories(), Map.class)).isEqualTo(c.get("categories"));
    }

    /** The full AST pipeline against the regex engine: overall and weighted scores within 3 points. */
    @ParameterizedTest(name = "end-to-end {0}")
    @MethodSource("fixture")
    @SuppressWarnings("unchecked")
    void astEngineStaysWithinThreePoints(String id, Map<String, Object> c) {
        Map<String, Object> r = ENGINE.review((String) c.get("code"), (String) c.get("lang"), false);
        List<String> ours = ((List<Finding>) r.get("findings")).stream().map(f -> f.ruleId).toList();
        List<String> theirs = ((List<Map<String, Object>>) c.get("findings")).stream().map(f -> (String) f.get("id")).toList();
        REPORT.add("| " + id + " | " + c.get("overall") + " / " + r.get("overall") + " | " + c.get("weighted") + " / " + r.get("weighted")
            + " | " + c.get("efficiency") + " / " + r.get("efficiency") + " | " + c.get("time") + " / " + r.get("time") + " | "
            + c.get("space") + " / " + r.get("space") + " | " + counts((Map<String, Integer>) c.get("counts")) + " / "
            + counts((Map<String, Integer>) r.get("counts")) + " | " + String.join(", ", theirs) + " | " + String.join(", ", ours) + " |");
        assertThat(Math.abs((int) r.get("overall") - (int) c.get("overall"))).as("overall for %s", id).isLessThanOrEqualTo(3);
        assertThat(Math.abs((int) r.get("weighted") - (int) c.get("weighted"))).as("weighted for %s", id).isLessThanOrEqualTo(3);
    }

    @Test
    void fixtureCoversEverySampleLanguage() throws IOException {
        assertThat(cases().stream().map(c -> c.get("lang")).distinct().toList())
            .containsExactlyInAnyOrder("python", "javascript", "java", "c", "cpp", "go");
    }

    @AfterAll
    static void writeReport() throws IOException {
        if (REPORT.isEmpty()) return;
        List<String> out = new ArrayList<>();
        out.add("| Case | overall (JS / AST) | weighted | efficiency | time | space | counts C/H/M/L/I | JS findings | AST findings |");
        out.add("|---|---|---|---|---|---|---|---|---|");
        out.addAll(REPORT.stream().sorted().toList());
        Path p = Path.of("target", "parity-report.md");
        Files.createDirectories(p.getParent());
        Files.write(p, out);
    }

    static String counts(Map<String, Integer> m) {
        return m.get("critical") + "/" + m.get("high") + "/" + m.get("medium") + "/" + m.get("low") + "/" + m.get("info");
    }

    /** Inverse of the browser engine's bigO()/TIME_RANK for the labels it can produce. */
    static int timeRank(String t) {
        if (t.contains("2ⁿ")) return 6;
        boolean log = t.contains("log");
        String n = t.replace("O(", "").replace(")", "").replace("log", "").replaceAll("[²³ ]+n?$", "").trim();
        int lin = t.matches(".*n[³⁴].*") ? 3 : t.contains("n²") ? 2 : t.matches("O\\(n( log.*)?\\)") ? 1 : 0;
        if (lin >= 3) return 5;
        if (lin == 2) return 4;
        if (lin == 1) return log ? 3 : 2;
        return log ? 1 : n.isEmpty() || t.equals("O(1)") ? 0 : 0;
    }

    static int spaceRank(String s) { return s.equals("O(n²)") ? 2 : s.equals("O(n)") ? 1 : 0; }
}
