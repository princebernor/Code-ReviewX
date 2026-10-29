/* Code ReveiwX — client for the optional Java backend.
 *
 * Loaded after main.js on every page. Demo sessions never touch the network: everything here only acts when
 * store.session().mode === "account". Account sessions keep using the same localStorage keys as a cache, so
 * the existing pages render unchanged; this file keeps that cache in sync with the server.
 *
 * Authentication uses HttpOnly cookies set by the server; JavaScript never sees the tokens. Every state-changing
 * request sends the XSRF-TOKEN cookie back in the X-XSRF-TOKEN header (read fresh each time, because the server
 * rotates it).
 */
(function () {
  "use strict";
  const X = window.CRX;
  if (!X || !X.store) return;
  const store = X.store;
  const NS = "crx.";

  class ApiError extends Error {
    constructor(status, code, message) { super(message); this.status = status; this.code = code; this.network = status === 0; }
  }

  function cookie(name) {
    const m = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]*)"));
    return m ? decodeURIComponent(m[1]) : null;
  }
  function getLocal(k, d) { try { const v = localStorage.getItem(NS + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } }
  function setLocal(k, v) { try { localStorage.setItem(NS + k, JSON.stringify(v)); } catch (e) {} }
  function delLocal(k) { try { localStorage.removeItem(NS + k); } catch (e) {} }

  const isAccount = () => { const s = store.session(); return !!(s && s.mode === "account"); };

  /* ---------------- transport ---------------- */
  async function send(method, path, body, opts) {
    opts = opts || {};
    if (method !== "GET" && !cookie("XSRF-TOKEN")) {
      try { await fetch("/api/auth/csrf", { credentials: "same-origin" }); } catch (e) { throw new ApiError(0, "network", "Couldn't reach the server."); }
    }
    const headers = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET") headers["X-XSRF-TOKEN"] = cookie("XSRF-TOKEN") || "";
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), opts.timeout || 20000);
    let res;
    try {
      res = await fetch("/api" + path, { method, headers, credentials: "same-origin", body: body === undefined ? undefined : JSON.stringify(body), signal: ctrl.signal, keepalive: !!opts.keepalive });
    } catch (e) {
      throw new ApiError(0, "network", "Couldn't reach the server. Check your connection and try again.");
    } finally { clearTimeout(timer); }
    let data = null;
    const text = await res.text();
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
    if (res.ok) return data;
    const code = data && data.error ? data.error : "http_" + res.status;
    if (res.status === 403 && code === "csrf_failed" && !opts.csrfRetried) {
      try { await fetch("/api/auth/csrf", { credentials: "same-origin" }); } catch (e) {}
      return send(method, path, body, Object.assign({}, opts, { csrfRetried: true }));
    }
    if (res.status === 401 && !opts.noRefresh && path.indexOf("/auth/") !== 0) {
      if (await refresh()) return send(method, path, body, Object.assign({}, opts, { noRefresh: true }));
      if (isAccount()) sessionEnded();
    }
    throw new ApiError(res.status, code, (data && data.message) || "The server returned an error (" + res.status + ").");
  }

  let refreshing = null;
  function refresh() {
    if (!refreshing) {
      refreshing = send("POST", "/auth/refresh", undefined, { noRefresh: true })
        .then((d) => { if (d && d.user && isAccount()) startAccount(d.user, true); return true; }, () => false)
        .finally(() => setTimeout(() => (refreshing = null), 0));
    }
    return refreshing;
  }

  /* ---------------- availability ---------------- */
  let probe = null;
  function available() {
    if (!probe) {
      probe = fetch("/api/health", { credentials: "same-origin", signal: AbortSignal.timeout ? AbortSignal.timeout(2500) : undefined })
        .then((r) => r.ok ? r.json() : null).then((d) => !!(d && d.status === "UP"), () => false);
    }
    return probe;
  }

  /* ---------------- session ---------------- */
  function startAccount(user, keepSince) {
    const prev = store.session();
    store.startAccount({ name: user.name, email: user.email, userId: user.id, since: keepSince && prev ? prev.since : Date.now() });
  }

  /** Account cache keys are cleared on sign-out; a demo backup kept at sign-in comes back. */
  function endAccountLocally() {
    ["reviews", "progress", "activity"].forEach(delLocal);
    const backup = getLocal("demoBackup", null);
    if (backup) { ["reviews", "progress", "activity"].forEach((k) => { if (backup[k] != null) setLocal(k, backup[k]); }); delLocal("demoBackup"); }
    delLocal("session");
  }

  let ended = false;
  function sessionEnded() {
    if (ended) return; ended = true;
    endAccountLocally();
    try { X.toast("Your session has ended. Sign in again.", "info"); } catch (e) {}
    const here = location.pathname.split("/").pop() + location.search;
    setTimeout(() => location.replace("index.html?next=" + encodeURIComponent(here)), 900);
  }

  async function login(email, password) {
    const d = await send("POST", "/auth/login", { email, password }, { noRefresh: true });
    await afterSignIn(d.user);
    return d.user;
  }
  async function register(name, email, password) {
    const d = await send("POST", "/auth/register", { name: name || null, email, password }, { noRefresh: true });
    await afterSignIn(d.user);
    return d.user;
  }
  const forgot = (email) => send("POST", "/auth/forgot", { email }, { noRefresh: true });
  const reset = (token, password) => send("POST", "/auth/reset", { token, password }, { noRefresh: true });
  async function logout() {
    flushProgress();
    try { await send("POST", "/auth/logout", undefined, { noRefresh: true }); } catch (e) { /* cookies may already be gone */ }
    endAccountLocally();
  }

  /** Local demo data present at sign-in: the caller asks the user whether to import it (see auth.js). */
  function localDemoData() {
    const reviews = store.reviews(), progress = store.progress(), activity = store.activity();
    const has = reviews.length > 0 || Object.keys(progress).length > 0;
    return has ? { reviews, progress, activity } : null;
  }

  let pendingDemo = null;
  async function afterSignIn(user) {
    pendingDemo = localDemoData();
    if (pendingDemo) setLocal("demoBackup", pendingDemo); // nothing is lost whatever the user decides next
    ["reviews", "progress", "activity"].forEach(delLocal);
    startAccount(user);
    await pull();
  }

  /** Copies the demo data saved at sign-in into the account, then refreshes the cache. */
  async function importDemo() {
    const d = getLocal("demoBackup", null);
    if (!d) return 0;
    let n = 0;
    for (const r of (d.reviews || []).slice().reverse()) {
      try { await send("POST", "/reviews", r); n++; } catch (e) { if (e.network) throw e; }
    }
    const server = await send("GET", "/progress");
    await send("PUT", "/progress", mergeProgress(server, d));
    delLocal("demoBackup");
    await pull();
    return n;
  }

  /* ---------------- reviews ---------------- */
  /** Shows the built-in sample's hand-written rewrite, exactly as the browser engine does for its samples. */
  function adapt(r) {
    const R = X.Review;
    if (!R || !r || !r.code) return r;
    const s = R.SAMPLES.find((x) => x.lang === r.lang && x.code.trim() === String(r.code).trim());
    if (!s) return r;
    r.sampleId = s.id;
    if (s.fix) (r.findings || []).forEach((f) => {
      if (f.ruleId !== "time.nested-loop") return;
      Object.assign(f, { suggested: s.fix.code, kind: "rewrite", changes: s.fix.changes, benefits: s.fix.benefits, after: s.fix.after, tradeoffs: s.fix.tradeoffs, assumptions: s.fix.assumptions });
    });
    return r;
  }

  async function review(code, lang, filename) {
    const r = await send("POST", "/review", { code, lang, filename: filename || null, save: true }, { timeout: 30000 });
    if (!r.at) r.at = Date.now();
    if (!r.id) r.id = "r" + Date.now().toString(36);
    if (r.code == null) r.code = code;
    if (r.file === undefined) r.file = filename || null;
    delete r.saved;
    return adapt(r);
  }

  async function pull() {
    const [list, prog] = await Promise.all([send("GET", "/reviews?size=60"), send("GET", "/progress")]);
    origSetReviews((list.items || []).map(adapt));
    setLocal("progress", prog.progress || {});
    setLocal("activity", prog.activity || []);
    document.dispatchEvent(new CustomEvent("crx:synced"));
  }

  /* ---------------- progress ---------------- */
  function mergeProgress(a, b) {
    const p = Object.assign({}, a.progress || {});
    Object.keys(b.progress || {}).forEach((id) => {
      const x = p[id], y = b.progress[id];
      if (!x || (y.status === "solved" && x.status !== "solved") || (y.status === x.status && y.at > x.at)) p[id] = y;
    });
    const seen = new Set(); const act = [];
    (a.activity || []).concat(b.activity || []).sort((u, v) => v.at - u.at).forEach((e) => {
      const k = e.type + "|" + e.ref + "|" + e.at; if (!seen.has(k)) { seen.add(k); act.push(e); }
    });
    return { progress: p, activity: act.slice(0, 400) };
  }

  let pushTimer = 0;
  function schedulePush() {
    if (!isAccount()) return;
    clearTimeout(pushTimer);
    pushTimer = setTimeout(() => { pushTimer = 0; pushProgress(false); }, 400);
  }
  function pushProgress(keepalive) {
    return send("PUT", "/progress", { progress: store.progress(), activity: store.activity() }, { keepalive })
      .catch((e) => { if (!keepalive) X.toast(e.network ? "Progress saved locally; it will sync when the server is reachable." : "Couldn't sync progress: " + e.message, "info"); });
  }
  function flushProgress() { if (pushTimer) { clearTimeout(pushTimer); pushTimer = 0; pushProgress(true); } }
  window.addEventListener("pagehide", flushProgress);

  /* ---------------- store wrappers (account sessions only) ---------------- */
  const origSetReviews = store.setReviews, origSetStatus = store.setStatus, origClearStatus = store.clearStatus;
  const origResetAll = store.resetAll, origAddReview = store.addReview;

  store.setReviews = function (list) {
    if (!isAccount()) return origSetReviews(list);
    const before = store.reviews(); const beforeIds = new Set(before.map((r) => r.id)); const afterIds = new Set(list.map((r) => r.id));
    origSetReviews(list);
    before.filter((r) => !afterIds.has(r.id)).forEach((r) => send("DELETE", "/reviews/" + encodeURIComponent(r.id)).catch((e) => {
      if (e.status !== 404) X.toast("Couldn't delete the review on the server: " + e.message, "info");
    }));
    const added = list.filter((r) => !beforeIds.has(r.id));
    if (added.length) {
      Promise.all(added.map((r) => send("POST", "/reviews", r).then((saved) => ({ from: r.id, to: adapt(saved) }))))
        .then((pairs) => {
          const map = {}; pairs.forEach((p) => (map[p.from] = p.to));
          origSetReviews(store.reviews().map((r) => map[r.id] || r));
        })
        .catch((e) => X.toast("Couldn't save to your account: " + e.message, "info"));
    }
  };
  store.addReview = function (r) { origAddReview(r); schedulePush(); };
  store.setStatus = function (id, status) { origSetStatus(id, status); schedulePush(); };
  store.clearStatus = function (id) { origClearStatus(id); schedulePush(); };
  store.resetAll = function () {
    origResetAll();
    if (!isAccount()) return;
    send("DELETE", "/reviews").catch((e) => X.toast("Couldn't clear server history: " + e.message, "info"));
    send("PUT", "/progress", { progress: {}, activity: [] }).catch(() => {});
  };

  /* ---------------- page load: confirm the account session and refresh the cache ---------------- */
  if (isAccount() && !/(^|\/)(index\.html)?$/.test(location.pathname)) {
    send("GET", "/auth/me").then(() => pull()).catch((e) => {
      if (e.network) X.toast("Can't reach the server right now. Showing the copy saved in this browser.", "info");
    });
  }

  /** Practice Run/Submit: {lang, code, mode: "tests"|"stdin", cases?, stdin?}. Code goes to the server's external judge. */
  const run = (payload) => send("POST", "/run", payload, { timeout: 70000 });

  X.api = { ApiError, available, isAccount, login, register, forgot, reset, logout, review, run, pull, importDemo, adapt, pendingDemo: () => pendingDemo };
})();
