/*
 * Exports the test cases, reference solutions and generated starter code of data/problem-details.js to
 * src/test/resources/problems/details.json for HarnessExecutionTest.
 *
 *   node backend/src/test/js/export-problems.cjs
 */
"use strict";
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "../../../..");
global.window = {};
require(path.join(root, "data/problems.js"));
require(path.join(root, "data/problem-details.js"));
require(path.join(root, "js/starters.js"));
const LANGS = ["c", "cpp", "java", "python", "javascript", "go"];
const out = Object.entries(window.CRX_DETAILS).map(([id, d]) => {
  const p = window.CRX_PROBLEMS[+id - 1];
  const starters = {};
  LANGS.forEach((l) => (starters[l] = window.CRX.Starters.forProblem(p, d.tests, l)));
  return { id: +id, tests: d.tests, solution: d.solution, starters };
});
const dest = path.resolve(__dirname, "../resources/problems/details.json");
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, JSON.stringify(out, null, 2) + "\n");
console.log("Wrote " + out.length + " problems to " + path.relative(root, dest));
