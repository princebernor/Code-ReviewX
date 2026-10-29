# Frontend change log

No CSS, layout, colours, fonts, images, IDs, navigation or placement content changed. No framework or build step was added. Every change is either a new script or a branch that runs **only** when a real account session exists or the account server answers; with no backend, the site behaves exactly as before (verified: demo review makes zero `/api` requests).

| File | Change | Why it is necessary | Preserved |
|---|---|---|---|
| `js/api-client.js` (new) | `run()` for practice execution; fetch wrapper (CSRF header, cookie auth, single-flight token refresh, timeouts), sign-in/register/reset calls, review call + result adapter, history/progress sync by wrapping `store.setReviews/addReview/setStatus/clearStatus/resetAll` for account sessions only | Connects the existing store-driven pages to the server without editing them | Demo sessions: wrappers delegate straight to the originals |
| 8 × `*.html` | One `<script src="js/api-client.js">` line after `main.js` | Loads the client on every page | Markup, order of existing scripts |
| `js/main.js` | `store.startAccount()`; `shell()` shows "Signed in / Sign out / sync" and a "Server analysis" topbar badge for account sessions, and signs out through the API | Without it a real user would be labelled "Demo session" and "Data stays in this browser", which is false | Demo text, classes and elements unchanged |
| `js/auth.js` | Real sign-in when `/api/health` answers; 401 / 429 / network / 5xx handled separately in the existing `#pwErr`, never creating a session; create-account and reset forms in the existing dialog; accurate sign-in notice when a server is connected; optional import of demo data at first sign-in | Real authentication through the existing form, as required | No server → the original demo dialogs, unchanged |
| `js/review.js` | Account sessions call `POST /api/review` in parallel with the existing step animation and render the result with the existing `renderScore/renderResults`; score badge says "AST analysis" for server results; review notice text corrected for account sessions | Review button calls the backend; the old notice ("Your code never leaves this page") would be false | Demo sessions keep the in-browser engine and original notice |
| `js/problem.js` | For account sessions: **Run** sends the code and the selected test cases (or the custom input) to `POST /api/run` and shows each case's expected output, actual output and verdict, plus compile errors, stderr, time and memory; **Submit** runs every test case and marks the problem *Solved* only if all pass (otherwise *Attempted*). Adds a "Custom input (stdin)" box to the Test cases tab. Output says the code ran on an external service | Real compiler/runtime under Placement Practice, as requested | Demo sessions keep the original "Not executed" demonstration |

## Diffs

### `js/main.js`

