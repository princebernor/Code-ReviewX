/* Code ReveiwX — dashboard: gateway to both modules, review first. */
(function () {
  "use strict";
  const X = window.CRX, { $, $$, esc, icon, store } = X;
  if (!X.shell({ active: "dashboard", title: "Dashboard" })) return;

  const s = store.session();
  const h = new Date().getHours();
  $("#greeting").textContent = (h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening") + ", " + s.name;

  const reviews = store.reviews();
  const last = reviews[0] || null;
  const stats = X.practiceStats();
  const SEV = { critical: "Critical", high: "High", medium: "Medium", low: "Low", info: "Info" };
  const topics = window.CRX_TOPICS || [];
  let html = "";

  /* 1–3. Flagship review card */
  if (last) {
    const cats = X.Review.CATEGORIES.map((c) => { const v = last.categories[c.id].score; return '<div class="mini-bar"><div><span>' + c.label + "</span><b>" + v + '</b></div><div class="bar"><span style="--v:0" data-v="' + v / 100 + '"></span></div></div>'; }).join("");
    html += '<section class="card flag" aria-labelledby="flagT"><div class="flag-score">' + X.gaugeHTML("dGauge") + '<span class="faint" style="font-size:12.5px">Latest review' + (last.sample ? " · sample record" : "") + "</span></div>" +
      '<div class="flag-main"><div style="display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;align-items:center"><h2 id="flagT">AI Code Review</h2><span class="badge badge-demo"><span class="dot"></span>Demonstration result</span></div>' +
      '<dl class="mini-kv"><div><dt>Efficiency (est.)</dt><dd style="color:var(--accent)">' + last.efficiency + "%</dd></div><div><dt>Time</dt><dd>" + esc(last.time) + "</dd></div><div><dt>Space</dt><dd>" + esc(last.space) + "</dd></div><div><dt>Language</dt><dd>" + X.langLabel(last.lang) + "</dd></div></dl>" +
      '<div><p class="eyebrow" style="margin-bottom:10px">Quality breakdown</p><div class="mini-bars">' + cats + "</div></div>" +
      '<div class="flag-actions"><a class="btn btn-accent" href="review.html">' + icon("review") + 'Start new code review</a><a class="btn" href="review.html?id=' + encodeURIComponent(last.id) + '">Open latest review</a></div>' +
      '<p class="faint" style="font-size:12.5px">' + X.fmtDate(last.at, true) + " · " + last.findings.length + " sample findings · " + last.lines + " lines</p></div></section>";
  } else {
    html += '<section class="card flag" aria-labelledby="flagT"><div class="flag-score">' + X.gaugeHTML("dGauge") + '<span class="faint" style="font-size:12.5px">No reviews yet</span></div>' +
      '<div class="flag-main"><span class="badge badge-demo" style="justify-self:start"><span class="dot"></span>Flagship</span><h2 id="flagT">Review your first piece of code</h2>' +
      "<p>No reviews yet. Paste or upload a solution in C, C++, Java, Python, JavaScript or Go to get a score out of 100, an efficiency estimate, and side-by-side suggestions, each with its reasoning and trade-offs.</p>" +
      '<div class="flag-actions"><a class="btn btn-accent" href="review.html">' + icon("review") + 'Start new code review</a><a class="btn" href="settings.html#samples">Add labelled sample records</a></div>' +
      '<p class="faint" style="font-size:12.5px">Reviews in this demo are rule-based checks that run in your browser, not AI analysis.</p></div></section>';
  }

  // Right column: recent insights or how it works
  const recentFindings = [];
  reviews.slice(0, 5).forEach((r) => r.findings.forEach((f) => { if (["critical", "high", "medium"].indexOf(f.sev) > -1) recentFindings.push({ f: f, r: r }); }));
  if (recentFindings.length) {
    html += '<section class="card" aria-labelledby="insT"><div class="card-head"><h2 class="card-title" id="insT">Recent review insights</h2><span class="badge">Sample findings</span></div><div class="insights">' +
      recentFindings.slice(0, 5).map((x) => '<div class="insight"><span class="sev sev-' + x.f.sev + '">' + SEV[x.f.sev] + '</span><a href="review.html?id=' + encodeURIComponent(x.r.id) + '">' + esc(x.f.title) + '</a><span class="faint">' + X.langLabel(x.r.lang) + " · line " + x.f.line + " · " + X.ago(x.r.at) + "</span></div>").join("") + "</div></section>";
  } else {
    html += '<section class="card card-pad" aria-labelledby="howT" style="display:grid;gap:16px;align-content:start"><h2 class="card-title" id="howT">How the two modules work</h2><ol class="how-list">' +
      "<li><span><strong>Practice</strong>Pick a problem from the 442-problem sheet and write a solution in the editor.</span></li>" +
      "<li><span><strong>Send to review</strong>One click moves your code into the review workspace.</span></li>" +
      "<li><span><strong>Understand the changes</strong>Every finding explains the issue, the improved code, and its trade-offs.</span></li>" +
      '</ol><p class="faint" style="font-size:12.5px">' + (last ? "Your recent reviews had no medium-or-higher findings." : "Insights appear here after your first review.") + "</p></section>";
  }

  /* 4. Placement overview */
  const tops = topics.map((t) => ({ t: t, s: stats.byTopic[t.slug] || { total: 0, solved: 0, attempted: 0 } }));
  tops.sort((a, b) => (b.s.solved + b.s.attempted) - (a.s.solved + a.s.attempted));
  html += '<section class="card" aria-labelledby="ppT"><div class="card-head"><div><h2 class="card-title" id="ppT">Placement Code Practice</h2><p class="card-sub">Curious Freaks coding sheet · 18 topics</p></div><a class="btn btn-sm" href="placement.html">Open practice</a></div>' +
    '<dl class="stat-row"><div class="stat"><dt>Solved</dt><dd>' + stats.solved + "<small> / " + stats.total + '</small></dd></div><div class="stat"><dt>Attempted</dt><dd>' + stats.attempted + '</dd></div><div class="stat"><dt>Readiness</dt><dd>' + stats.readiness + '<small>%</small></dd></div><div class="stat"><dt>Streak</dt><dd>' + X.streak() + "<small> day" + (X.streak() === 1 ? "" : "s") + "</small></dd></div></dl>" +
    '<div class="topic-mini">' + tops.slice(0, 6).map((x) => { const pct = x.s.total ? x.s.solved / x.s.total : 0; return '<a href="placement.html?topic=' + x.t.slug + '"><div class="tm-top"><span>' + esc(x.t.label) + "</span><span>" + x.s.solved + "/" + x.s.total + '</span></div><div class="bar"><span style="--v:0" data-v="' + pct + '"></span></div></a>'; }).join("") + "</div>" +
    (stats.solved + stats.attempted === 0 ? '<p class="faint" style="padding:14px 20px;font-size:12.5px">Progress starts at zero and only counts problems you mark in this browser.</p>' : "") + "</section>";

  /* 5. Recommended problems */
  const prog = store.progress(); const all = window.CRX_PROBLEMS || [];
  const curated = window.CRX_DETAILS ? Object.keys(window.CRX_DETAILS).map(Number) : [1, 26, 37, 38, 58, 92, 127, 170];
  const next = X.nextProblem();
  const recs = [];
  if (next) recs.push({ p: next, why: prog[next.id] ? "Continue: you attempted this" : "Next in the sheet's order" });
  curated.forEach((id) => { const p = all[id - 1]; if (p && !prog[id] && recs.length < 4 && !recs.some((r) => r.p.id === id)) recs.push({ p: p, why: "Full statement and walkthrough included" }); });
  html += '<section class="card" aria-labelledby="recT"><div class="card-head"><h2 class="card-title" id="recT">Recommended problems</h2></div><div class="rec-list">' +
    (recs.length ? recs.map((r) => '<div class="rec"><a class="rec-title" href="problem.html?id=' + r.p.id + '">' + esc(r.p.t) + '</a><a class="btn btn-sm" href="problem.html?id=' + r.p.id + '" aria-label="Open ' + esc(r.p.t) + '">Open</a><span class="rec-meta"><span class="num">#' + r.p.id + '</span><span class="diff-' + r.p.d.toLowerCase() + '">' + r.p.d + " (est.)</span><span>" + esc(r.why) + "</span></span></div>").join("")
      : '<div class="empty"><h3>Every problem is marked solved</h3><p>Impressive. Revisit hard problems or review your solutions.</p></div>') + "</div></section>";

  /* 6. Progress & utilities */
  const act = store.activity().slice(0, 5);
  const label = (a) => a.type === "review" ? "Ran a code review" : a.type === "solved" ? "Solved #" + a.ref : "Attempted #" + a.ref;
  html += '<div class="util-grid span-all">' +
    '<section class="card util" aria-labelledby="u1"><h3 id="u1">Learning streak</h3><span class="big-num">' + X.streak() + ' <span class="faint" style="font-size:14px;font-family:var(--font-sans)">days</span></span><p>Days in a row with at least one review, attempt or solve. Counted from your activity in this browser.</p><a class="btn btn-sm" style="justify-self:start" href="progress.html">View progress</a></section>' +
    '<section class="card util" aria-labelledby="u2"><h3 id="u2">Recent activity</h3>' + (act.length ? '<ul class="activity">' + act.map((a) => "<li><span>" + esc(label(a)) + "</span><span>" + X.ago(a.at) + "</span></li>").join("") + "</ul>" : "<p>Nothing yet. Your reviews and practice steps will show up here.</p>") + "</section>" +
    '<section class="card util" aria-labelledby="u3"><h3 id="u3">Quick actions</h3><div style="display:grid;gap:8px">' +
    '<a class="btn btn-sm" href="review.html" style="justify-content:flex-start">' + icon("upload") + "Upload a file for review</a>" +
    '<a class="btn btn-sm" href="placement.html?status=attempted" style="justify-content:flex-start">' + icon("practice") + "Resume attempted problems</a>" +
    '<a class="btn btn-sm" href="history.html" style="justify-content:flex-start">' + icon("history") + "Compare past review scores</a></div></section></div>";

  $("#dash").innerHTML = html;
  X.setGauge($("#dGauge"), last ? last.overall : 0, !!last);
  requestAnimationFrame(() => $$(".bar > span[data-v]").forEach((b) => b.style.setProperty("--v", b.dataset.v)));
})();
