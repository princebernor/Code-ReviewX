package com.codereviewx.ast;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.codereviewx.ast.lang.LanguageRegistry;
import com.codereviewx.ast.rules.Finding;
import com.codereviewx.ast.rules.RuleCatalog;
import com.codereviewx.config.CrxProperties;
import com.codereviewx.review.ExplanationProvider;
import com.codereviewx.review.InputValidator;
import com.codereviewx.review.ReviewEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every rule has a positive case (must fire), a negative case (must not fire) and a partial-syntax case (the
 * positive case followed by a broken fragment: the rule must still fire on the recovered tree and the parser must
 * report the error).
 */
class RuleCorpusTest {
    static final ReviewEngine ENGINE = new ReviewEngine(new LanguageRegistry(),
        new InputValidator(new CrxProperties.Review(2000, 524288, 5000, 200, 1, 1)), new ExplanationProvider.Deterministic());

    record Case(String rule, String lang, String positive, String negative) {}

    static final Map<String, String> BROKEN = Map.of(
        "python", "\ndef broken(:\n    pass\n",
        "c", "\nint broken( {\n",
        "cpp", "\nint broken( {\n",
        "java", "\nclass Broken { void f( { }\n",
        "javascript", "\nfunction broken( {\n",
        "go", "\nfunc broken( {\n");

    static String repeat(String line, int n) { return line.repeat(n); }

