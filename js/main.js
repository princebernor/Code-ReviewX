/* Code ReveiwX — shared utilities, demo state, and app shell.
   Everything runs in the browser. There is no server, database or AI service. */
(function () {
  "use strict";

  /* ---------------- Icons (inline stroke SVG) ---------------- */
  const P = {
    dashboard: '<rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/>',
    review: '<path d="m9 8-4 4 4 4"/><path d="m15 8 4 4-4 4"/><path d="m13.5 5-3 14"/>',
    practice: '<path d="M4 19.5V5a2 2 0 0 1 2-2h12v15H6a2 2 0 0 0-2 2Z"/><path d="M8 7h6M8 11h4"/>',
    history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l3 2"/>',
    progress: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"/>',
    menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
    close: '<path d="M6 6l12 12M18 6 6 18"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
    check: '<path d="m5 12 5 5L20 7"/>',
    copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 0 1 2-2h9"/>',
    upload: '<path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/>',
    trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>',
    play: '<path d="M7 5v14l11-7Z"/>',
    arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    ext: '<path d="M14 4h6v6M20 4 10 14"/><path d="M19 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
    chevron: '<path d="m9 6 6 6-6 6"/>',
    spark: '<path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6"/>',
    flame: '<path d="M12 22c4 0 7-3 7-7 0-3-2-5.5-3.5-7-.5 2-1.5 3-3 3.5C13 8 12 5 9 2c0 4-4 6.5-4 12 0 4.5 3 8 7 8Z"/>',
    logout: '<path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3"/><path d="M10 17l-5-5 5-5M5 12h11"/>',
    file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z"/><path d="M14 3v5h5"/>',
    target: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1"/>',
    download: '<path d="M12 4v12M7 11l5 5 5-5"/><path d="M4 20h16"/>'
  };
  function icon(name, cls) {
    return '<svg class="' + (cls || "") + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (P[name] || "") + "</svg>";
  }
  const LOGO = '<svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true"><rect x="1" y="1" width="30" height="30" rx="8" fill="var(--surface-3)" stroke="var(--line-2)"/><path d="M12.5 10 7 16l5.5 6" fill="none" stroke="var(--text)" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/><path d="m17 11 8 10M25 11l-8 10" fill="none" stroke="var(--accent)" stroke-width="2.2" stroke-linecap="round"/></svg>';
  function brandHTML(href) {
    return '<a class="brand" href="' + (href || "index.html") + '" aria-label="Code ReveiwX home">' + LOGO + '<span>Code Reveiw<span class="brand-x">X</span></span></a>';
  }

  /* ---------------- Storage (demo state only) ---------------- */
  const NS = "crx.";
  function get(key, fallback) {
    try { const v = localStorage.getItem(NS + key); return v == null ? fallback : JSON.parse(v); } catch (e) { return fallback; }
  }
  function set(key, value) {
    try { localStorage.setItem(NS + key, JSON.stringify(value)); return true; } catch (e) { return false; }
  }
  function del(key) { try { localStorage.removeItem(NS + key); } catch (e) {} }

  const store = {
    session: () => get("session", null),
    startDemo(name) { set("session", { mode: "demo", name: name || "Demo user", since: Date.now() }); },
    // Real account (js/api-client.js). Tokens live in HttpOnly cookies, never here.
    startAccount(u) { set("session", { mode: "account", name: u.name || "User", email: u.email, userId: u.userId, since: u.since || Date.now() }); },
    endDemo() { del("session"); },
    prefs: () => Object.assign({ theme: "dark", lang: "python" }, get("prefs", {})),
    setPref(k, v) { const p = store.prefs(); p[k] = v; set("prefs", p); },
    reviews: () => get("reviews", []),
    addReview(r) { const list = store.reviews(); list.unshift(r); set("reviews", list.slice(0, 60)); log("review", r.id); },
    getReview: (id) => store.reviews().find((r) => r.id === id) || null,
    setReviews: (list) => set("reviews", list),
    progress: () => get("progress", {}),
    setStatus(id, status) {
      const p = store.progress(); const prev = p[id] && p[id].status;
      if (prev === "solved" && status === "attempted") return; // never downgrade
      p[id] = { status: status, at: Date.now() }; set("progress", p); log(status, id);
    },
    clearStatus(id) { const p = store.progress(); delete p[id]; set("progress", p); },
    activity: () => get("activity", []),
    drafts: () => get("drafts", {}),
    saveDraft(id, lang, code) { const d = store.drafts(); d[id + ":" + lang] = code; set("drafts", d); },
    resetAll() { ["reviews", "progress", "activity", "drafts"].forEach(del); }
  };
  function log(type, ref) {
    const a = get("activity", []); a.unshift({ type: type, ref: ref, at: Date.now() }); set("activity", a.slice(0, 400));
  }

  /* ---------------- Theme ---------------- */
  function applyTheme(t) { document.documentElement.setAttribute("data-theme", t === "light" ? "light" : "dark"); }
  applyTheme(store.prefs().theme);

  /* ---------------- Helpers ---------------- */
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
  function fmtDate(ts, withTime) {
    const d = new Date(ts);
    const o = { day: "numeric", month: "short", year: "numeric" };
    if (withTime) { o.hour = "2-digit"; o.minute = "2-digit"; }
    return d.toLocaleString(undefined, o);
  }
  function ago(ts) {
    const s = Math.round((Date.now() - ts) / 1000);
    if (s < 60) return "just now"; if (s < 3600) return Math.floor(s / 60) + "m ago";
    if (s < 86400) return Math.floor(s / 3600) + "h ago"; return Math.floor(s / 86400) + "d ago";
  }
  const reduceMotion = () => window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const LANGS = [
    { id: "c", label: "C", ext: [".c", ".h"] },
    { id: "cpp", label: "C++", ext: [".cpp", ".cc", ".cxx", ".hpp"] },
    { id: "java", label: "Java", ext: [".java"] },
    { id: "python", label: "Python", ext: [".py"] },
    { id: "javascript", label: "JavaScript", ext: [".js", ".mjs", ".cjs"] },
    { id: "go", label: "Go", ext: [".go"] }
  ];
  const langLabel = (id) => (LANGS.find((l) => l.id === id) || { label: id }).label;
  function langOptions(sel) { return LANGS.map((l) => '<option value="' + l.id + '"' + (l.id === sel ? " selected" : "") + ">" + l.label + "</option>").join(""); }

  /* ---------------- Toast ---------------- */
  function toast(msg, ic) {
    let region = $(".toast-region");
    if (!region) { region = document.createElement("div"); region.className = "toast-region"; region.setAttribute("role", "status"); region.setAttribute("aria-live", "polite"); document.body.appendChild(region); }
    const t = document.createElement("div"); t.className = "toast is-entering";
    t.innerHTML = icon(ic || "check") + "<span>" + esc(msg) + "</span>";
    region.appendChild(t);
    requestAnimationFrame(() => requestAnimationFrame(() => t.classList.remove("is-entering")));
    setTimeout(() => { t.classList.add("is-leaving"); setTimeout(() => t.remove(), 240); }, 2800);
  }

  async function copyText(text, label) {
    try {
      if (navigator.clipboard && window.isSecureContext) await navigator.clipboard.writeText(text);
      else { const ta = document.createElement("textarea"); ta.value = text; ta.style.position = "fixed"; ta.style.opacity = "0"; document.body.appendChild(ta); ta.select(); document.execCommand("copy"); ta.remove(); }
      toast((label || "Code") + " copied to clipboard");
    } catch (e) { toast("Copy failed. Select the code and copy it manually.", "info"); }
  }

  /* ---------------- Dialog helper ---------------- */
  function dialog(opts) {
    const d = document.createElement("dialog"); d.className = "modal";
    d.setAttribute("aria-labelledby", "dlg-t");
    d.innerHTML = '<div class="modal-body"><h2 id="dlg-t">' + esc(opts.title) + "</h2>" + opts.body + '</div><div class="modal-actions"></div>';
    const actions = $(".modal-actions", d);
    (opts.actions || [{ label: "Close", primary: true }]).forEach((a) => {
      const b = document.createElement("button"); b.type = "button"; b.className = "btn btn-sm " + (a.primary ? "btn-primary" : "btn-ghost"); b.textContent = a.label;
      b.addEventListener("click", () => { d.close(); if (a.onClick) a.onClick(); }); actions.appendChild(b);
    });
    d.addEventListener("close", () => setTimeout(() => d.remove(), 250));
    d.addEventListener("click", (e) => { if (e.target === d) d.close(); });
    document.body.appendChild(d); d.showModal();
    return d;
  }

  /* ---------------- Code editor enhancer ---------------- */
  function editor(textarea, onChange) {
    const wrap = textarea.closest(".editor"); const gutter = $(".editor-gutter", wrap);
    function sync() {
      const n = Math.max(1, textarea.value.split("\n").length);
      let s = ""; for (let i = 1; i <= n; i++) s += i + "\n";
      gutter.textContent = s;
      gutter.scrollTop = textarea.scrollTop;
      if (onChange) onChange(textarea.value, n);
    }
    textarea.addEventListener("input", sync);
    textarea.addEventListener("scroll", () => { gutter.scrollTop = textarea.scrollTop; });
    textarea.addEventListener("keydown", (e) => {
      if (e.key === "Tab" && !e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey) {
        // Tab inserts indentation; Esc then Tab leaves the editor (keyboard escape hatch)
        if (textarea.dataset.tabEscape === "1") { textarea.dataset.tabEscape = "0"; return; }
        e.preventDefault();
        const s = textarea.selectionStart, en = textarea.selectionEnd;
        textarea.setRangeText("    ", s, en, "end"); sync();
      } else if (e.key === "Escape") { textarea.dataset.tabEscape = "1"; }
    });
    sync();
    return { refresh: sync, set(v) { textarea.value = v; sync(); }, get: () => textarea.value };
  }

  /* ---------------- Tiny syntax highlighter (display only) ---------------- */
  const KW = /\b(def|return|if|elif|else|for|while|in|not|and|or|class|import|from|const|let|var|function|new|public|private|static|void|int|long|char|bool|boolean|double|float|string|String|vector|auto|struct|func|package|range|nil|null|None|True|False|true|false|try|except|catch|throw|throws|break|continue|switch|case|include|using|namespace|std|map|make|type|is|lambda|with|as|this|self|unordered_map|HashMap|Map|Set|set|dict|list|len|print|printf|scanf|err|go|defer|fmt)\b/g;
  function highlight(code) {
    let h = esc(code);
    h = h.replace(/(&quot;.*?&quot;|&#39;.*?&#39;)/g, '<span class="tok-s">$1</span>');
    h = h.replace(/(\/\/[^\n]*|#(?!include)[^\n]*)$/gm, (m) => (m.indexOf("tok-s") > -1 ? m : '<span class="tok-c">' + m + "</span>"));
    h = h.split(/(<span[^>]*>.*?<\/span>)/g).map((part) => (part.startsWith("<span") ? part : part.replace(KW, '<span class="tok-k">$1</span>').replace(/\b(\d+)\b/g, '<span class="tok-n">$1</span>'))).join("");
    return h;
  }
  function codeBlock(code, opts) {
    opts = opts || {};
    const start = opts.start || 1, hl = opts.hl || [];
    const lines = highlight(code).split("\n");
    return '<pre class="code"><code>' + lines.map((l, i) => {
      const n = start + i; const inner = '<span class="ln">' + n + "</span>" + l;
      return hl.indexOf(n) > -1 ? '<span class="hl">' + inner + "</span>" : inner;
    }).join("\n") + "</code></pre>";
  }

  /* ---------------- App shell ---------------- */
  const NAV = [
    { href: "dashboard.html", key: "dashboard", label: "Dashboard", ic: "dashboard" },
    { href: "review.html", key: "review", label: "AI Code Review", ic: "review", tag: "CORE", flagship: true },
    { href: "placement.html", key: "placement", label: "Placement Practice", ic: "practice" },
    { href: "history.html", key: "history", label: "Review History", ic: "history" },
    { href: "progress.html", key: "progress", label: "My Progress", ic: "progress" },
    { href: "settings.html", key: "settings", label: "Settings", ic: "settings" }
  ];

  function shell(opts) {
    const session = store.session();
    if (!session) { location.replace("index.html?next=" + encodeURIComponent(location.pathname.split("/").pop() + location.search)); return false; }
    const app = $(".app");
    const nav = NAV.map((n) => '<a class="nav-link' + (n.flagship ? " flagship" : "") + '" href="' + n.href + '"' + (n.key === opts.active ? ' aria-current="page"' : "") + ">" + icon(n.ic) + "<span>" + n.label + "</span>" + (n.tag ? '<span class="nav-tag">' + n.tag + "</span>" : "") + "</a>").join("");
    const account = session.mode === "account";
    const side = document.createElement("aside"); side.className = "sidebar"; side.id = "sidebar"; side.setAttribute("aria-label", "Primary");
    side.innerHTML = brandHTML("index.html") +
      '<nav aria-label="Main navigation" style="display:grid;gap:2px">' + nav + "</nav>" +
      '<div class="sidebar-foot"><span class="badge badge-demo" style="justify-self:start"><span class="dot"></span>' + (account ? "Signed in" : "Demo session") + "</span>" +
      "<p>Signed in as <strong style=\"color:var(--text-2);font-weight:550\">" + esc(session.name) + "</strong>. " + (account ? "Reviews and progress sync to your account." : "Data stays in this browser.") + "</p>" +
      '<button class="btn btn-sm btn-ghost" type="button" id="exitDemo" style="justify-self:start;padding:0 8px">' + icon("logout") + (account ? "Sign out" : "Exit demo") + "</button></div>";
    app.prepend(side);
    const scrim = document.createElement("div"); scrim.className = "scrim"; app.appendChild(scrim);

    const top = $(".topbar");
    top.innerHTML = '<button class="btn btn-ghost btn-icon menu-btn" type="button" aria-label="Open navigation" aria-controls="sidebar" aria-expanded="false" id="menuBtn">' + icon("menu") + "</button>" +
      (opts.crumb ? '<a class="topbar-crumb" href="' + opts.crumb.href + '">' + esc(opts.crumb.label) + "</a><span class=\"faint\" aria-hidden=\"true\">/</span>" : "") +
      '<span class="topbar-title">' + esc(opts.title) + "</span>" +
      '<div class="topbar-actions">' + (account
        ? '<span class="badge badge-demo hide-sm" title="Reviews run on the CodeReviewX server (AST analysis)"><span class="dot"></span>Server analysis</span>'
        : '<span class="badge badge-demo hide-sm" title="All results in this app are demonstrations"><span class="dot"></span>Frontend demo</span>') +
      '<button class="btn btn-ghost btn-icon" type="button" id="themeBtn" aria-label="Toggle light and dark theme">' + icon(store.prefs().theme === "light" ? "moon" : "sun") + "</button>" +
      (opts.active !== "review" ? '<a class="btn btn-sm btn-accent hide-sm" href="review.html">' + icon("review") + "New review</a>" : "") + "</div>";

    const menuBtn = $("#menuBtn");
    function setNav(open) { app.classList.toggle("nav-open", open); menuBtn.setAttribute("aria-expanded", String(open)); if (open) $(".nav-link", side).focus(); }
    menuBtn.addEventListener("click", () => setNav(!app.classList.contains("nav-open")));
    scrim.addEventListener("click", () => setNav(false));
    document.addEventListener("keydown", (e) => { if (e.key === "Escape" && app.classList.contains("nav-open")) { setNav(false); menuBtn.focus(); } });
    $("#themeBtn").addEventListener("click", (e) => {
      const next = store.prefs().theme === "light" ? "dark" : "light"; store.setPref("theme", next); applyTheme(next);
      e.currentTarget.innerHTML = icon(next === "light" ? "moon" : "sun");
      document.dispatchEvent(new CustomEvent("crx:theme"));
    });
    $("#exitDemo").addEventListener("click", () => {
      if (account) {
        dialog({ title: "Sign out?", body: "<p>Your reviews and progress stay saved in your account.</p>",
          actions: [{ label: "Cancel" }, { label: "Sign out", primary: true, onClick: () => { window.CRX.api.logout().then(() => { location.href = "index.html"; }); } }] });
        return;
      }
      dialog({ title: "Exit demo session?", body: "<p>You'll return to the welcome page. Your demo reviews and practice progress stay saved in this browser until you clear them in Settings.</p>",
        actions: [{ label: "Cancel" }, { label: "Exit demo", primary: true, onClick: () => { store.endDemo(); location.href = "index.html"; } }] });
    });
    return true;
  }

  /* ---------------- Derived progress helpers ---------------- */
  function streak() {
    const days = new Set(store.activity().map((a) => new Date(a.at).toDateString()));
    let n = 0; const d = new Date();
    if (!days.has(d.toDateString())) d.setDate(d.getDate() - 1); // streak survives until today ends
    while (days.has(d.toDateString())) { n++; d.setDate(d.getDate() - 1); }
    return n;
  }
  function practiceStats() {
    const prog = store.progress(); const all = window.CRX_PROBLEMS || [];
    let solved = 0, attempted = 0; const byTopic = {};
    all.forEach((p) => { byTopic[p.topic] = byTopic[p.topic] || { total: 0, solved: 0, attempted: 0 }; byTopic[p.topic].total++; });
    Object.keys(prog).forEach((id) => {
      const p = all[+id - 1]; if (!p) return;
      if (prog[id].status === "solved") { solved++; byTopic[p.topic].solved++; } else { attempted++; byTopic[p.topic].attempted++; }
    });
    // Readiness: average topic coverage (solved / total) — every topic weighs the same,
    // so breadth across the sheet matters more than volume in one topic.
    const topics = Object.keys(byTopic);
    const readiness = topics.length ? Math.round((topics.reduce((s, t) => s + byTopic[t].solved / byTopic[t].total, 0) / topics.length) * 1000) / 10 : 0;
    return { solved: solved, attempted: attempted, total: all.length, byTopic: byTopic, readiness: readiness };
  }
  function nextProblem() {
    const prog = store.progress(); const all = window.CRX_PROBLEMS || [];
    // Continue the sheet's order: first problem not yet solved, preferring ones already attempted
    const att = all.find((p) => prog[p.id] && prog[p.id].status === "attempted");
    return att || all.find((p) => !prog[p.id]) || null;
  }

  window.CRX = { icon, brandHTML, LOGO, store, $, $$, esc, fmtDate, ago, toast, copyText, dialog, editor, highlight, codeBlock, shell, LANGS, langLabel, langOptions, reduceMotion, streak, practiceStats, nextProblem, applyTheme };
})();
