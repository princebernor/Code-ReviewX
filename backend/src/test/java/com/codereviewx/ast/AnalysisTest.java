package com.codereviewx.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.codereviewx.ast.fingerprint.Fingerprints;
import com.codereviewx.ast.lang.LanguageModule;
import com.codereviewx.ast.lang.LanguageRegistry;
import com.codereviewx.ast.model.Kind;
import com.codereviewx.ast.model.ParseResult;
import com.codereviewx.ast.model.UNode;
import com.codereviewx.config.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Parsers, unified AST, complexity estimates, fingerprints, input limits, and the no-execution guarantee. */
class AnalysisTest {
    static final LanguageRegistry LANGS = new LanguageRegistry();

    static Map<String, Object> review(String code, String lang) { return RuleCorpusTest.review(code, lang); }

    static ParseResult parse(String code, String lang) { return LANGS.get(lang).orElseThrow().parse(code); }

    @Test
    void allSixFrontendLanguagesHaveAWorkingParser() {
        assertThat(LANGS.all()).extracting(LanguageModule::id).containsExactly("c", "cpp", "java", "python", "javascript", "go");
        assertThat(LANGS.all()).allMatch(m -> m.unavailableReason() == null);
        assertThat(LANGS.get("typescript")).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "python|def two_sum(nums):\\n    for i in nums:\\n        for j in nums:\\n            if i == j:\\n                return 1\\n    return 0\\n",
        "javascript|function two_sum(nums) {\\n  for (const i of nums) {\\n    for (const j of nums) {\\n      if (i === j) return 1;\\n    }\\n  }\\n  return 0;\\n}\\n",
        "java|class A {\\n  int two_sum(int[] nums) {\\n    for (int i : nums) {\\n      for (int j : nums) {\\n        if (i == j) return 1;\\n      }\\n    }\\n    return 0;\\n  }\\n}\\n",
        "c|int two_sum(int *nums, int n) {\\n  for (int i = 0; i < n; i++) {\\n    for (int j = 0; j < n; j++) {\\n      if (nums[i] == nums[j]) return 1;\\n    }\\n  }\\n  return 0;\\n}\\n",
        "cpp|int two_sum(std::vector<int>& nums) {\\n  for (int i : nums) {\\n    for (int j : nums) {\\n      if (i == j) return 1;\\n    }\\n  }\\n  return 0;\\n}\\n",
        "go|package main\\n\\nfunc two_sum(nums []int) int {\\n  for _, i := range nums {\\n    for _, j := range nums {\\n      if i == j {\\n        return 1\\n      }\\n    }\\n  }\\n  return 0\\n}\\n"})
    void normalizesFunctionsLoopsAndBranchesWithPathsAndSpans(String lang, String escaped) {
        String code = escaped.replace("\\n", "\n");
        ParseResult p = parse(code, lang);
        assertThat(p.partial()).isFalse();
        UNode fn = p.root().find(Kind.FUNCTION).get(0);
        assertThat(fn.name()).isEqualTo("two_sum");
        assertThat(fn.list("params")).isNotEmpty();
        List<UNode> loops = p.root().find(Kind.LOOP);
        assertThat(loops).hasSize(2);
        UNode inner = loops.get(1);
        assertThat(inner.path()).matches("(Class\\[A\\] > )?Function\\[two_sum\\] > (For|ForEach)\\[0\\] > (For|ForEach)\\[0\\]");
        UNode branch = p.root().find(Kind.IF).get(0);
        assertThat(branch.path()).endsWith("> If[0]");
        assertThat(branch.isWithin(inner)).isTrue();
        // Spans point at the real text.
        assertThat(code.substring(fn.span().start(), fn.span().end())).contains("two_sum");
        assertThat(p.source().line(inner.line())).contains("for");
        Map<String, Object> r = review(code, lang);
        assertThat(r.get("time")).isEqualTo("O(n²)");
    }

    @ParameterizedTest(name = "{0}: {2}")
    @CsvSource(delimiter = '|', value = {
        "python|def f(n):\\n    total = 0\\n    return total + n\\n|O(1)",
        "python|def f(a):\\n    lo, hi = 0, len(a) - 1\\n    while lo <= hi:\\n        mid = (lo + hi) // 2\\n        if a[mid] < 5:\\n            lo = mid + 1\\n        else:\\n            hi = mid - 1\\n    return lo\\n|O(log n)",
        "python|def f(a):\\n    b = sorted(a)\\n    return b[0]\\n|O(n log n)",
        "python|def f(n):\\n    if n < 2:\\n        return n\\n    return f(n - 1) + f(n - 2)\\n|O(2ⁿ)",
        "python|def f(n, memo={}):\\n    if n < 2:\\n        return n\\n    if n not in memo:\\n        memo[n] = f(n - 1) + f(n - 2)\\n    return memo[n]\\n|O(n)",
        "python|def f(n):\\n    if n == 0:\\n        return 0\\n    return 1 + f(n - 1)\\n|O(n)",
        "javascript|function f(a) {\\n  let s = 0;\\n  for (let i = 0; i < 10; i++) s += a[i];\\n  return s;\\n}\\n|O(1)",
        "java|class M {\\n  void sort(int[] a, int lo, int hi) {\\n    if (hi - lo < 2) return;\\n    int mid = (lo + hi) >>> 1;\\n    sort(a, lo, mid);\\n    sort(a, mid, hi);\\n  }\\n}\\n|O(n log n)",
        "c|int f(int n) {\\n  int c = 0;\\n  for (int i = 1; i < n; i *= 2) c++;\\n  return c;\\n}\\n|O(log n)",
        "go|package main\\n\\nfunc f(n int) int {\\n  if n <= 1 {\\n    return n\\n  }\\n  if n%2 == 0 {\\n    return f(n - 1)\\n  }\\n  return f(n - 2)\\n}\\n|O(n)"})
    @SuppressWarnings("unchecked")
    void estimatesTimeComplexityWithEvidence(String lang, String escaped, String expected) {
        Map<String, Object> r = review(escaped.replace("\\n", "\n"), lang);
        assertThat(r.get("time")).isEqualTo(expected);
        Map<String, Object> cx = (Map<String, Object>) r.get("complexity");
        assertThat(cx.get("confidence")).isIn("high", "medium", "low");
        if (!expected.equals("O(1)")) assertThat((List<Object>) cx.get("evidence")).isNotEmpty();
    }

    @Test
    void recursionIsLowConfidenceAndLiteralLoopsAreConstant() {
        @SuppressWarnings("unchecked")
        Map<String, Object> cx = (Map<String, Object>) review("def f(n):\n    if n < 2:\n        return n\n    return f(n - 1) + f(n - 2)\n", "python").get("complexity");
        assertThat(cx.get("confidence")).isEqualTo("low");
        assertThat(review("def f(grid):\n    n = len(grid)\n    return [[0] * n for _ in range(n)]\n", "python").get("space")).isEqualTo("O(n²)");
    }

    @Test
    void syntaxErrorsYieldAPartialTreeWithLocatedDiagnostics() {
        for (String lang : List.of("python", "c", "cpp", "javascript", "go", "java")) {
            String bad = switch (lang) {
                case "python" -> "def ok(x):\n    return x\n\ndef bad(:\n    pass\n";
                case "go" -> "package main\n\nfunc ok() int {\n    return 1\n}\n\nfunc bad( {\n";
                case "java" -> "class A {\n    int ok() { return 1; }\n    int bad( { return 2; }\n}\n";
                case "javascript" -> "function ok() {\n  return 1;\n}\nfunction bad( {\n";
                default -> "int ok(void) {\n    return 1;\n}\nint bad( {\n";
            };
            ParseResult p = parse(bad, lang);
            assertThat(p.partial()).as(lang).isTrue();
            assertThat(p.diagnostics()).as(lang).anyMatch(d -> d.severity().equals("error") && d.line() > 0);
            assertThat(p.root().find(Kind.FUNCTION)).as(lang).anyMatch(f -> "ok".equals(f.name()));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void undefinedPythonNameIsReportedWithAQuotedFix() {
        Map<String, Object> r = review("print(\"hello\")\nprint(\"hello\")\nprint(hello)\n", "python");
        List<com.codereviewx.ast.rules.Finding> fs = (List<com.codereviewx.ast.rules.Finding>) r.get("findings");
        var f = fs.stream().filter(x -> x.ruleId.equals("correctness.undefined-name")).findFirst().orElseThrow();
        assertThat(f.line).isEqualTo(3);
        assertThat(f.sev).isEqualTo("high");
        assertThat(f.original).isEqualTo("print(hello)");
        assertThat(f.suggested).isEqualTo("print(\"hello\")");
        assertThat((int) r.get("overall")).isLessThan(100);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingReturnInTheSampleIsReportedWithTheReturnAdded() {
        String sample = """
            def find_pairs(nums, target, result=[]):
                # TODO: handle empty input
                for i in range(len(nums)):
                    for j in range(i + 1, len(nums)):
                        if nums[i] + nums[j] == target:
                            result.append((nums[i], nums[j]))
                try:
                    best = max(result, key=lambda p: p[0])
                except:
                    best = None
                if best == None:
                    print("no pairs found")
            """;
        Map<String, Object> withoutReturn = review(sample, "python");
        var f = ((List<com.codereviewx.ast.rules.Finding>) withoutReturn.get("findings")).stream()
            .filter(x -> x.ruleId.equals("correctness.missing-return")).findFirst().orElseThrow();
        assertThat(f.title).isEqualTo("'result' is built but never returned");
        assertThat(f.suggested).endsWith("        print(\"no pairs found\")\n    return result");
        Map<String, Object> withReturn = review(sample + "    return result\n", "python");
        assertThat(RuleCorpusTest.fired(withReturn)).doesNotContain("correctness.missing-return");
        assertThat((int) withoutReturn.get("overall")).isLessThan((int) withReturn.get("overall"));
        // Functions that only print, or that fill a list the caller passed in, are left alone.
        assertThat(RuleCorpusTest.fired(review("def show(items):\n    for x in items:\n        print(x)\n", "python"))).doesNotContain("correctness.missing-return");
        assertThat(RuleCorpusTest.fired(review("def fill(out, n):\n    for i in range(n):\n        out.append(i)\n", "python"))).doesNotContain("correctness.missing-return");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyWayPythonBindsANameCountsAsADefinition() {
        String code = """
            import os.path as osp, sys
            from collections import deque as dq, Counter
            import json

            THRESHOLD = 3
            counter = 0

            class Box:
                size = 1

                def area(self, scale=2, *args, **kwargs):
                    return self.size * scale + len(args) + len(kwargs)

            def outer(items):
                global counter
                counter += 1
                def inner(v):
                    return v + THRESHOLD
                total = sum(inner(x) for x in items)
                squares = [a * b for a in items for b in range(2) if a]
                pairs = {k: v for k, v in zip(items, items)}
                first, (second, third) = 1, (2, 3)
                for i, (j, k) in enumerate(pairs.items()):
                    total += i + first + second + third
                if (n := len(items)) > THRESHOLD:
                    total += n
                try:
                    with open(osp.join("a", "b")) as fh, open("c") as gh:
                        data = json.loads(fh.read() + gh.read())
                except (ValueError, OSError) as err:
                    print(err, file=sys.stderr)
                    data = None
                key = lambda item: item[0]
                q = dq(Counter(items))
                return sorted(squares, key=key), data, q, Box().area(scale=3)

            if __name__ == "__main__":
                outer([1, 2, 3])
            """;
        assertThat(((List<com.codereviewx.ast.rules.Finding>) review(code, "python").get("findings")).stream().filter(f -> f.ruleId.equals("correctness.undefined-name")).map(f -> f.title + "@" + f.line).toList()).isEmpty();
        // `from m import *` can define anything, so the rule stays quiet.
        assertThat(RuleCorpusTest.fired(review("from math import *\nx = pi\nprint(y)\n", "python"))).doesNotContain("correctness.undefined-name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void syntaxErrorsBecomeFindingsInEveryLanguage() {
        Map<String, String> broken = Map.of(
            "python", "def f(x):\n    print(\"hello\"\n    return x\n",
            "javascript", "function f(x) {\n  return (x + 1;\n}\n",
            "java", "class A {\n    int f(int x) {\n        return x + ;\n    }\n}\n",
            "c", "int f(int x) {\n    return x +;\n}\n",
            "cpp", "int f(int x) {\n    return (x + 1;\n}\n",
            "go", "package main\n\nfunc f(x int) int {\n    return (x + 1\n}\n");
        broken.forEach((lang, code) -> {
            Map<String, Object> r = review(code, lang);
            assertThat(RuleCorpusTest.fired(r)).as(lang).contains("correctness.syntax-error");
            assertThat((int) r.get("overall")).as(lang).isLessThan(100);
        });
        assertThat(RuleCorpusTest.fired(review("def f(x):\n    y = x + 1\n    return y\n", "python"))).doesNotContain("correctness.syntax-error");
    }

    @Test
    void javaSnippetsWithoutAClassAreParsedByJavaParser() {
        ParseResult p = parse("static int twice(int x) {\n    return x * 2;\n}\n", "java");
        assertThat(p.parser()).isEqualTo("javaparser");
        UNode fn = p.root().find(Kind.FUNCTION).get(0);
        assertThat(fn.name()).isEqualTo("twice");
        assertThat(fn.line()).isEqualTo(1); // the synthetic wrapper does not shift lines
        assertThat(p.root().find(Kind.CLASS)).isEmpty();
    }

    @Test
    void nonAsciiSourceKeepsCorrectColumns() {
        String code = "def grüße(x):\n    s = \"héllo wörld\"\n    return s == None\n";
        ParseResult p = parse(code, "python");
        UNode cmp = p.root().find(n -> n.is(Kind.BINARY)).get(0);
        assertThat(cmp.text()).isEqualTo("s == None");
        assertThat(p.root().find(Kind.FUNCTION).get(0).name()).isEqualTo("grüße");
    }

    @Test
    void validationMatchesTheFrontendCodes() {
        assertCode("", "python", "empty");
        assertCode("x = 1\n", "python", "short");
        assertCode("x = 1\n".repeat(2001), "python", "long");
        assertCode("a = 1\nb = 2\nc = \u0001\n", "python", "binary");
        assertCode("a\nb\nc\n", "rust", "unsupported_language");
        assertCode("f(" .repeat(250) + "\nx\ny\n", "python", "too_deep");
    }

    private static void assertCode(String code, String lang, String expected) {
        assertThatThrownBy(() -> review(code, lang)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    @Test
    void submittedCodeIsNeverExecuted(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("executed.txt");
        String p = marker.toString().replace("\\", "/");
        review("import os\nopen(\"" + p + "\", \"w\").write(\"x\")\nos.system(\"echo x > " + p + "\")\n", "python");
        review("const fs = require('fs');\nfs.writeFileSync('" + p + "', 'x');\nrequire('child_process').execSync('echo x > " + p + "');\n", "javascript");
        review("class A {\n    static { try { java.nio.file.Files.writeString(java.nio.file.Path.of(\"" + p + "\"), \"x\"); } catch (Exception e) {} }\n    public static void main(String[] a) { }\n}\n", "java");
        review("#include <stdlib.h>\nint main(void) {\n    system(\"echo x > " + p + "\");\n    return 0;\n}\n", "c");
        review("package main\n\nimport \"os\"\n\nfunc init() {\n    os.WriteFile(\"" + p + "\", []byte(\"x\"), 0644)\n}\n", "go");
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test
    void fingerprintsMatchRenamedCodeAndCompareAcrossLanguages() {
        UNode a = parse("def f(a):\n    s = 0\n    for x in a:\n        s += x\n    return s\n", "python").root();
        UNode renamed = parse("def g(items):\n    total = 0\n    for item in items:\n        total += item\n    return total\n", "python").root();
        UNode other = parse("def h(n):\n    if n > 3:\n        return [n] * n\n    return None\n", "python").root();
        UNode js = parse("function f(a) {\n  let s = 0;\n  for (const x of a) {\n    s += x;\n  }\n  return s;\n}\n", "javascript").root();
        assertThat(Fingerprints.fingerprint(a)).isEqualTo(Fingerprints.fingerprint(renamed));
        assertThat(Fingerprints.similarity(a, renamed)).isEqualTo(1.0);
        assertThat(Fingerprints.similarity(a, js)).isGreaterThan(Fingerprints.similarity(a, other));
    }
}
