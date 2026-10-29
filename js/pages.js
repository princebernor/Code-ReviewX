/* Code ReveiwX — Review History, My Progress and Settings pages. */
(function () {
  "use strict";
  const X = window.CRX, { $, $$, esc, icon, store } = X;
  const page = $("#main").dataset.page;
  const TITLES = { history: "Review History", progress: "My Progress", settings: "Settings" };
  if (!X.shell({ active: page, title: TITLES[page] })) return;
  const C = $("#content");

  /* ================= HISTORY ================= */
  function history() {
    const list = store.reviews();
    if (!list.length) {
      C.innerHTML = '<section class="card"><div class="empty"><div class="empty-icon">' + icon("history") + '</div><h3>No reviews yet</h3><p>Your review history starts empty. Run your first review, or add clearly labelled sample records from Settings to see how this page looks.</p><div style="display:flex;gap:8px;flex-wrap:wrap;justify-content:center"><a class="btn btn-sm btn-accent" href="review.html">Run a review</a><a class="btn btn-sm" href="settings.html#samples">Add sample records</a></div></div></section>';
      return;
    }
    const chrono = list.slice().reverse();
    const avg = Math.round(list.reduce((s, r) => s + r.overall, 0) / list.length);
    const best = Math.max.apply(null, list.map((r) => r.overall));
    const delta = list.length > 1 ? list[0].overall - list[list.length - 1].overall : 0;
    C.innerHTML =
      '<section class="card"><div class="card-head"><div><h2 class="card-title">Score trend</h2><p class="card-sub">Overall score per review, oldest to newest. Hover a point to inspect it.</p></div>' + (list.some((r) => r.sample) ? '<span class="badge badge-demo">Includes sample records</span>' : "") + "</div>" +
      '<dl class="stat-row" style="border-bottom:1px solid var(--line)"><div class="stat"><dt>Reviews</dt><dd>' + list.length + '</dd></div><div class="stat"><dt>Average score</dt><dd>' + avg + '</dd></div><div class="stat"><dt>Best score</dt><dd>' + best + '</dd></div><div class="stat"><dt>First → latest</dt><dd style="color:' + (delta >= 0 ? "var(--good)" : "var(--sev-high)") + '">' + (delta >= 0 ? "+" : "") + delta + "</dd></div></dl>" +
      '<div style="padding:18px 20px" id="trend"></div></section>' +
      '<section class="card"><div class="card-head"><h2 class="card-title">All reviews</h2><button class="btn btn-sm btn-ghost" type="button" id="clearHist">' + icon("trash") + 'Clear history</button></div><div class="table-wrap"><table class="table"><thead><tr><th scope="col">Date</th><th scope="col">Language</th><th scope="col">Score</th><th scope="col">Efficiency (est.)</th><th scope="col">Time</th><th scope="col">Findings (C / H / M / L)</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead><tbody>' +
      list.map((r) => "<tr><td>" + X.fmtDate(r.at, true) + (r.sample ? ' <span class="badge" style="margin-left:6px">Sample record</span>' : "") + (r.file ? '<div class="faint" style="font-size:12px">' + esc(r.file) + "</div>" : "") + "</td><td>" + X.langLabel(r.lang) + '</td><td><span class="hist-score"><i style="--v:' + r.overall / 100 + '"></i>' + r.overall + '</span></td><td class="num">' + r.efficiency + '%</td><td class="num">' + esc(r.time) + '</td><td><span class="sev-inline"><span class="sev-critical">' + r.counts.critical + '</span>/<span class="sev-high">' + r.counts.high + '</span>/<span class="sev-medium">' + r.counts.medium + '</span>/<span class="sev-low">' + r.counts.low + '</span></span></td><td><div class="row-actions" style="display:flex;gap:6px;justify-content:flex-end"><a class="btn btn-sm" href="review.html?id=' + encodeURIComponent(r.id) + '">View review</a><button class="btn btn-sm btn-ghost btn-icon" type="button" data-del="' + esc(r.id) + '" aria-label="Delete review from ' + X.fmtDate(r.at, true) + '">' + icon("trash") + "</button></div></td></tr>").join("") +
      "</tbody></table></div></section>";
    X.trend($("#trend"), chrono.map((r) => ({ x: new Date(r.at).toLocaleDateString(undefined, { month: "short", day: "numeric" }), y: r.overall, tip: X.fmtDate(r.at, true) + " · " + X.langLabel(r.lang) })), { label: "Overall review score over time" });
    $$("[data-del]").forEach((b) => b.addEventListener("click", () => { store.setReviews(store.reviews().filter((r) => r.id !== b.dataset.del)); X.toast("Review deleted", "trash"); history(); }));
    $("#clearHist").addEventListener("click", () => X.dialog({ title: "Clear all review history?", body: "<p>This permanently removes " + list.length + " saved review" + (list.length === 1 ? "" : "s") + " from this browser.</p>", actions: [{ label: "Cancel" }, { label: "Clear history", primary: true, onClick: () => { store.setReviews([]); history(); X.toast("History cleared", "trash"); } }] }));
  }

  /* ================= PROGRESS ================= */
  function progress() {
    const s = X.practiceStats(), prog = store.progress(), all = window.CRX_PROBLEMS, act = store.activity();
    // 16-week activity heatmap
    const counts = {}; act.forEach((a) => { const k = new Date(a.at).toDateString(); counts[k] = (counts[k] || 0) + 1; });
    const end = new Date(); end.setHours(0, 0, 0, 0); const start = new Date(end); start.setDate(end.getDate() - (16 * 7 - 1) - end.getDay());
    let cells = ""; for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) { const n = counts[d.toDateString()] || 0; cells += '<i data-l="' + (n === 0 ? 0 : n < 3 ? 1 : n < 6 ? 2 : 3) + '" title="' + d.toLocaleDateString() + ": " + n + ' action(s)"></i>'; }
    const history = Object.keys(prog).map((id) => ({ p: all[+id - 1], s: prog[id] })).filter((x) => x.p).sort((a, b) => b.s.at - a.s.at);
    const next = X.nextProblem();
    C.innerHTML =
      '<section class="card"><dl class="stat-row"><div class="stat"><dt>Problems solved</dt><dd>' + s.solved + "<small> / " + s.total + '</small></dd></div><div class="stat"><dt>Attempted, not solved</dt><dd>' + s.attempted + '</dd></div><div class="stat"><dt>Learning streak</dt><dd>' + X.streak() + '<small> days</small></dd></div><div class="stat"><dt>Reviews run</dt><dd>' + store.reviews().filter((r) => !r.sample).length + "</dd></div></dl>" +
      '<div class="ready card-pad">' + X.gaugeHTML("rGauge") + '<div style="display:grid;gap:8px"><h2 class="card-title">Placement readiness: ' + s.readiness + '%</h2><p class="muted" style="font-size:14px;max-width:62ch">The average share of each topic you\'ve solved, with all 18 topics weighted equally, so breadth counts as much as depth. It\'s a coverage indicator for this sheet, not a prediction of interview results.</p>' +
      (next ? '<a class="btn btn-sm btn-accent" style="justify-self:start" href="problem.html?id=' + next.id + '">Next: #' + next.id + " " + esc(next.t) + "</a>" : "") + "</div></div></section>" +
      '<section class="card"><div class="card-head"><h2 class="card-title">Activity, last 16 weeks</h2><span class="faint" style="font-size:12.5px">Reviews, attempts and solves per day</span></div><div class="card-pad"><div class="heat" role="img" aria-label="Activity heatmap for the last 16 weeks">' + cells + "</div></div></section>" +
      '<section class="card"><div class="card-head"><h2 class="card-title">Topic-wise completion</h2></div><div class="table-wrap"><table class="table topic-table"><thead><tr><th scope="col">Topic</th><th scope="col">Completion</th><th scope="col">Solved</th><th scope="col">Attempted</th><th scope="col">Total</th></tr></thead><tbody>' +
      window.CRX_TOPICS.map((t) => { const b = s.byTopic[t.slug]; const pct = b.solved / b.total; return '<tr><td><a href="placement.html?topic=' + t.slug + '" style="color:var(--text)">' + esc(t.label) + '</a></td><td><div style="display:flex;align-items:center;gap:10px"><div class="bar" style="flex:1"><span style="--v:' + pct + '"></span></div><span class="num faint" style="font-size:12px;width:36px;text-align:right">' + Math.round(pct * 100) + '%</span></div></td><td class="num">' + b.solved + '</td><td class="num">' + b.attempted + '</td><td class="num faint">' + b.total + "</td></tr>"; }).join("") + "</tbody></table></div></section>" +
      '<section class="card"><div class="card-head"><h2 class="card-title">Practice history</h2></div>' +
      (history.length ? '<div class="table-wrap"><table class="table"><thead><tr><th scope="col">#</th><th scope="col">Problem</th><th scope="col">Status</th><th scope="col">Updated</th></tr></thead><tbody>' + history.slice(0, 50).map((x) => '<tr><td class="num faint">' + x.p.id + '</td><td><a href="problem.html?id=' + x.p.id + '" style="color:var(--text)">' + esc(x.p.t) + '</a></td><td><span class="sev ' + (x.s.status === "solved" ? "" : "sev-medium") + '" style="' + (x.s.status === "solved" ? "color:var(--good)" : "") + '">' + (x.s.status === "solved" ? "Solved" : "Attempted") + '</span></td><td class="faint">' + X.ago(x.s.at) + "</td></tr>").join("") + "</tbody></table></div>"
        : '<div class="empty"><div class="empty-icon">' + icon("practice") + '</div><h3>No practice yet</h3><p>Open any problem, submit an attempt or mark it solved, and it will appear here.</p><a class="btn btn-sm" href="placement.html">Browse problems</a></div>') + "</section>";
    X.setGauge($("#rGauge"), s.readiness, s.readiness > 0);
    $(".gauge-den", $("#rGauge")).textContent = "% ready";
  }

  /* ================= SETTINGS ================= */
  function makeSamples() {
    const R = X.Review; const days = [12, 9, 6, 3, 1];
    const picks = ["c-greet", "js-dupes", "py-pairs", "cpp-pairs", "java-search"];
    return picks.map((id, i) => {
      const s = R.SAMPLES.find((x) => x.id === id); const r = R.analyze(s.code, s.lang);
      return Object.assign(r, { id: "s" + i + Date.now().toString(36), at: Date.now() - days[i] * 86400000 - (i * 3600000), code: s.code, file: s.name, sample: true });
    }).reverse();
  }
  function settings() {
    const pr = store.prefs(); const hasSamples = store.reviews().some((r) => r.sample);
    C.innerHTML =
      '<section class="card"><div class="card-head"><h2 class="card-title">Preferences</h2></div><div class="set-list">' +
      '<div class="set-row"><div><h3 id="thL">Theme</h3><p>Dark is the default. Saved in this browser.</p></div><div class="seg" role="group" aria-labelledby="thL"><button type="button" data-theme="dark" aria-pressed="' + (pr.theme !== "light") + '">Dark</button><button type="button" data-theme="light" aria-pressed="' + (pr.theme === "light") + '">Light</button></div></div>' +
      '<div class="set-row"><div><h3><label for="defLang">Default language</label></h3><p>Preselected in the review workspace and the problem editor.</p></div><select class="select select-sm" id="defLang" style="width:auto">' + X.langOptions(pr.lang) + "</select></div></div></section>" +
      '<section class="card" id="samples"><div class="card-head"><h2 class="card-title">Demo data</h2><span class="badge badge-demo">Stored locally</span></div><div class="set-list">' +
      '<div class="set-row"><div><h3>Sample review records</h3><p>Adds five reviews of the built-in sample code, dated over the last two weeks, so you can see History and Dashboard with data. Each is marked “Sample record” everywhere it appears.</p></div>' + (hasSamples ? '<button class="btn btn-sm" type="button" id="rmSamples">Remove sample records</button>' : '<button class="btn btn-sm btn-accent" type="button" id="addSamples">Add sample records</button>') + "</div>" +
      '<div class="set-row"><div><h3>Export my data</h3><p>Download your reviews, practice progress and activity as JSON.</p></div><button class="btn btn-sm" type="button" id="exportBtn">' + icon("download") + "Export JSON</button></div>" +
      '<div class="set-row"><div><h3>Reset progress &amp; history</h3><p>Permanently clears reviews, practice status, drafts and activity from this browser.</p></div><button class="btn btn-sm btn-danger" type="button" id="resetBtn">' + icon("trash") + "Reset all data</button></div></div></section>" +
      '<section class="card"><div class="card-head"><h2 class="card-title">About this build</h2></div><div class="card-pad" style="display:grid;gap:10px;font-size:14px;color:var(--text-2)"><p>Code ReveiwX is a frontend-only demonstration built with HTML, CSS and vanilla JavaScript. It has no backend, database, account system, code execution service or AI model.</p><p>Reviews are produced by in-browser rule checks and are labelled as demonstrations. Practice problems come from the Curious Freaks coding sheet (442 problems). Difficulty labels are this app\'s estimates.</p>' +
      '<button class="btn btn-sm" style="justify-self:start" type="button" id="exitBtn">' + icon("logout") + "Exit demo session</button></div></section>";

    $$("[data-theme]").forEach((b) => b.addEventListener("click", () => { store.setPref("theme", b.dataset.theme); X.applyTheme(b.dataset.theme); $$("[data-theme]").forEach((x) => x.setAttribute("aria-pressed", String(x === b))); $("#themeBtn").innerHTML = icon(b.dataset.theme === "light" ? "moon" : "sun"); }));
    $("#defLang").addEventListener("change", (e) => { store.setPref("lang", e.target.value); X.toast("Default language: " + X.langLabel(e.target.value)); });
    const add = $("#addSamples"), rm = $("#rmSamples");
    if (add) add.addEventListener("click", () => { store.setReviews(makeSamples().concat(store.reviews()).sort((a, b) => b.at - a.at)); X.toast("Added 5 sample records"); settings(); });
    if (rm) rm.addEventListener("click", () => { store.setReviews(store.reviews().filter((r) => !r.sample)); X.toast("Sample records removed", "trash"); settings(); });
    $("#exportBtn").addEventListener("click", () => {
      const data = { exportedAt: new Date().toISOString(), note: "Code ReveiwX frontend demo data", reviews: store.reviews(), progress: store.progress(), activity: store.activity(), prefs: store.prefs() };
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
      const a = document.createElement("a"); a.href = url; a.download = "code-reveiwx-data.json"; document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      X.toast("Export downloaded", "download");
    });
    $("#resetBtn").addEventListener("click", () => X.dialog({ title: "Reset all demo data?", body: "<p>This permanently deletes your reviews, practice progress, code drafts and activity in this browser. Preferences are kept.</p>", actions: [{ label: "Cancel" }, { label: "Reset everything", primary: true, onClick: () => { store.resetAll(); X.toast("All demo data cleared", "trash"); settings(); } }] }));
    $("#exitBtn").addEventListener("click", () => $("#exitDemo").click());
    if (location.hash === "#samples") $("#samples").scrollIntoView();
  }

  ({ history, progress, settings })[page]();
})();