```diff
--- a/js/main.js
+++ b/js/main.js
@@ -53,6 +53,8 @@
   const store = {
     session: () => get("session", null),
     startDemo(name) { set("session", { mode: "demo", name: name || "Demo user", since: Date.now() }); },
+    // Real account (js/api-client.js). Tokens live in HttpOnly cookies, never here.
+    startAccount(u) { set("session", { mode: "account", name: u.name || "User", email: u.email, userId: u.userId, since: u.since || Date.now() }); },
     endDemo() { del("session"); },
     prefs: () => Object.assign({ theme: "dark", lang: "python" }, get("prefs", {})),
     setPref(k, v) { const p = store.prefs(); p[k] = v; set("prefs", p); },
@@ -201,12 +203,13 @@
     if (!session) { location.replace("index.html?next=" + encodeURIComponent(location.pathname.split("/").pop() + location.search)); return false; }
     const app = $(".app");
     const nav = NAV.map((n) => '<a class="nav-link' + (n.flagship ? " flagship" : "") + '" href="' + n.href + '"' + (n.key === opts.active ? ' aria-current="page"' : "") + ">" + icon(n.ic) + "<span>" + n.label + "</span>" + (n.tag ? '<span class="nav-tag">' + n.tag + "</span>" : "") + "</a>").join("");
+    const account = session.mode === "account";
     const side = document.createElement("aside"); side.className = "sidebar"; side.id = "sidebar"; side.setAttribute("aria-label", "Primary");
     side.innerHTML = brandHTML("index.html") +
       '<nav aria-label="Main navigation" style="display:grid;gap:2px">' + nav + "</nav>" +
-      '<div class="sidebar-foot"><span class="badge badge-demo" style="justify-self:start"><span class="dot"></span>Demo session</span>' +
-      "<p>Signed in as <strong style=\"color:var(--text-2);font-weight:550\">" + esc(session.name) + "</strong>. Data stays in this browser.</p>" +
-      '<button class="btn btn-sm btn-ghost" type="button" id="exitDemo" style="justify-self:start;padding:0 8px">' + icon("logout") + "Exit demo</button></div>";
+      '<div class="sidebar-foot"><span class="badge badge-demo" style="justify-self:start"><span class="dot"></span>' + (account ? "Signed in" : "Demo session") + "</span>" +
+      "<p>Signed in as <strong style=\"color:var(--text-2);font-weight:550\">" + esc(session.name) + "</strong>. " + (account ? "Reviews and progress sync to your account." : "Data stays in this browser.") + "</p>" +
+      '<button class="btn btn-sm btn-ghost" type="button" id="exitDemo" style="justify-self:start;padding:0 8px">' + icon("logout") + (account ? "Sign out" : "Exit demo") + "</button></div>";
     app.prepend(side);
     const scrim = document.createElement("div"); scrim.className = "scrim"; app.appendChild(scrim);
 
@@ -214,7 +217,9 @@
     top.innerHTML = '<button class="btn btn-ghost btn-icon menu-btn" type="button" aria-label="Open navigation" aria-controls="sidebar" aria-expanded="false" id="menuBtn">' + icon("menu") + "</button>" +
       (opts.crumb ? '<a class="topbar-crumb" href="' + opts.crumb.href + '">' + esc(opts.crumb.label) + "</a><span class=\"faint\" aria-hidden=\"true\">/</span>" : "") +
       '<span class="topbar-title">' + esc(opts.title) + "</span>" +
-      '<div class="topbar-actions"><span class="badge badge-demo hide-sm" title="All results in this app are demonstrations"><span class="dot"></span>Frontend demo</span>' +
+      '<div class="topbar-actions">' + (account
+        ? '<span class="badge badge-demo hide-sm" title="Reviews run on the CodeReviewX server (AST analysis)"><span class="dot"></span>Server analysis</span>'
+        : '<span class="badge badge-demo hide-sm" title="All results in this app are demonstrations"><span class="dot"></span>Frontend demo</span>') +
       '<button class="btn btn-ghost btn-icon" type="button" id="themeBtn" aria-label="Toggle light and dark theme">' + icon(store.prefs().theme === "light" ? "moon" : "sun") + "</button>" +
       (opts.active !== "review" ? '<a class="btn btn-sm btn-accent hide-sm" href="review.html">' + icon("review") + "New review</a>" : "") + "</div>";
 
@@ -229,6 +234,11 @@
       document.dispatchEvent(new CustomEvent("crx:theme"));
     });
     $("#exitDemo").addEventListener("click", () => {
+      if (account) {
+        dialog({ title: "Sign out?", body: "<p>Your reviews and progress stay saved in your account.</p>",
+          actions: [{ label: "Cancel" }, { label: "Sign out", primary: true, onClick: () => { window.CRX.api.logout().then(() => { location.href = "index.html"; }); } }] });
+        return;
+      }
       dialog({ title: "Exit demo session?", body: "<p>You'll return to the welcome page. Your demo reviews and practice progress stay saved in this browser until you clear them in Settings.</p>",
         actions: [{ label: "Cancel" }, { label: "Exit demo", primary: true, onClick: () => { store.endDemo(); location.href = "index.html"; } }] });
     });
```

### `js/auth.js`

