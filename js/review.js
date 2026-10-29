/* Code ReveiwX — review workspace */
(function () {
  "use strict";
  const X = window.CRX, R = X.Review, { $, $$, esc, icon, store } = X;
  if (!X.shell({ active: "review", title: "AI Code Review" })) return;

  const SEV_LABEL = { critical: "Critical", high: "High", medium: "Medium", low: "Low", info: "Informational" };
  const CAT_LABEL = {}; R.CATEGORIES.forEach((c) => (CAT_LABEL[c.id] = c.label));
  const langSel = $("#lang"), sampleSel = $("#sample"), ta = $("#code");
  const params = new URLSearchParams(location.search);
  let current = null, fileName = "";
  // Signed-in accounts are reviewed by the server's AST engine; demo sessions keep the in-browser engine.
  const serverReview = !!(X.api && X.api.isAccount());
  if (serverReview) {
    $(".page > .notice span").innerHTML = "<strong>Server review.</strong> Your code is sent to the CodeReviewX server you signed in to, parsed into a syntax tree and checked by static-analysis rules. It is never compiled or run. Findings are suggestions to verify, not confirmed defects, and reviews are saved to your account history.";
  }

  langSel.innerHTML = X.langOptions(store.prefs().lang);
  sampleSel.innerHTML += R.SAMPLES.map((s) => '<option value="' + s.id + '">Sample: ' + esc(s.name) + "</option>").join("");

  const ed = X.editor(ta, (v, n) => {
    $("#lineCount").textContent = v ? n : 0; $("#charCount").textContent = v.length;
    $("#inputErr").textContent = ""; checkLang();
  });

  function checkLang() {
    const g = R.guessLang(ta.value), w = $("#langWarn");
    if (g && g !== langSel.value && ta.value.trim().split("\n").length >= 3) {
      w.innerHTML = icon("info") + '<span>This looks like <strong>' + X.langLabel(g) + "</strong>, but " + X.langLabel(langSel.value) + ' is selected. Rules are language-specific. <button class="link-btn" type="button" id="switchLang">Switch to ' + X.langLabel(g) + "</button></span>";
      w.classList.add("show"); $("#switchLang").onclick = () => { langSel.value = g; checkLang(); };
    } else w.classList.remove("show");
  }
  langSel.addEventListener("change", () => { store.setPref("lang", langSel.value); checkLang(); });

  sampleSel.addEventListener("change", () => {
    const s = R.SAMPLES.find((x) => x.id === sampleSel.value); if (!s) return;
    const load = () => { langSel.value = s.lang; ed.set(s.code); fileName = ""; $("#fileName").textContent = "Sample code"; sampleSel.value = ""; X.toast("Loaded sample: " + s.name, "file"); };
    if (ta.value.trim() && ta.value.trim() !== s.code.trim()) X.dialog({ title: "Replace your code?", body: "<p>Loading a sample replaces the code currently in the editor.</p>", actions: [{ label: "Cancel", onClick: () => (sampleSel.value = "") }, { label: "Replace", primary: true, onClick: load }] });
    else load();
  });

  $("#clearBtn").addEventListener("click", () => { if (!ta.value) return; ed.set(""); fileName = ""; $("#fileName").textContent = ""; ta.focus(); X.toast("Editor cleared", "trash"); });
  $("#pasteBtn").addEventListener("click", async () => {
    try {
      if (!navigator.clipboard || !navigator.clipboard.readText) throw new Error("unsupported");
      const t = await navigator.clipboard.readText(); if (!t) { X.toast("Clipboard is empty", "info"); return; }
      ed.set(t); X.toast("Pasted from clipboard", "copy");
    } catch (e) { ta.focus(); X.toast("Clipboard access blocked. Press Ctrl+V (⌘V) in the editor instead.", "info"); }
  });

  /* ---- file upload + drag and drop ---- */
  const MAX = 512 * 1024;
  function readFile(f) {
    if (!f) return;
    const ext = (f.name.match(/\.[^.]+$/) || [""])[0].toLowerCase();
    const lang = X.LANGS.find((l) => l.ext.indexOf(ext) > -1);
    if (!lang && ext !== ".txt") { $("#inputErr").textContent = "Unsupported file type \"" + ext + "\". Use .c, .cpp, .java, .py, .js or .go."; return; }
    if (f.size > MAX) { $("#inputErr").textContent = "That file is " + Math.round(f.size / 1024) + " KB. The demo accepts files up to 512 KB."; return; }
    const r = new FileReader();
    r.onload = () => { if (lang) langSel.value = lang.id; ed.set(String(r.result)); fileName = f.name; $("#fileName").textContent = f.name; X.toast("Loaded " + f.name, "file"); };
    r.onerror = () => { $("#inputErr").textContent = "Couldn't read that file."; };
    r.readAsText(f);
  }
  $("#uploadBtn").addEventListener("click", () => $("#fileInput").click());
  $("#fileInput").addEventListener("change", (e) => { readFile(e.target.files[0]); e.target.value = ""; });
  const box = $("#editorBox");
  ["dragenter", "dragover"].forEach((t) => box.addEventListener(t, (e) => { e.preventDefault(); box.classList.add("dragging"); }));
  ["dragleave", "drop"].forEach((t) => box.addEventListener(t, (e) => { e.preventDefault(); if (t === "dragleave" && box.contains(e.relatedTarget)) return; box.classList.remove("dragging"); }));
  box.addEventListener("drop", (e) => readFile(e.dataTransfer.files[0]));

  /* ---- score panel states ---- */
  function renderEmpty() {
    $("#scoreBadge").textContent = "Not run";
    $("#scoreBody").innerHTML = '<div class="empty"><div class="empty-icon">' + icon("review") + "</div><h3>No review yet</h3><p>Add code on the left and choose <strong>Review code</strong>. Not sure where to start? Load a sample.</p></div>";
  }
  function renderLoading() {
    $("#scoreBadge").textContent = "Running";
    $("#scoreBody").innerHTML = '<div class="score-body"><ul class="loading-steps" id="steps"><li>Parsing ' + X.langLabel(langSel.value) + " source</li><li>Matching risky and unidiomatic patterns</li><li>Estimating loop and recursion complexity</li><li>Scoring categories from findings</li></ul>" +
      '<div style="width:100%;display:grid;gap:8px"><div class="skel" style="width:80%"></div><div class="skel" style="width:60%"></div><div class="skel" style="width:70%"></div></div></div>';
  }
  function renderError(msg) {
    $("#scoreBadge").textContent = "Error";
    $("#scoreBody").innerHTML = '<div class="empty"><div class="empty-icon" style="color:var(--sev-critical)">' + icon("info") + "</div><h3>Couldn't review this code</h3><p>" + esc(msg) + "</p></div>";
  }

  function renderScore(rev, animate) {
    const sev = R.SEVERITIES.map((s) => '<div class="sev-count"><b class="sev-' + s + '" style="font-family:var(--font-mono)">' + rev.counts[s] + "</b><span>" + SEV_LABEL[s].replace("Informational", "Info") + "</span></div>").join("");
    $("#scoreBadge").outerHTML = '<span class="badge badge-demo" id="scoreBadge"><span class="dot"></span>' + (rev.engine === "ast-v1" ? "AST analysis" : "Demonstration") + "</span>";
    $("#scoreBody").innerHTML = '<div class="score-body"><div class="gauge-block" style="display:grid;justify-items:center;gap:8px">' + X.gaugeHTML("gauge") +
      '<div><div class="score-label">Overall code quality<span class="tip"><button class="tip-btn" type="button" aria-label="How the overall score is computed">?</button><span class="tip-body" role="tooltip">Weighted average of the seven category scores below (weights shown beside each), minus 8 per critical and 3 per high finding. A high score means few rules matched. It does not mean the code is verified correct.</span></span></div><div class="score-grade">' + X.grade(rev.overall) + "</div></div></div>" +
      '<dl class="kv"><div><dt>Estimated efficiency<span class="tip"><button class="tip-btn" type="button" aria-label="About the efficiency estimate">?</button><span class="tip-body" role="tooltip">A heuristic from the estimated time class (O(1) ≈ 97% down to exponential ≈ 20%), minus small penalties for hidden linear work. It isn\'t a measurement. Real efficiency depends on the method, benchmark, input size and runtime environment.</span></span></dt><dd class="eff">' + rev.efficiency + "%</dd></div>" +
      "<div><dt>Time complexity (est.)</dt><dd>" + esc(rev.time) + "</dd></div><div><dt>Space complexity (est.)</dt><dd>" + esc(rev.space) + "</dd></div><div><dt>Language · lines</dt><dd>" + X.langLabel(rev.lang) + " · " + rev.lines + "</dd></div></dl>" +
      '<div class="sev-counts" aria-label="Findings by severity">' + sev + "</div>" +
      '<div class="score-actions"><a class="btn btn-sm" href="#findings">Jump to findings</a><a class="btn btn-sm btn-ghost" href="history.html">History</a></div></div>';
    X.setGauge($("#gauge"), rev.overall, animate);
  }

  /* ---- results ---- */
  let sevFilter = "all", catFilter = "all";
  function renderResults(rev) {
    const res = $("#results"); res.hidden = false;
    const bd = R.CATEGORIES.map((c, i) => {
      const s = rev.categories[c.id];
      return '<div class="bd-item reveal" style="--i:' + i + '"><div class="bd-top"><span>' + c.label + "</span><b>" + s.score + '</b></div><div class="bar" role="img" aria-label="' + c.label + " " + s.score + ' out of 100"><span style="--v:0" data-v="' + s.score / 100 + '"></span></div><div class="bd-sub"><span>weight ' + Math.round(c.w * 100) + "%</span><span>" + s.n + " finding" + (s.n === 1 ? "" : "s") + "</span></div></div>";
    }).join("");
    const formula = R.CATEGORIES.map((c) => rev.categories[c.id].score + "×" + c.w.toFixed(2)).join(" + ");
    res.innerHTML =
      '<section class="card" aria-labelledby="bdTitle"><div class="card-head"><div><h2 class="card-title" id="bdTitle">Score breakdown</h2><p class="card-sub">Each category starts at 100 and loses points per finding (critical −32, high −20, medium −11, low −5, info −2). Time and space start from the estimated complexity class.</p></div></div>' +
      '<div class="breakdown">' + bd + '</div><p class="formula">overall = ' + formula + " = " + rev.weighted + (rev.counts.critical || rev.counts.high ? " − " + (8 * rev.counts.critical + 3 * rev.counts.high) + " (severity surcharge)" : "") + " → <b style=\"color:var(--text)\">" + rev.overall + "</b><br>A category at 100 means none of the checks matched there. It isn't proof the code is correct, secure or efficient.</p></section>" +
      '<section class="card" id="findings" aria-labelledby="fTitle"><div class="card-head" style="flex-wrap:wrap"><div><h2 class="card-title" id="fTitle">Findings &amp; suggested improvements</h2><p class="card-sub">' + rev.findings.length + " sample finding" + (rev.findings.length === 1 ? "" : "s") + ". Each is a suggestion to verify, not a confirmed defect.</p></div>" +
      '<div class="find-filters"><div class="seg" role="group" aria-label="Filter by severity" id="sevSeg">' + ["all"].concat(R.SEVERITIES).map((s) => '<button type="button" data-sev="' + s + '" aria-pressed="' + (s === sevFilter) + '">' + (s === "all" ? "All" : SEV_LABEL[s].replace("Informational", "Info")) + "</button>").join("") + '</div>' +
      '<label class="sr-only" for="catSel">Filter by category</label><select class="select select-sm" id="catSel" style="width:auto"><option value="all">All categories</option>' + R.CATEGORIES.map((c) => '<option value="' + c.id + '">' + c.label + "</option>").join("") + "</select></div></div>" +
      '<div class="find-list" id="findList"></div></section>';
    requestAnimationFrame(() => $$(".bar > span", res).forEach((b) => b.style.setProperty("--v", b.dataset.v)));
    $$("#sevSeg button").forEach((b) => b.addEventListener("click", () => { sevFilter = b.dataset.sev; $$("#sevSeg button").forEach((x) => x.setAttribute("aria-pressed", String(x === b))); renderFindings(rev); }));
    $("#catSel").value = catFilter; $("#catSel").addEventListener("change", (e) => { catFilter = e.target.value; renderFindings(rev); });
    renderFindings(rev);
  }

  function cplxTable(f) {
    if (!f.before || !f.after) return '<p class="faint" style="font-size:13px">Complexity: not affected by this change.</p>';
    return '<table class="cplx"><thead><tr><th scope="col"></th><th scope="col">Before</th><th scope="col">After</th></tr></thead><tbody><tr><th scope="row">Time</th><td>' + esc(f.before.time) + "</td><td>" + esc(f.after.time) + '</td></tr><tr><th scope="row">Space</th><td>' + esc(f.before.space) + "</td><td>" + esc(f.after.space) + "</td></tr></tbody></table>";
  }
  function sugLabel(kind) { return kind === "rewrite" ? "Suggested rewrite" : kind === "template" ? "Suggested pattern (template, adapt to your code)" : kind === "note" ? "Suggested approach" : "Suggested change"; }

  function renderFindings(rev) {
    const list = rev.findings.filter((f) => (sevFilter === "all" || f.sev === sevFilter) && (catFilter === "all" || f.cat === catFilter));
    const el = $("#findList");
    if (!rev.findings.length) { el.innerHTML = '<div class="empty no-findings"><div class="empty-icon" style="color:var(--good)">' + icon("check") + "</div><h3>No rules matched</h3><p>None of the demo checks flagged this code. That's a good sign, but it isn't a guarantee: these checks can't verify logic against a specification. Test it against edge cases.</p></div>"; return; }
    if (!list.length) { el.innerHTML = '<div class="empty no-findings"><h3>No findings match these filters</h3><p>Choose a different severity or category.</p></div>'; return; }
    el.innerHTML = list.map((f, i) => {
      const lines = f.line === f.end ? "Line " + f.line : "Lines " + f.line + "–" + f.end;
      return '<article class="finding"><button class="find-head" type="button" aria-expanded="' + (i === 0) + '" aria-controls="b-' + f.uid + '" id="h-' + f.uid + '">' +
        '<span class="sev sev-' + f.sev + '">' + SEV_LABEL[f.sev] + '</span><span class="find-title">' + esc(f.title) + '</span><span class="find-meta"><span>' + esc(CAT_LABEL[f.cat]) + '</span><span class="num">' + lines + '</span><span class="badge">Sample finding</span></span>' + icon("chevron", "chev") + "</button>" +
        '<div class="find-body" id="b-' + f.uid + '" role="region" aria-labelledby="h-' + f.uid + '"' + (i === 0 ? "" : " hidden") + ">" +
        '<div class="why-grid"><div><h4>What we found</h4><p>' + esc(f.explain) + "</p></div><div><h4>Why it matters</h4><p>" + esc(f.why) + "</p></div></div>" +
        '<div class="compare-bar"><div class="seg" role="group" aria-label="Code view"><button type="button" data-view="both" aria-pressed="true">Side by side</button><button type="button" data-view="original" aria-pressed="false">Original</button><button type="button" data-view="suggested" aria-pressed="false">Suggested</button></div></div>' +
        '<div class="compare" data-view="both"><div class="cmp cmp-orig"><div class="cmp-head"><span class="eyebrow">Original · ' + lines.toLowerCase() + '</span><button class="btn btn-sm btn-ghost" type="button" data-copy="o">' + icon("copy") + "Copy original</button></div>" + X.codeBlock(f.original, { start: f.line }) + "</div>" +
        '<div class="cmp cmp-sug"><div class="cmp-head"><span class="eyebrow">' + sugLabel(f.kind) + '</span><button class="btn btn-sm btn-ghost" type="button" data-copy="s">' + icon("copy") + "Copy suggested</button></div>" + X.codeBlock(f.suggested) + "</div></div>" +
        '<div class="facts"><div><h4>What changed</h4><p>' + esc(f.changes) + "</p></div><div><h4>Expected benefit</h4><p>" + esc(f.benefits) + "</p></div><div><h4>Trade-offs &amp; limits</h4><p>" + esc(f.tradeoffs) + (f.assumptions ? " " + esc(f.assumptions) : "") + "</p></div></div>" +
        "<div><h4>Complexity</h4>" + cplxTable(f) + "</div>" +
        '<p class="faint" style="font-size:12.5px">This is one possible improvement, not the only correct or optimal one. Check it against your requirements and tests before adopting it.</p></div></article>';
    }).join("");
    $$(".finding", el).forEach((art, i) => {
      const f = list[i], head = $(".find-head", art), body = $(".find-body", art);
      head.addEventListener("click", () => { const open = head.getAttribute("aria-expanded") === "true"; head.setAttribute("aria-expanded", String(!open)); body.hidden = open; });
      $$(".compare-bar button", art).forEach((b) => b.addEventListener("click", () => { $(".compare", art).dataset.view = b.dataset.view; $$(".compare-bar button", art).forEach((x) => x.setAttribute("aria-pressed", String(x === b))); }));
      $$("[data-copy]", art).forEach((b) => b.addEventListener("click", () => X.copyText(b.dataset.copy === "o" ? f.original : f.suggested, b.dataset.copy === "o" ? "Original code" : "Suggested code")));
    });
  }

  /* ---- run ---- */
  let running = false;
  $("#reviewBtn").addEventListener("click", run);
  function run() {
    if (running) return;
    const code = ta.value, lang = langSel.value;
    const pre = R.analyze(code, lang);
    if (pre.error) { $("#inputErr").textContent = pre.message; ta.focus(); if (!current) renderEmpty(); return; }
    running = true; const btn = $("#reviewBtn"); btn.disabled = true; btn.lastChild.textContent = "Reviewing…";
    renderLoading(); $("#results").hidden = true;
    const steps = $$("#steps li"); let k = 0;
    const stepMs = X.reduceMotion() ? 120 : 380;
    // Server reviews start immediately and run alongside the step animation.
    const remote = serverReview ? X.api.review(code, lang, fileName).then((r) => ({ r }), (e) => ({ e })) : null;
    (function next() {
      if (k > 0) steps[k - 1].className = "done";
      if (k < steps.length) { steps[k].className = "active"; k++; setTimeout(next, stepMs); return; }
      const finish = () => { running = false; btn.disabled = false; btn.lastChild.textContent = "Review code"; };
      if (remote) {
        remote.then(({ r, e }) => {
          if (e) {
            const msg = e.network ? "Couldn't reach the review server. Check your connection and try again."
              : e.code === "timeout" ? "The analysis took too long. Try a smaller piece of code." : e.message;
            renderError(msg); $("#inputErr").textContent = msg; finish(); return;
          }
          try {
            current = r; store.addReview(r);
            renderScore(r, true); renderResults(r);
            X.toast("Review saved to your account · score " + r.overall + "/100");
          } catch (err) { console.error(err); renderError("Something went wrong while showing the result. Try again."); }
          finish();
        });
        return;
      }
      try {
        const rev = Object.assign(pre, { id: "r" + Date.now().toString(36), at: Date.now(), code: code, file: fileName || null });
        current = rev; store.addReview(rev);
        renderScore(rev, true); renderResults(rev);
        X.toast("Review saved to history · score " + rev.overall + "/100");
      } catch (e) { console.error(e); renderError("Something went wrong while scoring. Try again, or simplify the snippet."); }
      finish();
    })();
  }
  ta.addEventListener("keydown", (e) => { if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); run(); } });

  /* ---- entry: stored review, hand-off from a problem, or empty ---- */
  const rid = params.get("id");
  const handoff = (function () { try { const h = JSON.parse(localStorage.getItem("crx.handoff") || "null"); localStorage.removeItem("crx.handoff"); return h; } catch (e) { return null; } })();
  if (rid) {
    const rev = store.getReview(rid);
    if (rev) { langSel.value = rev.lang; ed.set(rev.code || ""); current = rev; renderScore(rev, false); renderResults(rev); $("#fileName").textContent = "Saved review · " + X.fmtDate(rev.at, true) + (rev.sample ? " · sample record" : ""); }
    else { renderEmpty(); $("#inputErr").textContent = "That review isn't in this browser's history anymore."; }
  } else if (handoff && handoff.code) {
    langSel.value = handoff.lang || langSel.value; ed.set(handoff.code); $("#fileName").textContent = handoff.from || ""; renderEmpty();
    X.toast("Code loaded from practice. Choose Review code to run it.", "file");
  } else renderEmpty();
})();
