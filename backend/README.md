# CodeReviewX backend

Java 21 / Spring Boot 3.5 backend for the existing Code ReveiwX frontend. It adds real accounts, an AST-based static-analysis review engine, and per-user history and progress. It also serves the unchanged static frontend.

* Frontend baseline and integration contract: [`docs/frontend-baseline.md`](../docs/frontend-baseline.md), [`docs/integration-map.md`](../docs/integration-map.md)
* Every frontend edit, with diffs: [`docs/frontend-change-log.md`](../docs/frontend-change-log.md)

## Run it

Requirements: JDK 21+ (built and tested here with JDK 25 targeting release 21) and Maven 3.9+. No database install is needed for development.

```bash
./run.sh
```

Or `cd backend && mvn spring-boot:run`. Then open **http://localhost:8080**. Use `PORT=8081 ./run.sh` if 8080 is taken. On Windows PowerShell, run `cd backend; $env:PORT=8081; mvn spring-boot:run`.

* Profile `dev` (default) uses a file H2 database in `backend/.data/` and a random JWT key per run. Sessions end on restart unless you set `CRX_JWT_SECRET`. In dev, "Forgot password?" shows the reset link in a dialog because no email service is configured.
* Profile `prod` uses PostgreSQL. It refuses to start without `CRX_JWT_SECRET`, uses Secure cookies, and never exposes reset tokens.
* Opening the HTML files directly (or from any static server without `/api`) still gives the original demo-only site.

### Production with Docker

```bash
cp backend/.env.example backend/.env
docker compose --env-file backend/.env up --build
```

Edit `backend/.env` before running. Serve the app behind HTTPS: cookies are `Secure` in prod, so plain HTTP will not keep a session. Docker was not available on the build machine, so the Dockerfile and compose file are **untested**.

### Configuration (environment variables)

| Variable | Purpose |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` (default) or `prod` |
| `PORT` | HTTP port (8080) |
| `CRX_JWT_SECRET` | HS256 key, at least 32 bytes. Required in prod |
| `CRX_DB_URL`, `CRX_DB_USER`, `CRX_DB_PASSWORD` | PostgreSQL (prod) |
| `CRX_DB_PATH` | H2 file path (dev), default `./.data/crx-dev` |
| `CRX_FRONTEND_DIR` | Folder holding `index.html`, default `..` |
| `CRX_RESET_LINK_BASE` | Base URL for reset links |
| `CRX_ALLOWED_ORIGINS` | Comma-separated exact origins, only when the frontend runs on another origin (enables CORS with credentials) |
| `CRX_FORWARD_HEADERS` | `native` behind a TLS proxy |
| `CRX_JUDGE_ENABLED` | Practice code execution on/off (default on) |
| `CRX_JUDGE_URL` | Judge0 base URL, default the public `https://ce.judge0.com` (no key needed). Use your own Judge0 for anything beyond personal use |
| `CRX_JUDGE_API_KEY`, `CRX_JUDGE_API_KEY_HEADER`, `CRX_JUDGE_RAPIDAPI_HOST` | Credentials for a private or RapidAPI Judge0 (`X-Auth-Token`, or `X-RapidAPI-Key` + host `judge0-ce.p.rapidapi.com`) |

Other tunables (`crx.*` in `application.yml`): token lifetimes (access 15 min, refresh 7 days absolute), BCrypt strength 12, rate limits, review limits (2,000 lines, 512 KB, 5 s timeout, 4 workers), history cap (500 per user), `crx.history.store-source`.

No secret is in source, config defaults or the frontend. The API key mentioned in the original request was **not** used anywhere; if it was real, rotate it.

## Security model

