package com.codereviewx.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.codereviewx.config.CrxProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the generated driver programs compile and grade correctly, by running them with the toolchains on the
 * build machine (LocalJudge). Languages whose toolchain is missing are skipped, not passed.
 */
class HarnessExecutionTest {
    @TempDir static Path work;
    static RunService runs;
    static Map<Integer, List<RunService.TestCase>> tests = new java.util.HashMap<>();
    static List<Map<String, Object>> problems;

    static CrxProperties props() {
        CrxProperties.Judge judge = new CrxProperties.Judge(true, "http://local", "", "X-Auth-Token", "", Duration.ofSeconds(30), 5, 10, 256000,
            102400, 20, 10, Map.of("python", 1, "javascript", 2, "java", 3, "cpp", 4, "c", 5, "go", 6));
        return new CrxProperties(null, null, null, null, null, null, null, judge);
    }

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void setUp() throws Exception {
        runs = new RunService(new LocalJudge(work), props());
        try (InputStream in = HarnessExecutionTest.class.getResourceAsStream("/problems/details.json")) {
            problems = new ObjectMapper().readValue(in, new TypeReference<>() {});
        }
        for (Map<String, Object> p : problems) {
            List<RunService.TestCase> cs = new ArrayList<>();
            for (Map<String, String> t : (List<Map<String, String>>) p.get("tests")) cs.add(new RunService.TestCase(t.get("in"), t.get("out")));
            tests.put((Integer) p.get("id"), cs);
        }
    }

    @SuppressWarnings("unchecked")
    static Stream<Arguments> referenceSolutions() throws Exception {
        setUp();
        List<Arguments> out = new ArrayList<>();
        for (Map<String, Object> p : problems) {
            for (Map.Entry<String, String> s : ((Map<String, String>) p.get("solution")).entrySet()) {
                if (KNOWN_DATA_ISSUE.equals(p.get("id") + ":" + s.getKey())) continue; // see knownDataIssueInProblem265
                out.add(Arguments.of(p.get("id"), s.getKey(), s.getValue()));
            }
        }
        return out.stream();
    }

    /** data/problem-details.js #265: the tests are number grids but the reference solution compares with '1' strings. */
    static final String KNOWN_DATA_ISSUE = "265:python";

    @Test
    @SuppressWarnings("unchecked")
    void knownDataIssueInProblem265() {
        assumeTrue(LocalJudge.available("python"));
        Map<String, Object> p = problems.stream().filter(x -> x.get("id").equals(265)).findFirst().orElseThrow();
        String reference = ((Map<String, String>) p.get("solution")).get("python");
        Map<String, Object> r = runs.runTests("python", reference, tests.get(265));
        // The harness reports the mismatch faithfully: the reference returns 0 islands for numeric grids.
        assertThat(((List<Map<String, Object>>) r.get("cases")).get(0)).containsEntry("verdict", "Wrong answer").containsEntry("actual", "0");
        // The same algorithm comparing with the number 1 passes every case.
        assertAllPass(265, "python", reference.replace("'1'", "1").replace("'0'", "0"));
    }

    static void assertAllPass(int problem, String lang, String code) {
        assumeTrue(LocalJudge.available(lang), lang + " toolchain not installed on this machine");
        Map<String, Object> r = runs.runTests(lang, code, tests.get(problem));
        assertThat(r.get("allPassed")).as("#%d %s → %s", problem, lang, r).isEqualTo(true);
    }

    @ParameterizedTest(name = "#{0} reference solution in {1}")
    @MethodSource("referenceSolutions")
    void referenceSolutionsPassEveryTest(int problem, String lang, String code) {
        assertAllPass(problem, lang, code);
    }

