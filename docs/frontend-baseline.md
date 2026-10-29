# Frontend baseline (before backend integration)

Recorded 2026-10-28 from the project folder as delivered. No ZIP archive was present: the project was already extracted at the workspace root, so that folder is treated as the authoritative frontend.

## Directory structure

```text
OOPS Project/
├── index.html          welcome page, sign-in form, demo entry (js/auth.js)
├── dashboard.html      module gateway (js/dashboard.js)
├── review.html         review workspace (js/review-engine.js + js/review.js)
├── placement.html      442-problem list (js/placement.js)
├── problem.html        single problem + editor + "Send to review" (js/problem.js)
├── history.html        review history   ┐
├── progress.html       practice progress├ js/pages.js, switched on <main data-page>
├── settings.html       preferences/data ┘
├── css/   styles.css (shared), auth.css, dashboard.css, placement.css, review.css
├── js/    main.js (shared CRX namespace), auth.js, charts.js, dashboard.js, pages.js,
│          placement.js, problem.js, review-engine.js, review.js
├── data/  problems.js (CRX_PROBLEMS, CRX_TOPICS), problem-details.js
├── assets/ hero-code-loop.mp4, hero-poster.jpg
└── README.md
```

There was no backend, build system, package manifest, test suite, environment file or API integration.

## How it launches

Static files opened directly (`index.html`) or from any static server. No build step. Google Fonts is the only network dependency.

## Script loading order (shared globals)

Every app page loads `data/problems.js` → (`data/problem-details.js`) → `js/main.js` → page scripts. `main.js` defines `window.CRX` (helpers, `store`, `shell`, `LANGS`, …). `review-engine.js` adds `window.CRX.Review`. The page scripts are IIFEs that read `window.CRX`.

| Page | Scripts after main.js |
|---|---|
| index.html | auth.js |
| dashboard.html | charts.js, review-engine.js, dashboard.js |
| review.html | charts.js, review-engine.js, review.js |
| history / progress / settings | charts.js, review-engine.js, pages.js |
| placement.html | placement.js |
| problem.html | problem.js |

## Session and navigation

* `CRX.store.startDemo(name)` writes `crx.session = {mode:"demo", name, since}`.
* `CRX.shell()` is the page guard: no session → `location.replace("index.html?next=…")`. It renders the sidebar ("Demo session" badge, "Exit demo" button) and topbar ("Frontend demo" badge).
* `index.html` sign-in (`#loginForm`, handler in `js/auth.js`) validates format only, then offers a demo session in a dialog. "Forgot password?" (`#forgotBtn`) and "Create an account" (`#createBtn`) open dialogs explaining the features are unavailable.

## localStorage schema (prefix `crx.`)

| Key | Shape |
|---|---|
| session | `{mode:"demo", name, since}` |
| prefs | `{theme:"dark"\|"light", lang}` |
| reviews | array (newest first, max 60) of the full `analyze()` result plus `{id, at, code, file, sample?}` |
| progress | `{ "<problemId>": {status:"solved"\|"attempted", at} }` (never downgrades solved → attempted) |
| activity | array (max 400) of `{type:"review"\|"solved"\|"attempted", ref, at}` |
| drafts | `{ "<problemId>:<lang>": code }` |
| handoff | `{code, lang, from}`; written by problem.js "Send to review", consumed once by review.js |
| rememberEmail | string (index.html "Remember my email") |

## Review engine (js/review-engine.js) — the function the UI calls

`CRX.Review.analyze(code, lang)` is called synchronously by `run()` in `js/review.js` (button `#reviewBtn`, or Ctrl/⌘+Enter).

Validation (returns `{error, message}`): `empty` (blank), `short` (< 3 non-blank lines), `long` (> 2,000 lines), `binary` (control characters `\u0000-\u0008`, `\u000E-\u001F`). Uploads are limited to 512 KB in `review.js`.

Analysis is regular-expression rules applied line by line, plus an indentation-based loop-nesting estimate and a regex recursion detector. It is not an AST.

Response consumed by `review.js`, `pages.js`, `dashboard.js`:

```text
{ engine, lang, lines, nonblank, overall, weighted, efficiency, time, space,
  categories: {correctness|security|time|quality|readability|practices|space: {score, n}},
  findings: [{uid, id, cat, sev, title, line, end, original, suggested, kind,
              explain, why, changes, benefits, tradeoffs, assumptions?, before?, after?, noPenalty?}],
  counts: {critical, high, medium, low, info}, sampleId, guessed, notes }
```

`kind` ∈ `line | template | note | rewrite` (only changes the "Suggested …" label).

### Scoring formula (authoritative, reproduced exactly by the backend)

* Category weights: correctness 0.22, security 0.15, time 0.15, quality 0.14, readability 0.14, practices 0.12, space 0.08.
* Penalties per finding: critical 32, high 20, medium 11, low 5, info 2. Findings flagged `noPenalty` (the nested-loop and branching-recursion findings) count toward `n` and `counts` but do not subtract.
* Time category starts from `TIME_BASE[rank] = [100, 99, 95, 90, 68, 46, 32]`; space from `[100, 92, 76][spaceRank]`; others start at 100. Each category is clamped to 10..100 after rounding.
* Time rank: exponential 6; ≥ n³ 5; n² 4; n log n 3; n 2; log n 1; constant 0.
* `weighted = Σ score × weight`; `overall = max(10, round(weighted − 8·critical − 3·high))`; the response reports `weighted` rounded.
* `efficiency = clamp(5, 99, EFF_BASE[rank] − 3·(time findings without noPenalty) − (spaceRank = 2 ? 4 : 0))`, `EFF_BASE = [97, 95, 88, 82, 58, 38, 20]`.
* Complexity: linear loop nesting depth from indentation; a loop is "logarithmic" if the next 8 lines mention `mid`, `/ 2`, `>>= 1`, `*= 2` …; findings with `bump` (linear call inside a loop) raise the depth by one; any sort with depth ≤ 1 gives n log n; ≥ 2 self-calls without memo → O(2ⁿ); single self-call with depth 0 → O(n). Space: O(n) when an allocation pattern matches, O(n²) for 2-D allocations, O(n) for recursion.

### Baseline results on the built-in samples (`node`, unmodified engine)

| Sample | overall | weighted | efficiency | time | space | counts (C/H/M/L/I) |
|---|---:|---:|---:|---|---|---|
| py-pairs | 81 | 87 | 58 | O(n²) | O(n) | 0/2/1/1/2 |
| js-dupes | 81 | 84 | 35 | O(n³) | O(n) | 0/1/3/2/1 |
| java-search | 83 | 89 | 95 | O(log n) | O(1) | 0/2/1/0/0 |
| c-greet | 69 | 83 | 55 | O(n²) | O(1) | 1/2/1/0/0 |
| cpp-pairs | 89 | 92 | 58 | O(n²) | O(1) | 0/1/1/3/0 |
| go-common | 89 | 92 | 58 | O(n²) | O(n) | 0/1/1/0/0 |

## Placement → review hand-off

`problem.js` "Send to review" writes `crx.handoff = {code, lang, from:"From practice #<id>"}`, marks the problem attempted, then navigates to `review.html`; `review.js` reads and deletes the key and preloads the editor and language.

## Pre-existing checks and issues

* `node --check` passes for all 11 scripts in `js/` and `data/`. There are no automated tests.
* The product name is spelled "Code ReveiwX" throughout the UI and README. This is preserved as-is (it is visible text).
* Several statements are true only for the frontend-only build ("There is no account server", "Your code never leaves this page", "Data stays in this browser"). See `docs/frontend-change-log.md` for how each is handled when a real account is used.