```diff
--- a/js/auth.js
+++ b/js/auth.js
@@ -20,7 +20,77 @@
   const s = store.session();
   if (s) {
     const head = $(".auth-head");
-    head.insertAdjacentHTML("afterend", '<div class="auth-result"><p class="muted" style="font-size:14px">A demo session for <strong style="color:var(--text)">' + esc(s.name) + '</strong> is already open in this browser.</p><a class="btn btn-accent" href="' + defaultNext + '">Continue to dashboard</a></div>');
+    const who = s.mode === "account" ? "You're signed in as <strong style=\"color:var(--text)\">" + esc(s.name) + "</strong>."
+      : 'A demo session for <strong style="color:var(--text)">' + esc(s.name) + "</strong> is already open in this browser.";
+    head.insertAdjacentHTML("afterend", '<div class="auth-result"><p class="muted" style="font-size:14px">' + who + '</p><a class="btn btn-accent" href="' + defaultNext + '">Continue to dashboard</a></div>');
+  }
+
+  /* ---------- Account server (optional backend, js/api-client.js) ---------- */
+  const api = window.CRX.api;
+  const serverUp = api ? api.available() : Promise.resolve(false);
+  serverUp.then((up) => {
+    if (!up) return;
+    // The default notice describes the frontend-only build; it isn't true when a server answers.
+    $("#signin .notice span").innerHTML = "<strong>Account server connected.</strong> Sign in to save reviews and progress to your account. Demo mode still keeps everything in this browser.";
+    const token = params.get("reset");
+    if (token) resetDialog(token);
+  });
+
+  function goNext(next) { location.href = safeNext(next) || defaultNext; }
+
+  /** After a real sign-in: offer to copy this browser's demo data into the account, then continue. */
+  function afterSignIn() {
+    const demo = api.pendingDemo();
+    if (!demo) { goNext(); return; }
+    const nr = demo.reviews.filter((r) => !r.sample).length, np = Object.keys(demo.progress).length;
+    let leaving = false;
+    const leave = () => { if (!leaving) { leaving = true; goNext(); } };
+    const d = dialog({
+      title: "Bring your demo data?",
+      body: "<p>This browser has " + nr + " demo review" + (nr === 1 ? "" : "s") + " and " + np + " practice " + (np === 1 ? "entry" : "entries") + ". Import them into your account?</p><p>If you don't, they stay saved in this browser and come back when you sign out.</p>",
+      actions: [{ label: "Not now", onClick: leave }, { label: "Import", primary: true, onClick: () => {
+        leaving = true;
+        api.importDemo().then((n) => { window.CRX.toast("Imported " + n + " review" + (n === 1 ? "" : "s")); setTimeout(goNext, 600); })
+          .catch((e) => { window.CRX.toast("Import failed: " + e.message + " Your demo data is still in this browser.", "info"); setTimeout(goNext, 1500); });
+      } }]
+    });
+    d.addEventListener("close", () => setTimeout(leave, 0));
+  }
+
+  /** A small form inside the shared dialog. onSubmit(values, showError) resolves when done. */
+  function formDialog(title, intro, fields, submitLabel, onSubmit) {
+    const id = "f" + Date.now().toString(36);
+    const d = dialog({
+      title: title,
+      body: intro + '<form id="' + id + '" novalidate style="display:grid;gap:12px;margin-top:12px">' + fields.map((f) =>
+        '<div class="field"><label class="label" for="' + id + f.name + '">' + esc(f.label) + '</label><input class="input" id="' + id + f.name + '" name="' + f.name + '" type="' + f.type + '" autocomplete="' + f.auto + '"' + (f.value ? ' value="' + esc(f.value) + '"' : "") + "></div>").join("") +
+        '<p class="field-error" role="alert"></p><button class="btn btn-primary" type="submit">' + esc(submitLabel) + "</button></form>",
+      actions: [{ label: "Cancel" }]
+    });
+    const form = $("#" + id, d), err = $(".field-error", form), btn = $("button[type=submit]", form);
+    form.addEventListener("submit", (e) => {
+      e.preventDefault();
+      const values = {}; fields.forEach((f) => (values[f.name] = form.elements[f.name].value));
+      err.textContent = ""; btn.disabled = true;
+      Promise.resolve(onSubmit(values, (m) => (err.textContent = m), d)).finally(() => (btn.disabled = false));
+    });
+    return d;
+  }
+
+  function messageFor(e) {
+    if (e.network) return "Couldn't reach the account server. Check your connection and try again, or use demo mode.";
+    if (e.status >= 500) return "The account server had a problem. Try again in a moment.";
+    return e.message;
+  }
+
+  function resetDialog(token) {
+    formDialog("Choose a new password", "<p>Enter a new password for your account.</p>",
+      [{ name: "password", label: "New password", type: "password", auto: "new-password" }], "Set password",
+      (v, fail, d) => api.reset(token, v.password).then(() => {
+        d.close(); history.replaceState(null, "", location.pathname);
+        window.CRX.toast("Password changed. Sign in with your new password.");
+        pw.focus();
+      }, (e) => fail(messageFor(e))));
   }
 
   /* ---------- Nav background on scroll ---------- */
@@ -45,13 +115,26 @@
     return ok;
   }
   email.addEventListener("blur", () => { if (email.value) validate(); });
-  $("#loginForm").addEventListener("submit", (e) => {
+  $("#loginForm").addEventListener("submit", async (e) => {
     e.preventDefault();
     if (!validate()) { (email.getAttribute("aria-invalid") === "true" ? email : pw).focus(); return; }
     try {
       if ($("#remember").checked) localStorage.setItem("crx.rememberEmail", JSON.stringify(email.value.trim()));
       else localStorage.removeItem("crx.rememberEmail");
     } catch (err) {}
+    if (await serverUp) {
+      // Real sign-in. A failure never opens a session, demo or otherwise.
+      const btn = $(".auth-submit"); btn.disabled = true;
+      try {
+        await api.login(email.value.trim(), pw.value);
+        pw.value = "";
+        afterSignIn();
+      } catch (err) {
+        setErr(pw, $("#pwErr"), err.status === 401 ? "Email or password is incorrect." : messageFor(err));
+        pw.focus();
+      } finally { btn.disabled = false; }
+      return;
+    }
     const name = email.value.trim().split("@")[0].replace(/[._-]+/g, " ").replace(/\b\w/g, (c) => c.toUpperCase()).slice(0, 32) || "Demo user";
     dialog({
       title: "Continue in demo mode",
@@ -66,16 +149,45 @@
     e.currentTarget.setAttribute("aria-pressed", String(show));
     e.currentTarget.setAttribute("aria-label", show ? "Hide password" : "Show password");
   });
-  $("#forgotBtn").addEventListener("click", () => dialog({
-    title: "Password recovery isn't available",
-    body: "<p>Password recovery needs an email service and an account server, and neither is part of this frontend build. No email will be sent.</p><p>Use demo mode to explore every feature.</p>",
-    actions: [{ label: "Close" }, { label: "Explore demo", primary: true, onClick: () => enterDemo("Demo user") }]
-  }));
-  $("#createBtn").addEventListener("click", () => dialog({
-    title: "Account creation isn't available",
-    body: "<p>Creating an account needs a backend that stores users securely, and this build doesn't have one. It would be misleading to pretend otherwise.</p><p>Demo mode gives you the full interface, and your progress is saved locally in this browser.</p>",
-    actions: [{ label: "Close" }, { label: "Explore demo", primary: true, onClick: () => enterDemo("Demo user") }]
-  }));
+  $("#forgotBtn").addEventListener("click", async () => {
+    if (await serverUp) {
+      formDialog("Reset your password", "<p>Enter your account email. If an account exists, we'll send a reset link.</p>",
+        [{ name: "email", label: "Email", type: "email", auto: "email", value: email.value.trim() }], "Send reset link",
+        (v, fail, d) => api.forgot(v.email.trim()).then((r) => {
+          d.close();
+          if (r && r.devResetToken) {
+            // Development servers only (crx.auth.expose-reset-token): no email service, so hand the token over directly.
+            dialog({ title: "Development reset link", body: "<p>This server runs in development mode and has no email service, so the reset link is shown here instead of being emailed.</p>",
+              actions: [{ label: "Close" }, { label: "Reset now", primary: true, onClick: () => resetDialog(r.devResetToken) }] });
+          } else {
+            dialog({ title: "Check your email", body: "<p>" + esc(r && r.message ? r.message : "If an account exists for that email, a reset link has been sent.") + "</p>" });
+          }
+        }, (err) => fail(messageFor(err))));
+      return;
+    }
+    dialog({
+      title: "Password recovery isn't available",
+      body: "<p>Password recovery needs an email service and an account server, and neither is part of this frontend build. No email will be sent.</p><p>Use demo mode to explore every feature.</p>",
+      actions: [{ label: "Close" }, { label: "Explore demo", primary: true, onClick: () => enterDemo("Demo user") }]
+    });
+  });
+  $("#createBtn").addEventListener("click", async () => {
+    if (await serverUp) {
+      formDialog("Create an account", "<p>Your reviews and practice progress will be saved to this account. Passwords need at least 8 characters and two of: lowercase, uppercase, digits, symbols.</p>",
+        [{ name: "name", label: "Name (optional)", type: "text", auto: "name" }, { name: "email", label: "Email", type: "email", auto: "email", value: email.value.trim() },
+         { name: "password", label: "Password", type: "password", auto: "new-password" }], "Create account",
+        (v, fail, d) => api.register(v.name.trim(), v.email.trim(), v.password).then(() => { d.close(); afterSignIn(); }, (err) => {
+          const fields = err.code === "validation" ? "Check the email address and password." : null;
+          fail(fields || messageFor(err));
+        }));
+      return;
+    }
+    dialog({
+      title: "Account creation isn't available",
+      body: "<p>Creating an account needs a backend that stores users securely, and this build doesn't have one. It would be misleading to pretend otherwise.</p><p>Demo mode gives you the full interface, and your progress is saved locally in this browser.</p>",
+      actions: [{ label: "Close" }, { label: "Explore demo", primary: true, onClick: () => enterDemo("Demo user") }]
+    });
+  });
 
   /* ---------- Preview tabs (roving tabindex) ---------- */
   const tabs = $$('.preview-tabs [role="tab"]');
```