    static final List<Case> CASES = List.of(
        // ---------------- security
        new Case("security.gets", "c",
            "#include <stdio.h>\nint main(void) {\n    char buf[16];\n    gets(buf);\n    return 0;\n}\n",
            "#include <stdio.h>\nint main(void) {\n    char buf[16];\n    fgets(buf, sizeof buf, stdin);\n    return 0;\n}\n"),
        new Case("security.unbounded-copy", "c",
            "void f(const char *s) {\n    char d[8];\n    strcpy(d, s);\n}\n",
            "void f(const char *s) {\n    char d[8];\n    snprintf(d, sizeof d, \"%s\", s);\n}\n"),
        new Case("security.scanf-unbounded", "c",
            "int main(void) {\n    char name[16];\n    scanf(\"%s\", name);\n    return 0;\n}\n",
            "int main(void) {\n    char name[16];\n    scanf(\"%15s\", name);\n    return 0;\n}\n"),
        new Case("security.dynamic-exec", "python",
            "def run():\n    text = input()\n    return eval(text)\n",
            "import ast\ndef run():\n    text = input()\n    return ast.literal_eval(text)\n"),
        new Case("security.hardcoded-credential", "python",
            "import os\nAPI_KEY = \"sk_live_abcdef123\"\ndef f():\n    return API_KEY\n",
            "import os\nAPI_KEY = os.environ[\"API_KEY\"]\ndef f():\n    return API_KEY\n"),
        new Case("security.sql-concatenation", "java",
            "class Repo {\n    void find(java.sql.Statement st, String id) throws Exception {\n        st.executeQuery(\"SELECT * FROM users WHERE id = \" + id);\n    }\n}\n",
            "class Repo {\n    void find(java.sql.Connection c, String id) throws Exception {\n        var ps = c.prepareStatement(\"SELECT * FROM users WHERE id = ?\");\n        ps.setString(1, id);\n    }\n}\n"),
        new Case("security.command-injection", "python",
            "import os\ndef ls(path):\n    os.system(\"ls \" + path)\n",
            "import subprocess\ndef ls(path):\n    subprocess.run([\"ls\", path], check=True)\n"),
        new Case("security.insecure-random", "javascript",
            "function makeToken() {\n  const token = Math.random().toString(36);\n  return token;\n}\n",
            "function pickColor() {\n  const index = Math.floor(Math.random() * 3);\n  return index;\n}\n"),
        new Case("security.weak-hash", "python",
            "import hashlib\ndef h(data):\n    return hashlib.md5(data).hexdigest()\n",
            "import hashlib\ndef h(data):\n    return hashlib.sha256(data).hexdigest()\n"),
        new Case("security.inner-html", "javascript",
            "function show(name) {\n  const el = document.getElementById(\"out\");\n  el.innerHTML = \"Hi \" + name;\n}\n",
            "function show(name) {\n  const el = document.getElementById(\"out\");\n  el.textContent = \"Hi \" + name;\n}\n"),
        // ---------------- correctness
        new Case("correctness.undefined-name", "python",
            "def greet():\n    print(\"hello\")\n    print(hello)\n",
            "def greet():\n    hello = \"hi\"\n    print(hello)\n"),
        new Case("correctness.missing-return", "python",
            "def find_pairs(nums, target):\n    result = []\n    for x in nums:\n        if x > target:\n            result.append(x)\n",
            "def find_pairs(nums, target):\n    result = []\n    for x in nums:\n        if x > target:\n            result.append(x)\n    return result\n"),
        new Case("correctness.mutable-default", "python",
            "def add(x, items=[]):\n    items.append(x)\n    return items\n",
            "def add(x, items=None):\n    items = items or []\n    items.append(x)\n    return items\n"),
        new Case("correctness.bare-except", "python",
            "def f(s):\n    try:\n        return int(s)\n    except:\n        return 0\n",
            "def f(s):\n    try:\n        return int(s)\n    except ValueError:\n        return 0\n"),
        new Case("correctness.loose-equality", "javascript",
            "function eq(a, b) {\n  if (a == b) return true;\n  return false;\n}\n",
            "function eq(a, b) {\n  if (a === b) return true;\n  return false;\n}\n"),
        new Case("correctness.string-reference-equality", "java",
            "class A {\n    boolean admin(String role) {\n        return role == \"admin\";\n    }\n}\n",
            "class A {\n    boolean admin(String role) {\n        return \"admin\".equals(role);\n    }\n}\n"),
        new Case("correctness.assignment-in-condition", "c",
            "int f(int x) {\n    if (x = 5) {\n        return 1;\n    }\n    return 0;\n}\n",
            "int f(int x) {\n    if (x == 5) {\n        return 1;\n    }\n    return 0;\n}\n"),
        new Case("correctness.midpoint-overflow", "java",
            "class A {\n    int mid(int lo, int hi) {\n        return (lo + hi) / 2;\n    }\n}\n",
            "class A {\n    int mid(int lo, int hi) {\n        return lo + (hi - lo) / 2;\n    }\n}\n"),
        new Case("correctness.loop-past-end", "java",
            "class A {\n    int sum(int[] a) {\n        int s = 0;\n        for (int i = 0; i <= a.length; i++) s += a[i];\n        return s;\n    }\n}\n",
            "class A {\n    int sum(int[] a) {\n        int s = 0;\n        for (int i = 0; i < a.length; i++) s += a[i];\n        return s;\n    }\n}\n"),
        new Case("correctness.inclusive-bound-at-length", "javascript",
            "function search(a, t) {\n  let lo = 0, hi = a.length;\n  while (lo <= hi) {\n    const mid = Math.floor((lo + hi) / 2);\n    if (a[mid] === t) return mid;\n    if (a[mid] < t) lo = mid + 1; else hi = mid - 1;\n  }\n  return -1;\n}\n",
            "function search(a, t) {\n  let lo = 0, hi = a.length - 1;\n  while (lo <= hi) {\n    const mid = Math.floor((lo + hi) / 2);\n    if (a[mid] === t) return mid;\n    if (a[mid] < t) lo = mid + 1; else hi = mid - 1;\n  }\n  return -1;\n}\n"),
        new Case("correctness.ignored-error", "go",
            "package main\n\nimport \"strconv\"\n\nfunc parse(s string) int {\n    n, _ := strconv.Atoi(s)\n    return n\n}\n",
            "package main\n\nimport \"strconv\"\n\nfunc parse(s string) int {\n    n, err := strconv.Atoi(s)\n    if err != nil {\n        return 0\n    }\n    return n\n}\n"),
        new Case("correctness.signed-unsigned-comparison", "cpp",
            "#include <vector>\nint sum(const std::vector<int>& v) {\n    int s = 0;\n    for (int i = 0; i < v.size(); i++) s += v[i];\n    return s;\n}\n",
            "#include <vector>\nint sum(const std::vector<int>& v) {\n    int s = 0;\n    for (size_t i = 0; i < v.size(); i++) s += v[i];\n    return s;\n}\n"),
        new Case("correctness.missing-break", "c",
            "int f(int x) {\n    int r = 0;\n    switch (x) {\n    case 1:\n        r = 10;\n    case 2:\n        r = 20;\n        break;\n    }\n    return r;\n}\n",
            "int f(int x) {\n    int r = 0;\n    switch (x) {\n    case 1:\n        r = 10;\n        break;\n    case 2:\n        r = 20;\n        break;\n    }\n    return r;\n}\n"),
        new Case("correctness.unreachable-code", "python",
            "def f(x):\n    return x * 2\n    print(\"done\")\n",
            "def f(x):\n    print(\"done\")\n    return x * 2\n"),
        new Case("correctness.unchecked-allocation", "c",
            "#include <stdlib.h>\nint *make(int n) {\n    int *p = malloc(n * sizeof *p);\n    p[0] = 1;\n    return p;\n}\n",
            "#include <stdlib.h>\nint *make(int n) {\n    int *p = malloc(n * sizeof *p);\n    if (p == NULL) return NULL;\n    p[0] = 1;\n    return p;\n}\n"),
        new Case("correctness.use-after-free", "c",
            "#include <stdlib.h>\nvoid f(void) {\n    char *p = malloc(8);\n    if (!p) return;\n    free(p);\n    p[0] = 'a';\n}\n",
            "#include <stdlib.h>\nvoid f(void) {\n    char *p = malloc(8);\n    if (!p) return;\n    p[0] = 'a';\n    free(p);\n}\n"),
        new Case("correctness.double-free", "c",
            "#include <stdlib.h>\nvoid f(void) {\n    char *p = malloc(8);\n    free(p);\n    free(p);\n}\n",
            "#include <stdlib.h>\nvoid f(void) {\n    char *p = malloc(8);\n    free(p);\n    p = NULL;\n    free(p);\n}\n"),
        new Case("correctness.null-dereference", "java",
            "class A {\n    int f() {\n        String s = null;\n        return s.length();\n    }\n}\n",
            "class A {\n    int f() {\n        String s = \"x\";\n        return s.length();\n    }\n}\n"),
        // ---------------- time / space
        new Case("time.linear-in-loop", "python",
            "def f(items):\n    out = []\n    for x in items:\n        if out.count(x) == 0:\n            out.append(x)\n    return out\n",
            "def f(items):\n    seen = set()\n    out = []\n    for x in items:\n        if x not in seen:\n            seen.add(x)\n            out.append(x)\n    return out\n"),
        new Case("time.strlen-in-condition", "c",
            "#include <string.h>\nint count(const char *s) {\n    int n = 0;\n    for (int i = 0; i < strlen(s); i++) n++;\n    return n;\n}\n",
            "#include <string.h>\nint count(const char *s) {\n    int n = 0;\n    size_t len = strlen(s);\n    for (size_t i = 0; i < len; i++) n++;\n    return n;\n}\n"),
        new Case("time.sort-in-loop", "python",
            "def f(rows):\n    best = []\n    for r in rows:\n        r.sort()\n        best.append(r[0])\n    return best\n",
            "def f(rows):\n    best = []\n    rows.sort()\n    for r in rows:\n        best.append(r[0])\n    return best\n"),
        new Case("time.string-concat-in-loop", "java",
            "class A {\n    String join(String[] parts) {\n        String s = \"\";\n        for (String p : parts) {\n            s += p;\n        }\n        return s;\n    }\n}\n",
            "class A {\n    String join(String[] parts) {\n        StringBuilder sb = new StringBuilder();\n        for (String p : parts) {\n            sb.append(p);\n        }\n        return sb.toString();\n    }\n}\n"),
        new Case("space.container-by-value", "cpp",
            "#include <vector>\nint first(std::vector<int> v) {\n    if (v.empty()) return 0;\n    return v[0];\n}\n",
            "#include <vector>\nint first(const std::vector<int>& v) {\n    if (v.empty()) return 0;\n    return v[0];\n}\n"),
        new Case("space.allocation-in-loop", "java",
            "class A {\n    int f(int n) {\n        int t = 0;\n        for (int i = 0; i < n; i++) {\n            int[] buf = new int[n];\n            t += buf.length;\n        }\n        return t;\n    }\n}\n",
            "class A {\n    java.util.List<int[]> f(int n) {\n        java.util.List<int[]> rows = new java.util.ArrayList<>();\n        for (int i = 0; i < n; i++) {\n            int[] row = new int[n];\n            rows.add(row);\n        }\n        return rows;\n    }\n}\n"),
        new Case("space.copy-in-loop", "python",
            "def f(items):\n    total = 0\n    for i in range(len(items)):\n        rest = items[:]\n        total += len(rest)\n    return total\n",
            "def f(items):\n    total = 0\n    rest = items[:]\n    for i in range(len(items)):\n        total += len(rest)\n    return total\n"),
        new Case("space.unbounded-accumulation", "python",
            "def collect(read):\n    out = []\n    while True:\n        out.append(read())\n",
            "def collect(read):\n    out = []\n    while True:\n        out.append(read())\n        if len(out) > 100:\n            break\n    return out\n"),
        // ---------------- quality / readability
        new Case("quality.long-function", "python",
            "def big(x):\n" + repeat("    x = x + 1\n", 65) + "    return x\n",
            "def small(x):\n    x = x + 1\n    return x\n"),
        new Case("quality.cyclomatic-complexity", "python",
            "def grade(x):\n" + Stream.iterate(1, i -> i + 1).limit(11).map(i -> "    if x == " + i + ":\n        return " + i + "\n").collect(Collectors.joining()) + "    return 0\n",
            "def grade(x):\n    if x > 5:\n        return 1\n    return 0\n"),
        new Case("quality.deep-nesting", "javascript",
            "function f(a) {\n  if (a) {\n    if (a > 1) {\n      if (a > 2) {\n        if (a > 3) {\n          if (a > 4) {\n            return 1;\n          }\n        }\n      }\n    }\n  }\n  return 0;\n}\n",
            "function f(a) {\n  if (a === 1) return 1;\n  else if (a === 2) return 2;\n  else if (a === 3) return 3;\n  else if (a === 4) return 4;\n  else if (a === 5) return 5;\n  else if (a === 6) return 6;\n  return 0;\n}\n"),
        new Case("quality.too-many-parameters", "go",
            "package main\n\nfunc f(a, b, c, d, e, g int) int {\n    return a + b + c + d + e + g\n}\n",
            "package main\n\nfunc f(a, b, c int) int {\n    return a + b + c\n}\n"),
        new Case("quality.magic-number", "python",
            "def adult(age):\n    if age > 17:\n        return True\n    return False\n",
            "ADULT_AGE = 18\ndef adult(age):\n    if age >= ADULT_AGE:\n        return True\n    return False\n"),
        new Case("quality.duplicate-code", "python",
            "def f(a, b):\n    for x in a:\n        if x > 0:\n            total = x * 2 + 1\n            count = count + total * x\n            print(total, count)\n    for y in b:\n        if y > 0:\n            total = y * 2 + 1\n            count = count + total * y\n            print(total, count)\n",
            "def f(a, b):\n    for x in a:\n        if x > 0:\n            total = x * 2 + 1\n            count = count + total * x\n            print(total, count)\n    while b:\n        b = b[1:]\n"),
        new Case("quality.console-output", "javascript",
            "function add(a, b) {\n  console.log(a);\n  return a + b;\n}\n",
            "function add(a, b) {\n  const sum = a + b;\n  return sum;\n}\n"),
        new Case("quality.todo", "python",
            "def f(x):\n    # TODO: handle negative x\n    return x\n",
            "def f(x):\n    # negative x is rejected by the caller\n    return x\n"),
        new Case("quality.empty-catch", "java",
            "class A {\n    int f(String s) {\n        try {\n            return Integer.parseInt(s);\n        } catch (NumberFormatException e) {\n        }\n        return 0;\n    }\n}\n",
            "class A {\n    int f(String s) {\n        try {\n            return Integer.parseInt(s);\n        } catch (NumberFormatException e) {\n            return -1;\n        }\n    }\n}\n"),
        new Case("quality.swallowed-exception", "python",
            "def f(s):\n    try:\n        return int(s)\n    except ValueError:\n        pass\n    return 0\n",
            "def f(s):\n    try:\n        return int(s)\n    except ValueError:\n        return 0\n"),
        new Case("readability.long-line", "python",
            "def f():\n    message = \"" + "x".repeat(130) + "\"\n    return message\n",
            "def f():\n    message = \"short\"\n    return message\n"),
        new Case("readability.naming", "python",
            "def computeTotal(values):\n    total = sum(values)\n    return total\n",
            "def compute_total(values):\n    total = sum(values)\n    return total\n"),
        // ---------------- best practices
        new Case("practices.none-comparison", "python",
            "def f(x):\n    if x == None:\n        return 0\n    return x\n",
            "def f(x):\n    if x is None:\n        return 0\n    return x\n"),
        new Case("practices.var-declaration", "javascript",
            "function f(a) {\n  var total = a + 1;\n  return total;\n}\n",
            "function f(a) {\n  const total = a + 1;\n  return total;\n}\n"),
        new Case("practices.prefer-const", "javascript",
            "function f(a) {\n  let total = a + 1;\n  return total;\n}\n",
            "function f(a) {\n  let total = a + 1;\n  total += 2;\n  return total;\n}\n"),
        new Case("practices.bits-stdcpp", "cpp",
            "#include <bits/stdc++.h>\nint f(int a) {\n    return a + 1;\n}\n",
            "#include <vector>\nint f(int a) {\n    return a + 1;\n}\n"),
        new Case("practices.using-namespace-std", "cpp",
            "#include <vector>\nusing namespace std;\nint f(vector<int>& v) {\n    return v.size();\n}\n",
            "#include <vector>\nusing std::vector;\nint f(vector<int>& v) {\n    return v.size();\n}\n"),
        new Case("practices.generic-catch", "java",
            "class A {\n    int f(String s) {\n        try {\n            return Integer.parseInt(s);\n        } catch (Exception e) {\n            return -1;\n        }\n    }\n}\n",
            "class A {\n    int f(String s) {\n        try {\n            return Integer.parseInt(s);\n        } catch (NumberFormatException e) {\n            return -1;\n        }\n    }\n}\n"),
        new Case("practices.resource-leak", "python",
            "def read(path):\n    f = open(path)\n    data = f.read()\n    return data\n",
            "def read(path):\n    with open(path) as f:\n        data = f.read()\n    return data\n"),
        new Case("practices.missing-error-context", "java",
            "class A {\n    void check(int i) {\n        if (i < 0) throw new IllegalArgumentException();\n    }\n}\n",
            "class A {\n    void check(int i) {\n        if (i < 0) throw new IllegalArgumentException(\"negative index: \" + i);\n    }\n}\n"),
        new Case("practices.missing-doc", "java",
            "public class A {\n    public int f(int x) {\n" + repeat("        x = x + 1;\n", 16) + "        return x;\n    }\n}\n",
            "public class A {\n    /** Adds sixteen. */\n    public int f(int x) {\n" + repeat("        x = x + 1;\n", 16) + "        return x;\n    }\n}\n"));

