# Integration map

Every row was verified against the delivered source. "Planned integration" describes what was implemented; see `docs/frontend-change-log.md` for the diffs.

| Area | Actual file / function | Existing behavior | Integration |
|---|---|---|---|
| Authentication | `js/auth.js` submit handler on `#loginForm`; `CRX.store.startDemo` in `js/main.js:55` | Format check only, then a dialog offering a local demo session. Forgot/Create buttons open "not available" dialogs. | When `/api/health` answers, the form calls `POST /api/auth/login`. 401 → message under the password field, 429 → rate-limit message, network/5xx → "couldn't reach the server" message; none of these create a session. Success writes `crx.session = {mode:"account", …}` via the new `store.startAccount`. Create/Forgot open real forms. With no backend the original demo dialog is shown unchanged. |
| Session / guard | `CRX.shell` in `js/main.js:199` | Requires any `crx.session`; renders "Demo session" / "Exit demo". | Unchanged for demo sessions. For `mode:"account"` the same elements read "Signed in" / "Sign out" and sign-out calls `POST /api/auth/logout`. Expired refresh tokens send the user back to sign-in. |
| Code review | `run()` in `js/review.js:150` → `CRX.Review.analyze` | Synchronous in-browser regex engine; result saved with `store.addReview`. | Demo sessions: unchanged (code stays in the browser, which the page states). Account sessions: `CRX.api.review()` → `POST /api/review` (AST engine, saved server-side), adapted to the same result shape, then the same `renderScore`/`renderResults`. Client-side input validation (same codes) runs first in both modes. |
| Scoring | `analyze()` scoring block, `js/review-engine.js:376-390` | Formula in `docs/frontend-baseline.md`. | Ported exactly to `com.codereviewx.review.scoring.Scorer`. `ScoringParityTest` runs the JS engine's own outputs (exported by `backend/src/test/js/export-parity.cjs`) through the Java scorer and the full Java pipeline. |
| Placement practice | `problem.js:132` writes `crx.handoff`; `review.js:175` reads it | Code + language move to the review page. | Unchanged. In account mode the hand-off code is reviewed by the backend when the user clicks Review. `store.setStatus/clearStatus` are wrapped so changes also `PUT /api/progress`. |
| History | `store.reviews/addReview/setReviews` (`js/main.js:59-62`), `pages.js` history | localStorage list, max 60. | Account mode: list is a cache of `GET /api/reviews`, refreshed after sign-in and on each page load. `setReviews` is wrapped: removed ids → `DELETE /api/reviews/{id}`, added ids (e.g. sample records) → `POST /api/reviews`. |
| Progress | `store.progress/setStatus/activity` | localStorage map + activity log. | Account mode: merged with `GET /api/progress` (newest `at` wins, solved never downgraded, activity unioned), written back with `PUT /api/progress`. Local data is never discarded: at first sign-in the user is asked to import it; if declined it is kept in `crx.demoBackup` and restored on sign-out. |
| Languages | `CRX.LANGS` in `js/main.js:99` | c, cpp, java, python, javascript, go. | Backend supports exactly these six (`GET /api/languages`). Java uses JavaParser with tree-sitter-java as a partial-tree fallback; the other five use tree-sitter. |
| Configuration | none | Static files only. | `backend/src/main/resources/application*.yml` with `dev` (file H2) and `prod` (PostgreSQL) profiles; secrets only via environment variables (`backend/.env.example`). Spring Boot serves the frontend from an explicit allow-list of paths. |

## Frontend → backend contract

| Operation | Trigger | Request | Response the UI uses | Errors handled |
|---|---|---|---|---|
| Probe | any page load (cached per tab) | `GET /api/health` | `{status:"UP"}` | network error → treated as "no backend" |
| CSRF | first unsafe request | `GET /api/auth/csrf` | `XSRF-TOKEN` cookie | — |
| Sign in | `#loginForm` submit | `{email, password}` | `{user:{id,email,name}}` + HttpOnly cookies | 400 validation, 401 `invalid_credentials`, 429 `rate_limited`, 5xx |
| Register | Create-account dialog | `{name, email, password}` | `201 {user}` + cookies | 400 `weak_password`/validation, 409 `registration_failed`, 429 |
| Forgot | Forgot dialog | `{email}` | `202 {message}` (+ `devResetToken` only when `crx.auth.expose-reset-token=true`) | 429 |
| Reset | `index.html?reset=<token>` dialog | `{token, password}` | `204` | 400 `invalid_token` / `weak_password` |
| Review | `#reviewBtn` (account mode) | `{code, lang, filename, save:true}` | result shape above + `id`, `at`, `ruleId`, `confidence`, `ast`, `diagnostics`, `complexity` | 400 `empty`/`short`/`long`/`binary`/`unsupported_language`/`too_deep`, 413, 429, 503 `busy`, 504 `timeout` |
| History | sign-in, page load, delete | `GET/POST/DELETE /api/reviews…` | `{items:[review], page, size, total}` | 401 → sign-in |
| Progress | sign-in, status change | `GET/PUT /api/progress` | `{progress:{}, activity:[], updatedAt}` | 401 → sign-in |