### `js/review.js`

```diff
--- a/js/review.js
+++ b/js/review.js
@@ -9,6 +9,11 @@
   const langSel = $("#lang"), sampleSel = $("#sample"), ta = $("#code");
   const params = new URLSearchParams(location.search);
   let current = null, fileName = "";
+  // Signed-in accounts are reviewed by the server's AST engine; demo sessions keep the in-browser engine.
+  const serverReview = !!(X.api && X.api.isAccount());
+  if (serverReview) {
+    $(".page > .notice span").innerHTML = "<strong>Server review.</strong> Your code is sent to the CodeReviewX server you signed in to, parsed into a syntax tree and checked by static-analysis rules. It is never compiled or run. Findings are suggestions to verify, not confirmed defects, and reviews are saved to your account history.";
+  }
 
   langSel.innerHTML = X.langOptions(store.prefs().lang);
   sampleSel.innerHTML += R.SAMPLES.map((s) => '<option value="' + s.id + '">Sample: ' + esc(s.name) + "</option>").join("");
@@ -80,7 +85,7 @@
 
   function renderScore(rev, animate) {
     const sev = R.SEVERITIES.map((s) => '<div class="sev-count"><b class="sev-' + s + '" style="font-family:var(--font-mono)">' + rev.counts[s] + "</b><span>" + SEV_LABEL[s].replace("Informational", "Info") + "</span></div>").join("");
-    $("#scoreBadge").outerHTML = '<span class="badge badge-demo" id="scoreBadge"><span class="dot"></span>Demonstration</span>';
+    $("#scoreBadge").outerHTML = '<span class="badge badge-demo" id="scoreBadge"><span class="dot"></span>' + (rev.engine === "ast-v1" ? "AST analysis" : "Demonstration") + "</span>";
     $("#scoreBody").innerHTML = '<div class="score-body"><div class="gauge-block" style="display:grid;justify-items:center;gap:8px">' + X.gaugeHTML("gauge") +
       '<div><div class="score-label">Overall code quality<span class="tip"><button class="tip-btn" type="button" aria-label="How the overall score is computed">?</button><span class="tip-body" role="tooltip">Weighted average of the seven category scores below (weights shown beside each), minus 8 per critical and 3 per high finding. A high score means few rules matched. It does not mean the code is verified correct.</span></span></div><div class="score-grade">' + X.grade(rev.overall) + "</div></div></div>" +
       '<dl class="kv"><div><dt>Estimated efficiency<span class="tip"><button class="tip-btn" type="button" aria-label="About the efficiency estimate">?</button><span class="tip-body" role="tooltip">A heuristic from the estimated time class (O(1) ≈ 97% down to exponential ≈ 20%), minus small penalties for hidden linear work. It isn\'t a measurement. Real efficiency depends on the method, benchmark, input size and runtime environment.</span></span></dt><dd class="eff">' + rev.efficiency + "%</dd></div>" +
@@ -156,16 +161,35 @@
     renderLoading(); $("#results").hidden = true;
     const steps = $$("#steps li"); let k = 0;
     const stepMs = X.reduceMotion() ? 120 : 380;
+    // Server reviews start immediately and run alongside the step animation.
+    const remote = serverReview ? X.api.review(code, lang, fileName).then((r) => ({ r }), (e) => ({ e })) : null;
     (function next() {
       if (k > 0) steps[k - 1].className = "done";
       if (k < steps.length) { steps[k].className = "active"; k++; setTimeout(next, stepMs); return; }
+      const finish = () => { running = false; btn.disabled = false; btn.lastChild.textContent = "Review code"; };
+      if (remote) {
+        remote.then(({ r, e }) => {
+          if (e) {
+            const msg = e.network ? "Couldn't reach the review server. Check your connection and try again."
+              : e.code === "timeout" ? "The analysis took too long. Try a smaller piece of code." : e.message;
+            renderError(msg); $("#inputErr").textContent = msg; finish(); return;
+          }
+          try {
+            current = r; store.addReview(r);
+            renderScore(r, true); renderResults(r);
+            X.toast("Review saved to your account · score " + r.overall + "/100");
+          } catch (err) { console.error(err); renderError("Something went wrong while showing the result. Try again."); }
+          finish();
+        });
+        return;
+      }
       try {
         const rev = Object.assign(pre, { id: "r" + Date.now().toString(36), at: Date.now(), code: code, file: fileName || null });
         current = rev; store.addReview(rev);
         renderScore(rev, true); renderResults(rev);
         X.toast("Review saved to history · score " + rev.overall + "/100");
       } catch (e) { console.error(e); renderError("Something went wrong while scoring. Try again, or simplify the snippet."); }
-      running = false; btn.disabled = false; btn.lastChild.textContent = "Review code";
+      finish();
     })();
   }
   ta.addEventListener("keydown", (e) => { if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); run(); } });
```

