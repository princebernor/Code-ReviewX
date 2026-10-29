/* Code ReveiwX — placement practice: topic explorer, filters, collections. */
(function () {
  "use strict";
  const X = window.CRX, { $, $$, esc, icon, store } = X;
  if (!X.shell({ active: "placement", title: "Placement Practice" })) return;

  const ALL = window.CRX_PROBLEMS, TOPICS = window.CRX_TOPICS, DETAILS = window.CRX_DETAILS || {};
  const TL = {}; TOPICS.forEach((t) => (TL[t.slug] = t.label));
  const params = new URLSearchParams(location.search);
  const PAGE = 60;
  const state = { q: "", topic: params.get("topic") || "", diff: params.get("diff") || "", status: params.get("status") || "", col: "", limit: PAGE };

  const COLLECTIONS = [
    { id: "imp", name: "Marked important in the sheet", desc: "Problems or patterns the sheet tags as imp / must solve", test: (p) => /\bimp\b|must solve|super imp/i.test(p.n + " " + p.p) },
    { id: "walk", name: "With full walkthrough", desc: "Statement, tests and solution included here", test: (p) => !!DETAILS[p.id] },
    { id: "window", name: "Two pointers & sliding window", desc: "Core array-interview techniques", test: (p) => /two pointer/i.test(p.p) || p.topic === "sliding-window" },
    { id: "start", name: "Beginner path", desc: "Basics, arrays and recursion (easy estimates)", test: (p) => ["basics", "arrays", "recursion"].indexOf(p.topic) > -1 && p.d === "Easy" },
    { id: "hard", name: "Interview stretch (hard est.)", desc: "Problems this app estimates as hard", test: (p) => p.d === "Hard" }
  ];

  $("#fTopic").innerHTML += TOPICS.map((t) => '<option value="' + t.slug + '">' + esc(t.label) + "</option>").join("");

  function statusOf(id) { const p = store.progress()[id]; return p ? p.status : "unsolved"; }

  function renderTopics() {
    const stats = X.practiceStats();
    $("#topics").innerHTML = TOPICS.map((t) => {
      const s = stats.byTopic[t.slug]; const pct = s.total ? s.solved / s.total : 0;
      return '<button class="topic" type="button" data-topic="' + t.slug + '" aria-pressed="' + (state.topic === t.slug) + '"><span class="topic-name">' + esc(t.label) + '</span><span class="topic-meta"><span>' + s.total + " problems</span><span>" + s.solved + " solved</span></span>" +
        '<span class="bar" aria-hidden="true"><span style="--v:' + pct + '"></span></span><span class="sr-only">' + Math.round(pct * 100) + "% complete</span></button>";
    }).join("");
    $$("#topics .topic").forEach((b) => b.addEventListener("click", () => { state.topic = state.topic === b.dataset.topic ? "" : b.dataset.topic; state.limit = PAGE; sync(); }));
  }

  function renderSide() {
    const s = X.practiceStats();
    $("#sideStats").innerHTML = '<div><dt>Solved</dt><dd>' + s.solved + "</dd></div><div><dt>Attempted</dt><dd>" + s.attempted + "</dd></div><div><dt>Remaining</dt><dd>" + (s.total - s.solved) + '</dd></div><div><dt>Streak</dt><dd>' + X.streak() + "d</dd></div>";
    const n = X.nextProblem();
    $("#nextBox").innerHTML = n ? '<span class="eyebrow">Recommended next</span><a href="problem.html?id=' + n.id + '" style="color:var(--text);font-weight:550">#' + n.id + " " + esc(n.t) + '</a><span class="faint" style="font-size:12.5px">' + esc(TL[n.topic]) + " · " + esc(n.p) + '</span><a class="btn btn-sm btn-accent" style="justify-self:start" href="problem.html?id=' + n.id + '">Continue practice</a>' : "<p class=\"muted\">You've marked every problem solved.</p>";
    $("#collections").innerHTML = COLLECTIONS.map((c) => '<button class="collection" type="button" data-col="' + c.id + '" aria-pressed="' + (state.col === c.id) + '"><b>' + esc(c.name) + "</b><span>" + esc(c.desc) + "</span><i>" + ALL.filter(c.test).length + "</i></button>").join("");
    $$("#collections .collection").forEach((b) => b.addEventListener("click", () => { state.col = state.col === b.dataset.col ? "" : b.dataset.col; state.limit = PAGE; sync(); }));
  }

  function filtered() {
    const q = state.q.trim().toLowerCase(); const col = COLLECTIONS.find((c) => c.id === state.col);
    return ALL.filter((p) => (!state.topic || p.topic === state.topic) && (!state.diff || p.d === state.diff) &&
      (!state.status || statusOf(p.id) === state.status) && (!col || col.test(p)) &&
      (!q || p.t.toLowerCase().indexOf(q) > -1 || p.p.toLowerCase().indexOf(q) > -1 || String(p.id) === q.replace("#", "")));
  }

  function renderList() {
    const list = filtered(); const shown = list.slice(0, state.limit);
    const parts = [state.topic && TL[state.topic], state.diff, state.status, state.col && COLLECTIONS.find((c) => c.id === state.col).name, state.q && "“" + state.q + "”"].filter(Boolean);
    $("#resultCount").textContent = list.length + " of " + ALL.length + " problems" + (parts.length ? " · " + parts.join(" · ") : "");
    if (!list.length) {
      $("#pbody").innerHTML = '<tr><td colspan="6"><div class="empty"><div class="empty-icon">' + icon("search") + "</div><h3>No problems match</h3><p>Try a different search term, or reset the filters.</p></div></td></tr>";
      $("#more").hidden = true; return;
    }
    let lastKey = "", rows = "";
    shown.forEach((p) => {
      const key = p.topic + "|" + p.p;
      if (key !== lastKey && !state.q) { rows += '<tr class="pattern-row"><td colspan="6">' + esc(TL[p.topic]) + " / " + esc(p.p) + "</td></tr>"; lastKey = key; }
      const st = statusOf(p.id);
      rows += "<tr><td class=\"col-id\">" + p.id + '</td><td class="p-title"><a href="problem.html?id=' + p.id + '">' + esc(p.t) + "</a>" + (p.n ? '<span class="p-note">' + esc(p.n) + "</span>" : "") + "</td>" +
        '<td class="hide-md faint" style="font-size:13px">' + esc(TL[p.topic]) + '</td><td><span class="diff-' + p.d.toLowerCase() + '" style="font-size:13px">' + p.d + '</span></td><td><span class="status ' + st + '">' + st.charAt(0).toUpperCase() + st.slice(1) + "</span></td>" +
        '<td><div class="row-actions">' + (p.u ? '<a class="btn btn-sm btn-ghost btn-icon" href="' + esc(p.u) + '" target="_blank" rel="noopener noreferrer" aria-label="Open source practice page for ' + esc(p.t) + ' (new tab)" title="Source practice page">' + icon("ext") + "</a>" : '<span class="btn btn-sm btn-ghost btn-icon" aria-hidden="true" title="No source link in the sheet" style="opacity:.35;cursor:default">' + icon("ext") + '</span><span class="sr-only">No source link in the sheet</span>') +
        '<a class="btn btn-sm" href="problem.html?id=' + p.id + '">Open</a></div></td></tr>';
    });
    $("#pbody").innerHTML = rows;
    $("#more").hidden = list.length <= state.limit;
    $("#moreBtn").textContent = "Show " + Math.min(PAGE, list.length - state.limit) + " more";
  }

  function sync() {
    $("#fTopic").value = state.topic; $("#fDiff").value = state.diff; $("#fStatus").value = state.status;
    const u = new URLSearchParams(); ["topic", "diff", "status"].forEach((k) => state[k] && u.set(k, state[k]));
    history.replaceState(null, "", location.pathname + (u.toString() ? "?" + u : ""));
    renderTopics(); renderSide(); renderList();
  }

  let t; $("#q").addEventListener("input", (e) => { clearTimeout(t); t = setTimeout(() => { state.q = e.target.value; state.limit = PAGE; renderList(); }, 120); });
  $("#fTopic").addEventListener("change", (e) => { state.topic = e.target.value; state.limit = PAGE; sync(); });
  $("#fDiff").addEventListener("change", (e) => { state.diff = e.target.value; state.limit = PAGE; sync(); });
  $("#fStatus").addEventListener("change", (e) => { state.status = e.target.value; state.limit = PAGE; sync(); });
  $("#resetF").addEventListener("click", () => { Object.assign(state, { q: "", topic: "", diff: "", status: "", col: "", limit: PAGE }); $("#q").value = ""; sync(); });
  $("#moreBtn").addEventListener("click", () => { state.limit += PAGE; renderList(); });
  sync();
})();