    static Stream<Arguments> cases() { return CASES.stream().map(c -> Arguments.of(c.rule(), c)); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> review(String code, String lang) { return ENGINE.review(code, lang, false); }

    @SuppressWarnings("unchecked")
    static Set<String> fired(Map<String, Object> r) {
        return ((List<Finding>) r.get("findings")).stream().map(f -> f.ruleId).collect(Collectors.toSet());
    }

    @ParameterizedTest(name = "{0} fires")
    @MethodSource("cases")
    @SuppressWarnings("unchecked")
    void positive(String rule, Case c) {
        Map<String, Object> r = review(c.positive(), c.lang());
        assertThat(fired(r)).as("rules fired").contains(rule);
        Finding f = ((List<Finding>) r.get("findings")).stream().filter(x -> x.ruleId.equals(rule)).findFirst().orElseThrow();
        assertThat(f.line).isPositive();
        assertThat(f.end).isGreaterThanOrEqualTo(f.line);
        assertThat(f.original).isNotBlank();
        assertThat(f.suggested).isNotBlank();
        assertThat(f.ast).containsKeys("path", "nodeType");
        assertThat(f.confidence).isIn("high", "medium", "low");
        // The reported location really is in the code.
        assertThat(c.positive().split("\n", -1).length).isGreaterThanOrEqualTo(f.end);
    }

    @ParameterizedTest(name = "{0} stays quiet")
    @MethodSource("cases")
    void negative(String rule, Case c) {
        assertThat(fired(review(c.negative(), c.lang()))).as("rules fired").doesNotContain(rule);
    }

    @ParameterizedTest(name = "{0} survives a syntax error")
    @MethodSource("cases")
    @SuppressWarnings("unchecked")
    void partialSyntax(String rule, Case c) {
        Map<String, Object> r = review(c.positive() + BROKEN.get(c.lang()), c.lang());
        assertThat((boolean) r.get("partial")).as("partial tree").isTrue();
        assertThat((List<Object>) r.get("diagnostics")).as("diagnostics").isNotEmpty();
        assertThat(fired(r)).as("rules fired on the recovered tree").contains(rule);
    }

    @Test
    void everyRuleInTheCatalogHasACorpusCase() {
        List<String> missing = new ArrayList<>();
        for (var r : RuleCatalog.all()) if (CASES.stream().noneMatch(c -> c.rule().equals(r.meta().id()))) missing.add(r.meta().id());
        assertThat(missing).isEmpty();
    }

    @Test
    void lineFixesAreBuiltFromSourceSpans() {
        @SuppressWarnings("unchecked")
        List<Finding> fs = (List<Finding>) review(CASES.get(0).positive(), "c").get("findings");
        Finding gets = fs.stream().filter(f -> f.ruleId.equals("security.gets")).findFirst().orElseThrow();
        assertThat(gets.kind).isEqualTo("line");
        assertThat(gets.original).isEqualTo("    gets(buf);");
        assertThat(gets.suggested).isEqualTo("    fgets(buf, sizeof buf, stdin);");
        assertThat(gets.fixAvailable).isTrue();
    }
}
