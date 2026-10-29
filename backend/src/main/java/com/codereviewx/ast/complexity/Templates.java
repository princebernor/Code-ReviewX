package com.codereviewx.ast.complexity;

import java.util.Map;

/** Suggested-pattern templates, the same text the browser engine shows, so both engines read alike. */
final class Templates {
    private Templates() {}

    static final Map<String, String> HASH_LOOKUP = Map.of(
        "python", "# Template: replace the inner scan with a hash lookup\nseen = {}\nfor i, x in enumerate(items):\n    match = target - x          # what the inner loop was searching for\n    if match in seen:\n        handle(seen[match], i)\n    seen[x] = i",
        "javascript", "// Template: replace the inner scan with a hash lookup\nconst seen = new Map();\nitems.forEach((x, i) => {\n  const match = target - x;       // what the inner loop was searching for\n  if (seen.has(match)) handle(seen.get(match), i);\n  seen.set(x, i);\n});",
        "java", "// Template: replace the inner scan with a hash lookup\nMap<Integer, Integer> seen = new HashMap<>();\nfor (int i = 0; i < items.length; i++) {\n    int match = target - items[i];   // what the inner loop was searching for\n    if (seen.containsKey(match)) handle(seen.get(match), i);\n    seen.put(items[i], i);\n}",
        "cpp", "// Template: replace the inner scan with a hash lookup\nstd::unordered_map<int, int> seen;\nfor (int i = 0; i < (int)items.size(); ++i) {\n    int match = target - items[i];   // what the inner loop was searching for\n    if (auto it = seen.find(match); it != seen.end()) handle(it->second, i);\n    seen[items[i]] = i;\n}",
        "c", "/* Template: sort a copy, then walk two pointers inward (O(n log n), O(1) extra) */\nqsort(a, n, sizeof a[0], cmp_int);\nint lo = 0, hi = n - 1;\nwhile (lo < hi) {\n    int s = a[lo] + a[hi];\n    if (s == target) { handle(lo, hi); lo++; hi--; }\n    else if (s < target) lo++;\n    else hi--;\n}",
        "go", "// Template: replace the inner scan with a hash lookup\nseen := make(map[int]int)\nfor i, x := range items {\n    match := target - x // what the inner loop was searching for\n    if j, ok := seen[match]; ok {\n        handle(j, i)\n    }\n    seen[x] = i\n}");

    static final Map<String, String> MEMO = Map.of(
        "python", "from functools import lru_cache\n\n@lru_cache(maxsize=None)\ndef solve(n):\n    if n < 2:\n        return n\n    return solve(n - 1) + solve(n - 2)",
        "javascript", "const memo = new Map();\nfunction solve(n) {\n  if (n < 2) return n;\n  if (memo.has(n)) return memo.get(n);\n  const v = solve(n - 1) + solve(n - 2);\n  memo.set(n, v);\n  return v;\n}",
        "java", "long[] memo = new long[n + 1];\nArrays.fill(memo, -1);\nlong solve(int n) {\n    if (n < 2) return n;\n    if (memo[n] != -1) return memo[n];\n    return memo[n] = solve(n - 1) + solve(n - 2);\n}",
        "cpp", "std::vector<long long> memo(n + 1, -1);\nstd::function<long long(int)> solve = [&](int k) -> long long {\n    if (k < 2) return k;\n    if (memo[k] != -1) return memo[k];\n    return memo[k] = solve(k - 1) + solve(k - 2);\n};",
        "c", "long long memo[MAXN];  /* fill with -1 first */\nlong long solve(int n) {\n    if (n < 2) return n;\n    if (memo[n] != -1) return memo[n];\n    return memo[n] = solve(n - 1) + solve(n - 2);\n}",
        "go", "memo := map[int]int{}\nvar solve func(int) int\nsolve = func(n int) int {\n    if n < 2 { return n }\n    if v, ok := memo[n]; ok { return v }\n    memo[n] = solve(n-1) + solve(n-2)\n    return memo[n]\n}");
}