    static Stream<Arguments> userStyleSolutions() {
        return Stream.of(
            // #1 even or odd
            Arguments.of(1, "java", "class Solution {\n    public String evenOrOdd(long n) {\n        return n % 2 == 0 ? \"Even\" : \"Odd\";\n    }\n}\n"),
            Arguments.of(1, "java", "public class Main {\n    static String check(int n) { return n % 2 == 0 ? \"Even\" : \"Odd\"; }\n    public static void main(String[] a) { System.out.println(check(3)); }\n}\n"),
            Arguments.of(1, "javascript", "function evenOrOdd(n) {\n  return n % 2 === 0 ? \"Even\" : \"Odd\";\n}\n"),
            Arguments.of(1, "c", "#include <stdio.h>\n\nconst char* evenOrOdd(long long n) {\n    return n % 2 == 0 ? \"Even\" : \"Odd\";\n}\n\nint main(void) {\n    return 0;\n}\n"),
            Arguments.of(1, "cpp", "#include <string>\nclass Solution {\npublic:\n    std::string evenOrOdd(int n) { return n % 2 == 0 ? \"Even\" : \"Odd\"; }\n};\n"),
            Arguments.of(1, "python", "class Solution:\n    def even_or_odd(self, n):\n        return \"Even\" if n % 2 == 0 else \"Odd\"\n"),
            // #26 pair sum: C array + length, C++ vector, JS, Java helper method
            Arguments.of(26, "c", "#include <stdbool.h>\nbool hasPair(int arr[], int n, int target) {\n    for (int i = 0; i < n; i++)\n        for (int j = i + 1; j < n; j++)\n            if (arr[i] + arr[j] == target) return true;\n    return false;\n}\n\nint main(void) { return 0; }\n"),
            Arguments.of(26, "cpp", "#include <vector>\n#include <unordered_set>\nbool hasPair(const std::vector<int>& a, int t) {\n    std::unordered_set<int> seen;\n    for (int x : a) { if (seen.count(t - x)) return true; seen.insert(x); }\n    return false;\n}\nint main() { return 0; }\n"),
            Arguments.of(26, "javascript", "function hasPair(arr, target) {\n  const seen = new Set();\n  for (const x of arr) { if (seen.has(target - x)) return true; seen.add(x); }\n  return false;\n}\n"),
            Arguments.of(26, "java", "class Solution {\n    private boolean contains(java.util.Set<Integer> s, int v) { return s.contains(v); }\n    public boolean hasPair(int[] arr, int target) {\n        java.util.Set<Integer> s = new java.util.HashSet<>();\n        for (int x : arr) { if (contains(s, target - x)) return true; s.add(x); }\n        return false;\n    }\n}\n"),
            // #92 linked lists in every language
            Arguments.of(92, "cpp", "struct ListNode { int val; ListNode* next; ListNode(int x) : val(x), next(nullptr) {} };\nListNode* reverseList(ListNode* head) {\n    ListNode* prev = nullptr;\n    while (head) { ListNode* n = head->next; head->next = prev; prev = head; head = n; }\n    return prev;\n}\n"),
            Arguments.of(92, "c", "struct ListNode* reverse(struct ListNode* head) {\n    struct ListNode* prev = NULL;\n    while (head) { struct ListNode* n = head->next; head->next = prev; prev = head; head = n; }\n    return prev;\n}\n"),
            Arguments.of(92, "javascript", "function reverse(head) {\n  let prev = null;\n  while (head) { const n = head.next; head.next = prev; prev = head; head = n; }\n  return prev;\n}\n"),
            Arguments.of(92, "python", "class ListNode:\n    def __init__(self, x):\n        self.val = x\n        self.next = None\n\ndef reverse(head):\n    prev = None\n    while head:\n        head.next, prev, head = prev, head, head.next\n    return prev\n"),
            // #265 grid
            Arguments.of(265, "java", "class Solution {\n    public int numIslands(int[][] g) {\n        int c = 0;\n        for (int i = 0; i < g.length; i++) for (int j = 0; j < g[0].length; j++) if (g[i][j] == 1) { c++; sink(g, i, j); }\n        return c;\n    }\n    void sink(int[][] g, int i, int j) {\n        if (i < 0 || j < 0 || i >= g.length || j >= g[0].length || g[i][j] != 1) return;\n        g[i][j] = 0; sink(g, i + 1, j); sink(g, i - 1, j); sink(g, i, j + 1); sink(g, i, j - 1);\n    }\n}\n"),
            Arguments.of(265, "cpp", "#include <vector>\nusing namespace std;\nvoid sink(vector<vector<int>>& g, int i, int j) {\n    if (i < 0 || j < 0 || i >= (int) g.size() || j >= (int) g[0].size() || g[i][j] != 1) return;\n    g[i][j] = 0; sink(g, i + 1, j); sink(g, i - 1, j); sink(g, i, j + 1); sink(g, i, j - 1);\n}\nint numIslands(vector<vector<int>>& g) {\n    int c = 0;\n    for (int i = 0; i < (int) g.size(); i++) for (int j = 0; j < (int) g[0].size(); j++) if (g[i][j] == 1) { c++; sink(g, i, j); }\n    return c;\n}\n"),
            // #170 strings
            Arguments.of(170, "java", "class Solution {\n    int longestUnique(String s) {\n        int[] last = new int[128]; java.util.Arrays.fill(last, -1);\n        int best = 0, lo = 0;\n        for (int i = 0; i < s.length(); i++) { lo = Math.max(lo, last[s.charAt(i)] + 1); last[s.charAt(i)] = i; best = Math.max(best, i - lo + 1); }\n        return best;\n    }\n}\n"),
            Arguments.of(170, "c", "#include <string.h>\nint longestUnique(const char* s) {\n    int last[256]; for (int i = 0; i < 256; i++) last[i] = -1;\n    int best = 0, lo = 0, n = (int) strlen(s);\n    for (int i = 0; i < n; i++) { unsigned char ch = s[i]; if (last[ch] + 1 > lo) lo = last[ch] + 1; last[ch] = i; if (i - lo + 1 > best) best = i - lo + 1; }\n    return best;\n}\n"),
            Arguments.of(170, "cpp", "#include <string>\n#include <unordered_map>\nint longestUnique(std::string s) {\n    std::unordered_map<char, int> last; int best = 0, lo = 0;\n    for (int i = 0; i < (int) s.size(); i++) { if (last.count(s[i]) && last[s[i]] >= lo) lo = last[s[i]] + 1; last[s[i]] = i; best = std::max(best, i - lo + 1); }\n    return best;\n}\n"));
    }