### `index.html`

```diff
--- a/index.html
+++ b/index.html
@@ -228,6 +228,7 @@
 
 <script src="data/problems.js"></script>
 <script src="js/main.js"></script>
+<script src="js/api-client.js"></script>
 <script src="js/auth.js"></script>
 </body>
 </html>
```

### `dashboard.html`

```diff
--- a/dashboard.html
+++ b/dashboard.html
@@ -31,6 +31,7 @@
 <script src="data/problems.js"></script>
 <script src="data/problem-details.js"></script>
 <script src="js/main.js"></script>
+<script src="js/api-client.js"></script>
 <script src="js/charts.js"></script>
 <script src="js/review-engine.js"></script>
 <script src="js/dashboard.js"></script>
```

### `history.html`

```diff
--- a/history.html
+++ b/history.html
@@ -25,6 +25,7 @@
 <script src="data/problems.js"></script>
 <script src="data/problem-details.js"></script>
 <script src="js/main.js"></script>
+<script src="js/api-client.js"></script>
 <script src="js/charts.js"></script>
 <script src="js/review-engine.js"></script>
 <script src="js/pages.js"></script>
```

### `placement.html`

```diff
--- a/placement.html
+++ b/placement.html
@@ -78,6 +78,7 @@
 <script src="data/problems.js"></script>
 <script src="data/problem-details.js"></script>
 <script src="js/main.js"></script>
+<script src="js/api-client.js"></script>
 <script src="js/placement.js"></script>
 </body>
 </html>
```

