# Code ReveiwX: frontend demo

Open `index.html` in a browser. No build step or server is needed. An internet connection is only used for Google Fonts, and the site falls back to system fonts without it.

| Page | Purpose |
|---|---|
| index.html | Welcome page with background video, honest sign-in form, and demo entry |
| dashboard.html | Gateway to both modules (AI Code Review first) |
| review.html | Code review workspace (flagship) |
| placement.html / problem.html | Placement practice: all 442 sheet problems |
| history.html, progress.html, settings.html | Review history, progress tracking, preferences |

## What's simulated
- **Reviews** are rule-based checks in `js/review-engine.js` (pattern rules + loop-nesting analysis). They are not AI analysis, and the UI labels them as demonstrations.
- **Code is never compiled or run.** "Run" shows the expected outputs of the sample tests.
- **Sign-in** opens a local demo session. Credentials are checked for format only, and nothing is sent or stored.
- **Progress** is saved in localStorage and starts at zero.

## Data
- `data/problems.js` is generated from *CodeSheet File.pdf*: 442 problems, 18 topics, the sheet's notes and practice links (424 of the 442 have a link). Difficulty labels are this app's estimates.
- `data/problem-details.js` holds full statements, tests and solutions for 11 well-known problems. The rest link to their source.

## Optional backend

`backend/` adds real accounts, an AST-based review engine and server-side history/progress. Run `./run.sh` (or `cd backend && mvn spring-boot:run`) and open http://localhost:8080. Without it, everything above still applies. See [backend/README.md](backend/README.md).
