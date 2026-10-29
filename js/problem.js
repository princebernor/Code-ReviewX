/* Code ReveiwX — problem-solving interface. Code is never executed in the browser; signed-in accounts run it
   through the server's external judge (POST /api/run), demo sessions show the expected outputs only. */
(function () {
  "use strict";
  const X = window.CRX, { $, $$, esc, icon, store } = X;
  const ALL = window.CRX_PROBLEMS, DETAILS = window.CRX_DETAILS || {};
  const TL = {}; window.CRX_TOPICS.forEach((t) => (TL[t.slug] = t.label));
  const id = parseInt(new URLSearchParams(location.search).get("id"), 10);
  const p = ALL[id - 1];
  if (!X.shell({ active: "placement", title: p ? "#" + p.id + " " + p.t : "Problem", crumb: { href: "placement.html", label: "Practice" } })) return;

  if (!p) {
    $("#pbGrid").innerHTML = '<div class="card span-all" style="grid-column:1/-1"><div class="empty"><div class="empty-icon">' + icon("search") + '</div><h3>Problem not found</h3><p>There\'s no problem with that number in the sheet. Problems are numbered 1 to ' + ALL.length + '.</p><a class="btn btn-sm" href="placement.html">Back to the problem list</a></div></div>';
    return;
  }
  document.title = "#" + p.id + " " + p.t + " · Code ReveiwX";
  const d = DETAILS[p.id] || null;

  /* ---------- header ---------- */
  function statusOf() { const s = store.progress()[p.id]; return s ? s.status : "unsolved"; }
  function renderHead() {
    const st = statusOf();
    $("#pbHead").innerHTML = '<div style="min-width:0"><span class="eyebrow">' + esc(TL[p.topic]) + " / " + esc(p.p) + "</span><h1 style=\"margin-top:8px\">" + esc(p.t) + "</h1>" +
      '<div class="pb-meta"><span class="badge num">#' + p.id + ' of 442</span><span class="badge"><span class="diff-' + p.d.toLowerCase() + '">' + p.d + '</span>&nbsp;· estimate</span><span class="status ' + st + '">' + st.charAt(0).toUpperCase() + st.slice(1) + "</span>" +
      (p.u ? '<a class="btn btn-sm btn-ghost" href="' + esc(p.u) + '" target="_blank" rel="noopener noreferrer">' + icon("ext") + 'Source practice page<span class="sr-only"> (opens in a new tab)</span></a>' : '<span class="faint" style="font-size:12.5px">No source link in the sheet</span>') + "</div></div>" +
      '<div style="display:flex;gap:8px">' + (p.id > 1 ? '<a class="btn btn-sm" href="problem.html?id=' + (p.id - 1) + '" aria-label="Previous problem">← Prev</a>' : "") + (p.id < ALL.length ? '<a class="btn btn-sm" href="problem.html?id=' + (p.id + 1) + '" aria-label="Next problem">Next →</a>' : "") + "</div>";
  }
  renderHead();

  /* ---------- left: description / tests / solution ---------- */
  const tests = d ? d.tests : [];
  const descHTML = d
    ? "<div><h3>Problem</h3><p>" + esc(d.desc) + "</p></div><div><h3>Input</h3><p>" + esc(d.input) + "</p></div><div><h3>Output</h3><p>" + esc(d.output) + "</p></div>" +
      "<div><h3>Examples</h3><div style=\"display:grid;gap:8px\">" + d.examples.map((e, i) => '<div class="example"><span><b>Example ' + (i + 1) + "</b></span><span><b>Input:</b> " + esc(e.in) + "</span><span><b>Output:</b> " + esc(e.out) + "</span>" + (e.note ? "<span><b>Why:</b> " + esc(e.note) + "</span>" : "") + "</div>").join("") + "</div></div>" +
      "<div><h3>Constraints</h3><ul>" + d.constraints.map((c) => '<li class="num" style="font-size:13.5px">' + esc(c) + "</li>").join("") + "</ul></div>" +
      '<p class="faint" style="font-size:12.5px">Statement written for this demo from the standard version of the problem. The linked source may differ in details.</p>'
    : '<div class="notice" role="note">' + icon("info") + "<span><strong>Full statement not included.</strong> The coding sheet lists this problem's title" + (p.u ? " and a practice link" : "") + ", not its statement, input format or constraints. Rather than invent them, this page links to the source.</span></div>" +
      "<div><h3>Problem</h3><p>" + esc(p.t) + "</p></div>" + (p.u ? '<a class="btn btn-sm" style="justify-self:start" href="' + esc(p.u) + '" target="_blank" rel="noopener noreferrer">' + icon("ext") + "Read the full statement on the source site</a>" : "");
  const sheetTip = p.n ? '<div class="tip-box"><h3>Tip from the sheet</h3><p>' + esc(p.n) + "</p></div>" : "";
  const testsHTML = tests.length
    ? '<p class="faint" style="font-size:13px">Select the sample cases to include when you choose <strong>Run</strong>.</p><div class="cases" role="group" aria-label="Sample test cases">' + tests.map((t, i) => '<button class="case" type="button" aria-pressed="true" data-i="' + i + '"><input type="checkbox" checked tabindex="-1" aria-hidden="true"><span>' + esc(t.in) + '</span><small>→ ' + esc(t.out) + "</small></button>").join("") + "</div>"
    : '<div class="empty"><h3>No sample tests for this problem</h3><p>The sheet doesn\'t provide test cases. Use the examples on the source practice page.</p></div>';
  const solHTML = d
    ? '<details class="sol" open><summary>' + icon("chevron") + "Approach</summary><div><p>" + esc(d.approach) + '</p><table class="cplx" style="width:100%;border-collapse:collapse;font-size:13px"><tbody><tr><th scope="row" style="text-align:left;padding:8px 12px;border:1px solid var(--line);color:var(--text-3);font-weight:550">Time</th><td class="num" style="padding:8px 12px;border:1px solid var(--line)">' + esc(d.time) + '</td></tr><tr><th scope="row" style="text-align:left;padding:8px 12px;border:1px solid var(--line);color:var(--text-3);font-weight:550">Space</th><td class="num" style="padding:8px 12px;border:1px solid var(--line)">' + esc(d.space) + "</td></tr></tbody></table></div></details>" +
      '<details class="sol"><summary>' + icon("chevron") + "Reference solution (spoiler)</summary><div>" + Object.keys(d.solution).map((l) => '<div style="display:grid;gap:8px"><div style="display:flex;justify-content:space-between;align-items:center"><span class="eyebrow">' + X.langLabel(l) + '</span><button class="btn btn-sm btn-ghost" type="button" data-copysol="' + l + '">' + icon("copy") + "Copy</button></div>" + X.codeBlock(d.solution[l]) + "</div>").join("") +
      '<p class="faint" style="font-size:12.5px">One clear approach, not the only correct one. Edge-case handling may differ from the source judge.</p></div></details>'
    : '<div class="empty"><h3>No walkthrough for this problem yet</h3><p>Walkthroughs are included for ' + Object.keys(DETAILS).length + ' sheet problems. For this one, check the discussion on the source page, or write your solution and send it to the review workspace for feedback.</p></div>';

  $("#pbGrid").innerHTML =
    '<section class="card pb-left" aria-label="Problem details"><div class="tabs" role="tablist" aria-label="Problem sections">' +
    '<button role="tab" type="button" id="t-desc" aria-controls="p-desc" aria-selected="true">Description</button>' +
    '<button role="tab" type="button" id="t-tests" aria-controls="p-tests" aria-selected="false" tabindex="-1">Test cases' + (tests.length ? " (" + tests.length + ")" : "") + "</button>" +
    '<button role="tab" type="button" id="t-sol" aria-controls="p-sol" aria-selected="false" tabindex="-1">Solution &amp; complexity</button></div>' +
    '<div class="pb-pane" role="tabpanel" id="p-desc" aria-labelledby="t-desc">' + descHTML + sheetTip + "</div>" +
    '<div class="pb-pane" role="tabpanel" id="p-tests" aria-labelledby="t-tests" hidden>' + testsHTML + "</div>" +
    '<div class="pb-pane" role="tabpanel" id="p-sol" aria-labelledby="t-sol" hidden>' + solHTML + "</div></section>" +

    '<section class="card pb-editor" aria-label="Code editor"><div class="rv-toolbar" style="display:flex;gap:8px;align-items:center;padding:12px 14px;flex-wrap:wrap">' +
    '<label class="sr-only" for="plang">Language</label><select class="select select-sm" id="plang" style="width:auto">' + X.langOptions(store.prefs().lang) + "</select>" +
    '<span class="faint num" style="font-size:12px;margin-left:auto" id="pLines">0 lines</span><button class="btn btn-sm btn-ghost" type="button" id="resetCode">Reset to starter</button></div>' +
    '<div class="editor"><div class="editor-gutter" aria-hidden="true">1</div><label class="sr-only" for="pcode">Your solution</label><textarea id="pcode" spellcheck="false" autocomplete="off" autocapitalize="off"></textarea></div>' +
    '<div class="pb-actions"><button class="btn btn-sm" type="button" id="runBtn">' + icon("play") + 'Run</button><button class="btn btn-sm btn-primary" type="button" id="submitBtn">Submit</button><span class="grow"></span>' +
    '<button class="btn btn-sm btn-ghost" type="button" id="solvedBtn"></button><button class="btn btn-sm btn-accent" type="button" id="toReview">' + icon("review") + "Review this code</button></div>" +
    '<div class="output" id="output" aria-live="polite"><span class="eyebrow">Output</span><p class="faint">Run shows the selected sample cases with their expected output. Code is not compiled or executed in this frontend demo.</p></div></section>';

  /* tabs */
  const tabs = $$('[role="tab"]');
  function sel(t) { tabs.forEach((x) => { const on = x === t; x.setAttribute("aria-selected", String(on)); x.tabIndex = on ? 0 : -1; $("#" + x.getAttribute("aria-controls")).hidden = !on; }); }
  tabs.forEach((t, i) => { t.addEventListener("click", () => sel(t)); t.addEventListener("keydown", (e) => { if (e.key === "ArrowRight" || e.key === "ArrowLeft") { const n = tabs[(i + (e.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length]; sel(n); n.focus(); } }); });
  $$(".case").forEach((c) => c.addEventListener("click", () => { const on = c.getAttribute("aria-pressed") !== "true"; c.setAttribute("aria-pressed", String(on)); $("input", c).checked = on; }));
  $$("[data-copysol]").forEach((b) => b.addEventListener("click", () => X.copyText(d.solution[b.dataset.copysol], "Solution")));

  /* ---------- editor + starter code ---------- */
  const fn = p.t.replace(/\(.*?\)/g, "").replace(/[^a-zA-Z0-9 ]/g, " ").trim().split(/\s+/).slice(0, 5).map((w, i) => (i ? w.charAt(0).toUpperCase() + w.slice(1).toLowerCase() : w.toLowerCase())).join("") || "solve";
  const fnName = /^[0-9]/.test(fn) ? "solve" + fn : fn;
  const snake = fnName.replace(/[A-Z]/g, (c) => "_" + c.toLowerCase());
  function starter(lang) {
    const head = "Problem #" + p.id + ": " + p.t;
    return {
      c: "/* " + head + " */\n#include <stdio.h>\n\nint " + fnName + "(int arr[], int n) {\n    // write your solution here\n    return 0;\n}\n\nint main(void) {\n    return 0;\n}\n",
      cpp: "// " + head + "\n#include <iostream>\n#include <vector>\n\nint " + fnName + "(const std::vector<int>& arr) {\n    // write your solution here\n    return 0;\n}\n\nint main() {\n    return 0;\n}\n",
      java: "// " + head + "\nclass Solution {\n    public int " + fnName + "(int[] arr) {\n        // write your solution here\n        return 0;\n    }\n}\n",
      python: "# " + head + "\ndef " + snake + "(arr):\n    # write your solution here\n    pass\n",
      javascript: "// " + head + "\nfunction " + fnName + "(arr) {\n  // write your solution here\n}\n",
      go: "// " + head + "\npackage main\n\nfunc " + fnName + "(arr []int) int {\n    // write your solution here\n    return 0\n}\n\nfunc main() {}\n"
    }[lang];
  }
  const ta = $("#pcode"), langSel = $("#plang");
  let saveT;
  const ed = X.editor(ta, (v, n) => { $("#pLines").textContent = n + " line" + (n === 1 ? "" : "s"); clearTimeout(saveT); saveT = setTimeout(() => store.saveDraft(p.id, langSel.value, v), 300); });
  window.addEventListener("pagehide", () => { clearTimeout(saveT); store.saveDraft(p.id, langSel.value, ta.value); });
  function loadLang() { const dr = store.drafts()[p.id + ":" + langSel.value]; ed.set(dr != null ? dr : starter(langSel.value)); }
  loadLang();
  langSel.addEventListener("change", () => { store.setPref("lang", langSel.value); loadLang(); });
  $("#resetCode").addEventListener("click", () => X.dialog({ title: "Reset to starter code?", body: "<p>This replaces your " + X.langLabel(langSel.value) + " code for this problem with the starter template.</p>", actions: [{ label: "Cancel" }, { label: "Reset", primary: true, onClick: () => { ed.set(starter(langSel.value)); X.toast("Starter code restored"); } }] }));

  const hasWork = () => { const v = ta.value.trim(); return v && v !== starter(langSel.value).trim(); };
  const out = $("#output");

  /* ---------- real execution for signed-in accounts (server → external judge); demo sessions keep the demonstration ---------- */
  const judge = !!(X.api && X.api.isAccount());
  if (judge) {
    out.innerHTML = '<span class="eyebrow">Output</span><p class="faint">Run executes your code against the selected test cases (or your custom input) on the code-execution service. Submit runs every test case.</p>';
    $("#p-tests").insertAdjacentHTML("beforeend", '<div class="field" style="margin-top:4px"><label class="label" for="stdinBox">Custom input (stdin)</label>' +
      '<textarea class="input" id="stdinBox" rows="4" spellcheck="false" style="font-family:var(--font-mono);font-size:12.5px;min-height:84px;resize:vertical" placeholder="Typed input for your program\'s main()"></textarea>' +
      '<p class="faint" style="font-size:12.5px">Used when no test case is selected: your whole program runs with this input and its output is shown.</p></div>');
  }
  let running = false;
  function setBusy(on) { running = on; ["#runBtn", "#submitBtn"].forEach((s) => ($(s).disabled = on)); }
  function pre(text) { return '<pre>' + esc(text) + "</pre>"; }

  function renderRun(r, submit) {
    const head = '<span class="eyebrow">' + (submit ? "Submit" : "Output") + " · " + esc(r.judge || "judge") + "</span>" +
      '<div class="notice" role="note">' + icon("info") + "<span><strong>Executed remotely.</strong> Your code was sent to " + esc(r.judge || "the code-execution service") + ", an external service, to compile and run with time and memory limits.</span></div>";
    if (r.setupError) return head + '<p class="field-error">' + esc(r.setupError) + "</p>";
    let h = head;
    if (r.statusId === 6) return h + '<p class="field-error"><strong>Compilation error.</strong> Fix it and run again.</p>' + pre(r.compileOutput || r.message || "");
    if (r.mode === "tests") {
      const all = r.allPassed;
      h += '<p style="font-weight:600;color:' + (all ? "var(--good)" : "var(--sev-high)") + '">' + (all ? "All " + r.total + " test" + (r.total === 1 ? "" : "s") + " passed" : "Passed " + r.passed + " of " + r.total) + "</p>";
      h += r.cases.map((c, i) => {
        const ok = c.passed, yours = c.error ? c.error : c.actual == null ? "no output" : c.actual;
        return '<div class="out-row"><b>Case ' + (i + 1) + "</b><span>" + esc(c.input) + '</span></div><div class="out-row"><b>Expected</b><span style="color:var(--good)">' + esc(c.expected) + "</span></div>" +
          '<div class="out-row"><b>Yours</b><span style="color:' + (ok ? "var(--good)" : "var(--sev-high)") + '">' + esc(yours) + '</span></div><div class="out-row"><b>Verdict</b><span style="color:' + (ok ? "var(--good)" : "var(--sev-high)") + '">' + esc(c.verdict) + "</span></div>";
      }).join('<hr class="divider">');
      if (r.stdout) h += '<span class="eyebrow">Your printed output</span>' + pre(r.stdout);
    } else {
      h += '<div class="out-row"><b>Status</b><span style="color:' + (r.statusId === 3 ? "var(--good)" : "var(--sev-high)") + '">' + esc(r.status) + "</span></div>";
      h += '<span class="eyebrow">Output</span>' + pre(r.stdout || "(no output)");
    }
    if (r.stderr) h += '<span class="eyebrow">Errors (stderr)</span>' + pre(r.stderr);
    if (r.time != null || r.memoryKb != null) h += '<p class="faint" style="font-size:12px">' + (r.time != null ? "Time " + r.time + " s" : "") + (r.memoryKb != null ? " · Memory " + Math.round(r.memoryKb / 1024) + " MB" : "") + "</p>";
    return h;
  }

  async function judgeRun(submit) {
    if (running) return;
    const chosen = submit ? tests : $$(".case").filter((c) => c.getAttribute("aria-pressed") === "true").map((c) => tests[+c.dataset.i]);
    const stdin = ($("#stdinBox") || { value: "" }).value;
    if (!submit && !chosen.length && tests.length && !stdin.trim()) {
      out.innerHTML = '<span class="eyebrow">Output</span><p class="field-error">Select at least one test case in the Test cases tab, or enter custom input there.</p>'; return;
    }
    setBusy(true);
    out.innerHTML = '<span class="eyebrow">' + (submit ? "Submit" : "Output") + ' · running</span><div class="skel" style="width:60%"></div><div class="skel" style="width:40%"></div>';
    try {
      const r = await X.api.run({ lang: langSel.value, code: ta.value, mode: chosen.length ? "tests" : "stdin", cases: chosen.map((t) => ({ in: t.in, out: t.out })), stdin: stdin });
      out.innerHTML = renderRun(r, submit);
      if (submit) {
        if (r.allPassed) { store.setStatus(p.id, "solved"); X.toast("All tests passed. Marked as solved"); }
        else { store.setStatus(p.id, "attempted"); X.toast("Saved as attempted", "info"); }
        renderHead(); renderSolved();
      }
    } catch (e) {
      out.innerHTML = '<span class="eyebrow">' + (submit ? "Submit" : "Output") + '</span><p class="field-error">' + esc(e.network ? "Couldn't reach the server. Check your connection and try again." : e.message) + "</p>";
    } finally { setBusy(false); }
  }

  $("#runBtn").addEventListener("click", () => {
    if (!hasWork()) { out.innerHTML = '<span class="eyebrow">Output</span><p class="field-error">Write some code first. The editor still has the starter template.</p>'; return; }
    if (judge) { judgeRun(false); return; }
    const chosen = $$(".case").filter((c) => c.getAttribute("aria-pressed") === "true").map((c) => tests[+c.dataset.i]);
    out.innerHTML = '<span class="eyebrow">Output · demonstration</span><div class="skel" style="width:60%"></div>';
    setTimeout(() => {
      if (!tests.length) { out.innerHTML = '<span class="eyebrow">Output · demonstration</span><p class="muted">This problem has no sample tests here, and your code isn\'t executed in this frontend demo. Run it locally or on the source practice page.</p>'; return; }
      if (!chosen.length) { out.innerHTML = '<span class="eyebrow">Output</span><p class="field-error">Select at least one test case in the Test cases tab.</p>'; return; }
      out.innerHTML = '<span class="eyebrow">Output · demonstration</span><div class="notice" role="note">' + icon("info") + "<span><strong>Not executed.</strong> No compiler or runtime is connected, so your " + X.langLabel(langSel.value) + " code wasn't run. Below are the expected outputs to check your logic against by hand. Real execution needs a separate sandboxed service.</span></div>" +
        chosen.map((t, i) => '<div class="out-row"><b>Case ' + (i + 1) + "</b><span>" + esc(t.in) + '</span></div><div class="out-row"><b>Expected</b><span style="color:var(--good)">' + esc(t.out) + '</span></div><div class="out-row"><b>Yours</b><span class="faint">not executed</span></div>').join('<hr class="divider">');
    }, X.reduceMotion() ? 0 : 450);
  });

  $("#submitBtn").addEventListener("click", () => {
    if (!hasWork()) { out.innerHTML = '<span class="eyebrow">Submit</span><p class="field-error">Nothing to submit yet. Replace the starter template with your solution.</p>'; return; }
    if (judge && tests.length) { judgeRun(true); return; }
    store.setStatus(p.id, "attempted"); renderHead(); renderSolved();
    out.innerHTML = '<span class="eyebrow">Submit · demonstration</span><p class="muted">Saved as <strong style="color:var(--sev-medium)">Attempted</strong>. There\'s no judge in this demo, so correctness can\'t be verified here. Check your answer against the expected outputs, or submit on the source practice page, then mark it solved.</p>';
    X.toast("Saved as attempted");
  });

  function renderSolved() {
    const solved = statusOf() === "solved";
    $("#solvedBtn").innerHTML = solved ? icon("check") + "Solved · undo" : "Mark as solved";
    $("#solvedBtn").setAttribute("aria-pressed", String(solved));
  }
  $("#solvedBtn").addEventListener("click", () => {
    if (statusOf() === "solved") { store.clearStatus(p.id); if (hasWork()) store.setStatus(p.id, "attempted"); X.toast("Marked as not solved", "info"); }
    else { store.setStatus(p.id, "solved"); X.toast("Marked as solved. Nice work"); }
    renderHead(); renderSolved();
  });
  renderSolved();

  $("#toReview").addEventListener("click", () => {
    if (!hasWork()) { out.innerHTML = '<span class="eyebrow">Review</span><p class="field-error">Write your solution first, then send it for review.</p>'; return; }
    try { localStorage.setItem("crx.handoff", JSON.stringify({ code: ta.value, lang: langSel.value, from: "From practice #" + p.id })); } catch (e) {}
    store.setStatus(p.id, "attempted");
    location.href = "review.html";
  });
})();