### `problem.html`

```diff
--- a/problem.html
+++ b/problem.html
@@ -25,6 +25,7 @@
 <script src="data/problems.js"></script>
 <script src="data/problem-details.js"></script>
 <script src="js/main.js"></script>
+<script src="js/api-client.js"></script>
 <script src="js/problem.js"></script>
 </body>
 </html>
```

### `progress.html`

```diff
--- a/progress.html
+++ b/progress.html
@@ -25,6 +25,7 @@
 <script src="data/problems.js"></script>
 <script src="data/problem-details.js"></script>
 <script src="js/main.js"></script>
+<script src="js/api-client.js"></script>
 <script src="js/charts.js"></script>
 <script src="js/review-engine.js"></script>
 <script src="js/pages.js"></script>
```

### `review.html`

```diff
--- a/review.html
+++ b/review.html
@@ -75,6 +75,7 @@
 </div>
 <script src="data/problems.js"></script>
 <script src="js/main.js"></script>
+<script src="js/api-client.js"></script>
 <script src="js/charts.js"></script>
 <script src="js/review-engine.js"></script>
 <script src="js/review.js"></script>
```

### `settings.html`

```diff
--- a/settings.html
+++ b/settings.html
@@ -25,6 +25,7 @@
 <script src="data/problems.js"></script>
 <script src="data/problem-details.js"></script>
 <script src="js/main.js"></script>
+<script src="js/api-client.js"></script>
 <script src="js/charts.js"></script>
 <script src="js/review-engine.js"></script>
 <script src="js/pages.js"></script>
```

