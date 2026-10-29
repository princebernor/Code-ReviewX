/*
 * Exports the test cases and reference solutions of data/problem-details.js to
 * src/test/resources/problems/details.json for HarnessExecutionTest.
 *
 *   node backend/src/test/js/export-problems.cjs
 */
"use strict";
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "../../../..");
global.window = {};
require(path.join(root, "data/problem-details.js"));
const out = Object.entries(window.CRX_DETAILS).map(([id, d]) => ({ id: +id, tests: d.tests, solution: d.solution }));
const dest = path.resolve(__dirname, "../resources/problems/details.json");
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, JSON.stringify(out, null, 2) + "\n");
console.log("Wrote " + out.length + " problems to " + path.relative(root, dest));
