/*
 * Runs the unmodified browser engine (js/review-engine.js) over the parity corpus and writes its results to
 * src/test/resources/parity/frontend-results.json, which ScoringParityTest compares against the Java engine.
 *
 *   node backend/src/test/js/export-parity.cjs
 */
"use strict";
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "../../../..");
global.window = {};
require(path.join(root, "js/review-engine.js"));
const R = window.CRX.Review;

const extra = [
  { id: "py-fib", lang: "python", code: "def fib(n):\n    if n < 2:\n        return n\n    return fib(n - 1) + fib(n - 2)\n\nprint(fib(30))\n" },
  { id: "py-binsearch", lang: "python", code: "def search(a, t):\n    lo, hi = 0, len(a) - 1\n    while lo <= hi:\n        mid = (lo + hi) // 2\n        if a[mid] == t:\n            return mid\n        if a[mid] < t:\n            lo = mid + 1\n        else:\n            hi = mid - 1\n    return -1\n" },
  { id: "py-sort", lang: "python", code: "def dedupe(items):\n    items = sorted(items)\n    out = []\n    for x in items:\n        if not out or out[-1] != x:\n            out.append(x)\n    return out\n" },
  { id: "py-clean", lang: "python", code: "def total(values):\n    result = 0\n    for v in values:\n        result += v\n    return result\n" },
  { id: "js-clean", lang: "javascript", code: "function sum(items) {\n  let total = 0;\n  for (const x of items) {\n    total += x;\n  }\n  return total;\n}\n" },
  { id: "java-clean", lang: "java", code: "public class Sum {\n    static int sum(int[] a) {\n        int s = 0;\n        for (int x : a) {\n            s += x;\n        }\n        return s;\n    }\n}\n" },
  { id: "c-clean", lang: "c", code: "#include <stdio.h>\n\nint sum(const int *a, int n) {\n    int s = 0;\n    for (int i = 0; i < n; i++) {\n        s += a[i];\n    }\n    return s;\n}\n" },
  { id: "go-clean", lang: "go", code: "package main\n\nfunc sum(a []int) int {\n    s := 0\n    for _, x := range a {\n        s += x\n    }\n    return s\n}\n" },
  { id: "cpp-triple", lang: "cpp", code: "#include <vector>\n\nint triples(const std::vector<int>& a) {\n    int c = 0;\n    for (size_t i = 0; i < a.size(); i++) {\n        for (size_t j = i + 1; j < a.size(); j++) {\n            for (size_t k = j + 1; k < a.size(); k++) {\n                if (a[i] + a[j] + a[k] == 0) c++;\n            }\n        }\n    }\n    return c;\n}\n" },
  { id: "js-secret", lang: "javascript", code: "const apiKey = \"sk_live_123456\";\nfunction load(id) {\n  const q = \"SELECT * FROM users WHERE id = \" + id;\n  return db.query(q);\n}\n" }
];

const cases = R.SAMPLES.map((s) => ({ id: s.id, lang: s.lang, code: s.code })).concat(extra);
const out = cases.map((c) => {
  const r = R.analyze(c.code, c.lang);
  return {
    id: c.id, lang: c.lang, code: c.code,
    overall: r.overall, weighted: r.weighted, efficiency: r.efficiency, time: r.time, space: r.space,
    counts: r.counts, categories: r.categories,
    findings: r.findings.map((f) => ({ id: f.id, cat: f.cat, sev: f.sev, line: f.line, noPenalty: !!f.noPenalty }))
  };
});
const dest = path.resolve(__dirname, "../resources/parity/frontend-results.json");
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, JSON.stringify(out, null, 2) + "\n");
console.log("Wrote " + out.length + " cases to " + path.relative(root, dest));