### `js/problem.js`

```diff
--- a/js/problem.js
+++ b/js/problem.js
@@ -1,4 +1,5 @@
-/* Code ReveiwX — problem-solving interface. Code is never compiled or executed here. */
+/* Code ReveiwX — problem-solving interface. Code is never executed in the browser; signed-in accounts run it
+   through the server's external judge (POST /api/run), demo sessions show the expected outputs only. */
 (function () {
   "use strict";
   const X = window.CRX, { $, $$, esc, icon, store } = X;
@@ -96,8 +97,67 @@
   const hasWork = () => { const v = ta.value.trim(); return v && v !== starter(langSel.value).trim(); };
   const out = $("#output");
 
+  /* ---------- real execution for signed-in accounts (server → external judge); demo sessions keep the demonstration ---------- */
+  const judge = !!(X.api && X.api.isAccount());
+  if (judge) {
+    out.innerHTML = '<span class="eyebrow">Output</span><p class="faint">Run executes your code against the selected test cases (or your custom input) on the code-execution service. Submit runs every test case.</p>';
+    $("#p-tests").insertAdjacentHTML("beforeend", '<div class="field" style="margin-top:4px"><label class="label" for="stdinBox">Custom input (stdin)</label>' +
+      '<textarea class="input" id="stdinBox" rows="4" spellcheck="false" style="font-family:var(--font-mono);font-size:12.5px;min-height:84px;resize:vertical" placeholder="Typed input for your program\'s main()"></textarea>' +
+      '<p class="faint" style="font-size:12.5px">Used when no test case is selected: your whole program runs with this input and its output is shown.</p></div>');
+  }
+  let running = false;
+  function setBusy(on) { running = on; ["#runBtn", "#submitBtn"].forEach((s) => ($(s).disabled = on)); }
+  function pre(text) { return '<pre>' + esc(text) + "</pre>"; }
+
+  function renderRun(r, submit) {
+    const head = '<span class="eyebrow">' + (submit ? "Submit" : "Output") + " · " + esc(r.judge || "judge") + "</span>" +
+      '<div class="notice" role="note">' + icon("info") + "<span><strong>Executed remotely.</strong> Your code was sent to " + esc(r.judge || "the code-execution service") + ", an external service, to compile and run with time and memory limits.</span></div>";
+    if (r.setupError) return head + '<p class="field-error">' + esc(r.setupError) + "</p>";
+    let h = head;
+    if (r.statusId === 6) return h + '<p class="field-error"><strong>Compilation error.</strong> Fix it and run again.</p>' + pre(r.compileOutput || r.message || "");
+    if (r.mode === "tests") {
+      const all = r.allPassed;
+      h += '<p style="font-weight:600;color:' + (all ? "var(--good)" : "var(--sev-high)") + '">' + (all ? "All " + r.total + " test" + (r.total === 1 ? "" : "s") + " passed" : "Passed " + r.passed + " of " + r.total) + "</p>";
+      h += r.cases.map((c, i) => {
+        const ok = c.passed, yours = c.error ? c.error : c.actual == null ? "no output" : c.actual;
+        return '<div class="out-row"><b>Case ' + (i + 1) + "</b><span>" + esc(c.input) + '</span></div><div class="out-row"><b>Expected</b><span style="color:var(--good)">' + esc(c.expected) + "</span></div>" +
+          '<div class="out-row"><b>Yours</b><span style="color:' + (ok ? "var(--good)" : "var(--sev-high)") + '">' + esc(yours) + '</span></div><div class="out-row"><b>Verdict</b><span style="color:' + (ok ? "var(--good)" : "var(--sev-high)") + '">' + esc(c.verdict) + "</span></div>";
+      }).join('<hr class="divider">');
+      if (r.stdout) h += '<span class="eyebrow">Your printed output</span>' + pre(r.stdout);
+    } else {
+      h += '<div class="out-row"><b>Status</b><span style="color:' + (r.statusId === 3 ? "var(--good)" : "var(--sev-high)") + '">' + esc(r.status) + "</span></div>";
+      h += '<span class="eyebrow">Output</span>' + pre(r.stdout || "(no output)");
+    }
+    if (r.stderr) h += '<span class="eyebrow">Errors (stderr)</span>' + pre(r.stderr);
+    if (r.time != null || r.memoryKb != null) h += '<p class="faint" style="font-size:12px">' + (r.time != null ? "Time " + r.time + " s" : "") + (r.memoryKb != null ? " · Memory " + Math.round(r.memoryKb / 1024) + " MB" : "") + "</p>";
+    return h;
+  }
+
+  async function judgeRun(submit) {
+    if (running) return;
+    const chosen = submit ? tests : $$(".case").filter((c) => c.getAttribute("aria-pressed") === "true").map((c) => tests[+c.dataset.i]);
+    const stdin = ($("#stdinBox") || { value: "" }).value;
+    if (!submit && !chosen.length && tests.length && !stdin.trim()) {
+      out.innerHTML = '<span class="eyebrow">Output</span><p class="field-error">Select at least one test case in the Test cases tab, or enter custom input there.</p>'; return;
+    }
+    setBusy(true);
+    out.innerHTML = '<span class="eyebrow">' + (submit ? "Submit" : "Output") + ' · running</span><div class="skel" style="width:60%"></div><div class="skel" style="width:40%"></div>';
+    try {
+      const r = await X.api.run({ lang: langSel.value, code: ta.value, mode: chosen.length ? "tests" : "stdin", cases: chosen.map((t) => ({ in: t.in, out: t.out })), stdin: stdin });
+      out.innerHTML = renderRun(r, submit);
+      if (submit) {
+        if (r.allPassed) { store.setStatus(p.id, "solved"); X.toast("All tests passed. Marked as solved"); }
+        else { store.setStatus(p.id, "attempted"); X.toast("Saved as attempted", "info"); }
+        renderHead(); renderSolved();
+      }
+    } catch (e) {
+      out.innerHTML = '<span class="eyebrow">' + (submit ? "Submit" : "Output") + '</span><p class="field-error">' + esc(e.network ? "Couldn't reach the server. Check your connection and try again." : e.message) + "</p>";
+    } finally { setBusy(false); }
+  }
+
   $("#runBtn").addEventListener("click", () => {
     if (!hasWork()) { out.innerHTML = '<span class="eyebrow">Output</span><p class="field-error">Write some code first. The editor still has the starter template.</p>'; return; }
+    if (judge) { judgeRun(false); return; }
     const chosen = $$(".case").filter((c) => c.getAttribute("aria-pressed") === "true").map((c) => tests[+c.dataset.i]);
     out.innerHTML = '<span class="eyebrow">Output · demonstration</span><div class="skel" style="width:60%"></div>';
     setTimeout(() => {
@@ -110,6 +170,7 @@
 
   $("#submitBtn").addEventListener("click", () => {
     if (!hasWork()) { out.innerHTML = '<span class="eyebrow">Submit</span><p class="field-error">Nothing to submit yet. Replace the starter template with your solution.</p>'; return; }
+    if (judge && tests.length) { judgeRun(true); return; }
     store.setStatus(p.id, "attempted"); renderHead(); renderSolved();
     out.innerHTML = '<span class="eyebrow">Submit · demonstration</span><p class="muted">Saved as <strong style="color:var(--sev-medium)">Attempted</strong>. There\'s no judge in this demo, so correctness can\'t be verified here. Check your answer against the expected outputs, or submit on the source practice page, then mark it solved.</p>';
     X.toast("Saved as attempted");
```

### `js/api-client.js`

New file (see the source; not repeated here).