* Passwords use BCrypt(12). The minimum is 8 characters (matching the sign-in form), the maximum 72 bytes (BCrypt's limit, rejected rather than silently truncated). At least two character classes are required, and a common-password list and the email name are rejected.
* Access token: 15-minute HS256 JWT in an `HttpOnly`, `SameSite=Strict` cookie scoped to `/api`. JavaScript never sees a token.
* Refresh token: random 256-bit value in an `HttpOnly` cookie scoped to `/api/auth`. Only its SHA-256 is stored. Every refresh rotates it within a family that keeps the original 7-day expiry. Replaying a rotated token revokes the whole family (with a 5-second grace window for two tabs refreshing at once).
* CSRF: double-submit token (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header) on every state-changing request. The token rotates after authentication, so clients read the cookie per request.
* Login, register, forgot, reset and review are rate-limited (Bucket4j, in-memory: per IP, plus per email for login). Login takes the same BCrypt time for unknown emails. Forgot always returns the same 202 message.
* Password reset tokens are single-use, last 30 minutes, are stored hashed, and a reset revokes all sessions. Delivery goes through `PasswordResetNotifier`; the default only logs that a reset happened, never the token.
* Every history/progress query is scoped by the authenticated user id. Another user's review id returns 404.
* Static serving is an allow-list (`/*.html`, `css/`, `js/`, `data/`, `assets/`). README, docs, backend sources, `.env` and the database return 404. Responses carry a strict CSP (`script-src 'self'`), `X-Frame-Options: DENY` and `nosniff`.
* **Code execution (Placement Practice Run/Submit)** never happens on this server. The code is sent to the configured Judge0 instance (by default the public `ce.judge0.com`, a third party), with 5 s CPU / 10 s wall / 256 MB limits and networking disabled. Only signed-in users can run code (10 runs per minute each, 100 KB code, 20 cases), and the UI says the code was sent to an external service. Demo sessions never run code.
* The **review engine** only parses code. Nothing in it compiles, interprets or executes code (covered by a test that would detect file writes from Python, JS, Java static initialisers, C `system()` and Go `init()`).

## API

All bodies are JSON. Errors are `{"error": code, "message": text, "status": n}`.

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/health` | – | `{status:"UP"}`, used by the frontend to detect the server |
| GET | `/api/auth/csrf` | – | issues `XSRF-TOKEN` |
| POST | `/api/auth/register` | – | `{name?, email, password}` → 201 `{user}` + cookies. 400 `weak_password`/`validation`, 409 `registration_failed`, 429 |
| POST | `/api/auth/login` | – | `{email, password}` → `{user}` + cookies. 401 `invalid_credentials`, 429 `rate_limited` |
| POST | `/api/auth/refresh` | refresh cookie | rotates → `{user}`. 401 `session_reused`/`session_expired`/`invalid_session` |
| POST | `/api/auth/logout` | – | revokes the refresh family, clears cookies, 204 |
| GET | `/api/auth/me` | ✓ | `{user:{id,email,name}}` |
| POST | `/api/auth/forgot` | – | `{email}` → 202 generic message (+ `devResetToken` in dev only) |
| POST | `/api/auth/reset` | – | `{token, password}` → 204. 400 `invalid_token`/`weak_password` |
| GET | `/api/languages` | – | the six languages with parser and availability |
| POST | `/api/review` | optional | `{code, lang, filename?, save?, explain?}` → review result in the UI's shape (below). Saved to history only when signed in and `save:true`. 400 `empty`/`short`/`long`/`binary`/`too_deep`/`unsupported_language`, 413 `too_large`, 429, 503 `busy`, 504 `timeout` |
| POST | `/api/ast` | – | `{code, lang, maxDepth?}` → unified AST (≤ 5,000 nodes) + diagnostics |
| POST | `/api/compare` | – | `{a:{code,lang}, b:{code,lang}}` → structural similarity 0..1 and fingerprints |
| GET | `/api/reviews?page&size` | ✓ | `{items, page, size, total}`, newest first. Items have the browser-history shape |
| POST | `/api/reviews` | ✓ | import a review the client already has (demo data, sample records). Shape- and size-validated |
| GET / DELETE | `/api/reviews/{id}` | ✓ | owner only (404 otherwise) |
| DELETE | `/api/reviews` | ✓ | delete all of the caller's reviews |
| POST | `/api/run` | ✓ | `{lang, code, mode:"tests"|"stdin", cases?:[{in,out}], stdin?}` → `{judge, status, statusId, compileOutput, stderr, stdout, time, memoryKb, cases:[{input, expected, actual, verdict, passed, error?}], passed, total, allPassed}`, or `{setupError}` when no runnable program can be built. 429 `rate_limited`, 503 `judge_busy`/`judge_unavailable`/`judge_disabled`, 504 `judge_timeout` |
| GET | `/api/run/info` | ✓ | judge name, enabled flag, languages |
| GET / PUT | `/api/progress` | ✓ | `{progress:{"<id>":{status,at}}, activity:[{type,ref,at}], version?}`. A stale `version` → 409 |

Review result fields: `engine` (`ast-v1`), `lang`, `lines`, `nonblank`, `overall`, `weighted`, `efficiency`, `time`, `space`, `categories{id:{score,n}}`, `counts`, `findings[]` (`uid, id, ruleId, cat, sev, title, line, end, col, endCol, original, suggested, kind, explain, why, changes, benefits, tradeoffs, assumptions?, before?, after?, noPenalty?, confidence, source, ast{path,nodeType,kind}, fixAvailable`), `guessed`, `parser`, `partial`, `diagnostics[]`, `complexity{time,space,confidence,evidence[],note}`, `fingerprint`, `explanation?`.

## Analysis engine

```text
code ─▶ InputValidator ─▶ LanguageModule.parse ─▶ unified AST (UNode) ─▶ RuleEngine (57 rules + DataFlow)
                                                                    └─▶ ComplexityAnalyzer ─▶ Scorer ─▶ result
```

* **Parsers.** Java uses JavaParser 3.27 (snippets without a class are parsed in a synthetic wrapper that is removed again). If JavaParser can't build a tree, tree-sitter-java recovers a partial one. C, C++, Python, JavaScript and Go use tree-sitter grammars via `io.github.bonede:tree-sitter` 0.26.6 (JNI, bundled natives for Windows/Linux/macOS x86-64 and Linux/macOS arm64).
* **Unified AST** (`ast.model`): 41 language-neutral kinds with normalized roles (`params`, `cond`, `then`, `callee`, `args`, …), 1-based spans, char offsets and paths like `Function[two_sum] > For[0] > For[0] > If[0]`.
* **Rules** (`ast.rules`): 10 security, 18 correctness (including undefined names in Python and missing returns in Python/JavaScript), 4 time, 4 space, 10 quality, 2 readability and 9 best-practice rules. Each finding has the exact source span, the AST path, a confidence, and a fix built from span edits when a safe edit exists (otherwise a template or note). `readability.long-line` is the only text-metric rule (`source:"text"`). Every parser error also becomes a high-severity `correctness.syntax-error` finding (`source:"parser"`, up to 3 per review), so code that would not run cannot score 100.
* **Data flow** (`ast.dataflow`): intra-procedural definitions, uses and taint from recognised input sources (e.g. `input()`, `req.query`, `scanner.nextLine()`, `scanf`, `argv`). It raises confidence for SQL/command/eval findings and drives use-after-free, double-free, unchecked allocation and null-dereference.
* **Complexity**: structural loop nesting (literal-bounded loops count as O(1)), halving/doubling loops (log), linear library calls in loops, sorts, and recursion (calls counted per execution path, with memoisation and halving detected). Auxiliary space comes from allocation sites. Every estimate lists evidence marked *structural* or *heuristic*, plus a confidence.
* **Scoring** (`review.Scorer`): an exact port of the browser formula. See *Test results*.

## Test results

`mvn verify` on Windows 11, JDK 25 (release 21), 2026-10-28: **302 tests, 0 failures, 2 skipped, BUILD SUCCESS**. The skips are `go` in the local harness test (Go isn't installed on the build machine) and the opt-in live Judge0 test; run separately with `CRX_LIVE_JUDGE=1`, it passed for all six languages, including Go.

| Suite | Tests | Covers |
|---|---:|---|
| `AuthApiTest` | 9 | register (HttpOnly cookies, no hashes returned), duplicate/weak/invalid input, login, identical responses for wrong vs unknown accounts, CSRF required, expired and forged JWTs, refresh rotation and reuse revocation, logout revocation, single-use reset that signs out everywhere, rate limiting |
| `ApiFlowTest` | 10 | languages, anonymous vs saved review, frontend error codes, 413, owner-only history (404 for others), import validation, per-user progress + 409, `/api/ast`, `/api/compare`, explanation mode, static allow-list + CSP, no secrets in browser files |
| `RuleCorpusTest` | 173 | every one of the 57 rules: positive, negative and partial-syntax cases; span-based fix check; catalog completeness |
| `AnalysisTest` | 28 | missing return in the built-in sample, undefined Python names (incl. a no-false-positive program using every binding form), syntax errors as findings in all six languages; all six parsers normalize functions, loops, branches, paths and spans; 10 complexity cases across languages; syntax-error recovery in all six; Java snippets; non-ASCII columns; validation codes; **no execution**; fingerprints |
| `HarnessExecutionTest` | 44 | the generated test programs, compiled and run with the local Python, Node, javac, gcc and g++: every reference solution in `data/problem-details.js` plus 18 user-style programs (LeetCode-style classes, C arrays + length, linked lists, grids, strings) pass; wrong answers, exceptions, compile errors and custom-input runs are reported correctly |
| `RunApiTest` | 4 | `/api/run` needs an account, per-case grading, setup and compile errors explained, per-user rate limit (fake judge, no network) |
| `LiveJudgeTest` | 6 (opt-in) | one reference solution per language on the real Judge0 CE |
| `ScoringParityTest` | 33 | against the **unmodified** `js/review-engine.js` run in Node (`src/test/js/export-parity.cjs`) on 16 snippets (the 6 built-in samples + 10 more) |

**Parity.** With identical findings, the Java scorer reproduces the frontend exactly (overall, weighted, efficiency, counts, every category) in all 16 cases. Run end to end, the AST engine matches the regex engine exactly on all 16: same overall, weighted, efficiency, time, space and severity counts. The required tolerance was 3 points; the observed difference is 0. Regenerate the fixture after changing the browser engine with `node backend/src/test/js/export-parity.cjs`.

The integrated UI was also exercised in a browser against the running server: wrong password (error shown, no session), sign-in, server review rendered in the existing panels, practice → review hand-off, progress and history-delete sync, sign-out revocation, and demo mode making zero API calls.

## Feature status

**Implemented and tested:** everything in the tables above.

**Practice execution**: verified in the browser against the real Judge0 CE (Python and C++ tests, C++ wrong answer → Attempted, fix → Solved, Java custom input). Supported test-argument types: integers, floats, booleans, strings, 1-D/2-D arrays and lists, and linked lists. C/C++ pointer arrays get their length passed automatically.

**Known data issue (frontend, not changed):** `data/problem-details.js` #265 *Number of islands* has number grids in its test cases, but its Python reference solution compares cells with the string `'1'`. Run on the real judge, it fails (0 islands); the same code comparing with `1` passes. Fix one or the other in the data file.

**Implemented, not fully verified:**
* Production deployment: PostgreSQL migration compatibility is by design (`V1__init.sql` uses portable SQL and runs on H2 in PostgreSQL mode) but was not run against a real PostgreSQL. The Dockerfile and compose file are unbuilt because Docker isn't installed here.
* The demo-data import dialog at first sign-in and the session-expired redirect were not exercised in the browser.

**Partial or heuristic by nature:**
* Complexity is an estimate from structure. It assumes non-literal loop bounds grow with the input, doesn't know callee costs beyond a small list of library calls, and labels recursion low-confidence.
* Data flow is intra-procedural: no aliasing, no field or container sensitivity, branches merged. A taint verdict means "derived from a recognised input", never "exploitable".
* Use-after-free and null-dereference only report straight-line cases they can establish, so they miss many real bugs (by design, to avoid false alarms).

**Not implemented (planned):**
* LLM explanations: `ExplanationProvider` exists, but only the deterministic provider is implemented. `crx.llm.enabled=true` logs a warning and changes nothing.
* Email delivery for reset links (interface only).
* Shared rate-limit store for multi-instance deployments.
* Hidden test cases: Submit uses the same visible cases as Run, and progress is client-reported, so *Solved* is a self-check, not a verified judgement.
* Automatic tests for the 431 problems without test data (they get custom-input runs only).
* Additional languages (TypeScript, Kotlin, Rust, C#, PHP, Ruby, Swift): add a `LanguageModule`. tree-sitter grammars exist on Maven Central for several of them.

## Frontend statements that are now outdated (left unchanged: visible copy)

These are accurate for demo mode, but no longer for signed-in users or when the server is running. The ones that would have been actively false for a signed-in user were made conditional (see the change log). The rest are listed here for a copy update.

| Where | Statement |
|---|---|
| `index.html:73` | "Frontend-only demo. There is no account server…" (replaced at runtime when the server answers) |
| `index.html:209-215` | "frontend-only build with no backend, database or AI model"; reviews are "rule-based checks… not AI analysis"; "No credentials are verified, sent, or stored"; progress "saved in this browser's storage" |
| `index.html:224` | footer: "Frontend demonstration. No real AI analysis takes place." |
| `history.html:21` | "Every review you run is saved in this browser." |
| `progress.html:21` | progress saved "in this browser" |
| `review.html:29` | demo review notice (replaced at runtime for account sessions) |
| `js/dashboard.js:32` | "Reviews in this demo are rule-based checks that run in your browser" |
| `js/pages.js:69-76` | Settings: theme "Saved in this browser"; "About this build… has no backend, database, account system…" |
| `js/pages.js:74,89` | "Reset all demo data… in this browser": for account sessions this also deletes the account's server history and progress |
| `README.md:14-16` | "What's simulated" section |
| Product name | "Code ReveiwX" is misspelled throughout (kept as found) |

## Optional UI improvements (proposals only, not implemented)

1. Show each finding's `confidence` and `source` (AST / data flow / text) next to the "Sample finding" badge.
2. Show the complexity `evidence` list (line + path + structural/heuristic) under the time/space estimates.
3. Display `diagnostics` when `partial` is true ("Analysed a partial tree: syntax error on line 7").
4. An "Apply suggestion" button for `kind:"line"` fixes, and a parse-tree viewer backed by `/api/ast`.
5. Account-aware copy for the History, Progress, Settings and landing-page statements listed above.
6. Re-render History/Progress when `crx:synced` fires (today a background sync shows on the next page view).