    @ParameterizedTest(name = "#{0} written in {1}")
    @MethodSource("userStyleSolutions")
    void userStyleSolutionsPass(int problem, String lang, String code) {
        assertAllPass(problem, lang, code);
    }

    @Test
    @SuppressWarnings("unchecked")
    void wrongAnswersAndCrashesAreReportedPerCase() {
        assumeTrue(LocalJudge.available("python"));
        Map<String, Object> r = runs.runTests("python", "def even_or_odd(n):\n    if n == 0:\n        raise ValueError(\"zero\")\n    print(\"debug\", n)\n    return \"Even\" if n % 4 == 0 else \"Odd\"\n", tests.get(1));
        List<Map<String, Object>> cases = (List<Map<String, Object>>) r.get("cases");
        assertThat(cases).extracting(c -> c.get("verdict")).containsExactly("Passed", "Passed", "Runtime error", "Passed");
        assertThat(cases.get(2).get("error").toString()).contains("ValueError: zero");
        assertThat(r.get("passed")).isEqualTo(3);
        assertThat(r.get("stdout").toString()).contains("debug 4").doesNotContain("@@CRX");

        Map<String, Object> wrong = runs.runTests("python", "def even_or_odd(n):\n    return \"even\"\n", tests.get(1));
        assertThat(((List<Map<String, Object>>) wrong.get("cases")).get(0)).containsEntry("verdict", "Wrong answer").containsEntry("actual", "even");
    }

    @Test
    void compileErrorsAreShownNotHidden() {
        assumeTrue(LocalJudge.available("java"));
        Map<String, Object> r = runs.runTests("java", "class Solution {\n    int majority(int[] a) {\n        return a[0]\n    }\n}\n", tests.get(37));
        assertThat(r.get("statusId")).isEqualTo(6);
        assertThat(r.get("compileOutput").toString()).contains("';' expected");
        assertThat(r.get("allPassed")).isEqualTo(false);
    }

    @Test
    void customInputRunsTheWholeProgram() {
        assumeTrue(LocalJudge.available("python") && LocalJudge.available("java") && LocalJudge.available("cpp"));
        assertThat(runs.runStdin("python", "n = int(input())\nprint(\"Even\" if n % 2 == 0 else \"Odd\")\n", "7\n").get("stdout").toString().trim()).isEqualTo("Odd");
        assertThat(runs.runStdin("java", "import java.util.*;\npublic class Solution {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        System.out.println(sc.nextInt() * 2);\n    }\n}\n", "21").get("stdout").toString().trim()).isEqualTo("42");
        assertThat(runs.runStdin("cpp", "#include <iostream>\nint main() { int a, b; std::cin >> a >> b; std::cout << a + b; }\n", "2 3").get("stdout")).isEqualTo("5");
        assertThat(runs.runStdin("c", "int add(int a, int b) { return a + b; }\n", "").get("setupError").toString()).contains("main");
    }

    @Test
    void outputComparisonIsForgivingAboutFormattingOnly() {
        assertThat(RunService.matches("[1, 2, 3]", "[1,2,3]")).isTrue();
        assertThat(RunService.matches("True", "true")).isTrue();
        assertThat(RunService.matches("6.0", "6")).isTrue();
        assertThat(RunService.matches("null", "(empty)")).isTrue();
        assertThat(RunService.matches("5 -> 4", "5->4")).isTrue();
        assertThat(RunService.matches("even", "Even")).isFalse();
        assertThat(RunService.matches("7", "6")).isFalse();
    }
}
