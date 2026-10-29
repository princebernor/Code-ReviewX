/* Code ReveiwX — welcome page: demo entry, honest sign-in form, hero video stage. */
(function () {
  "use strict";
  const { $, $$, esc, store, dialog, brandHTML } = window.CRX;

  $("#brandSlot").innerHTML = brandHTML("#top");
  $("#brandSlot2").innerHTML = brandHTML("#top");

  const params = new URLSearchParams(location.search);
  const safeNext = (n) => (/^[a-z]+\.html(\?[\w=&%-]*)?$/.test(n || "") ? n : null);
  const defaultNext = safeNext(params.get("next")) || "dashboard.html";

  function enterDemo(name, next) {
    store.startDemo(name);
    location.href = safeNext(next) || defaultNext;
  }
  $$("[data-demo]").forEach((b) => b.addEventListener("click", () => enterDemo("Demo user", b.dataset.next)));

  // Already in a demo session? Offer to continue rather than silently redirecting.
  const s = store.session();
  if (s) {
    const head = $(".auth-head");
    const who = s.mode === "account" ? "You're signed in as <strong style=\"color:var(--text)\">" + esc(s.name) + "</strong>."
      : 'A demo session for <strong style="color:var(--text)">' + esc(s.name) + "</strong> is already open in this browser.";
    head.insertAdjacentHTML("afterend", '<div class="auth-result"><p class="muted" style="font-size:14px">' + who + '</p><a class="btn btn-accent" href="' + defaultNext + '">Continue to dashboard</a></div>');
  }

  /* ---------- Account server (optional backend, js/api-client.js) ---------- */
  const api = window.CRX.api;
  const serverUp = api ? api.available() : Promise.resolve(false);
  serverUp.then((up) => {
    if (!up) return;
    // The default notice describes the frontend-only build; it isn't true when a server answers.
    $("#signin .notice span").innerHTML = "<strong>Account server connected.</strong> Sign in to save reviews and progress to your account. Demo mode still keeps everything in this browser.";
    const token = params.get("reset");
    if (token) resetDialog(token);
  });

  function goNext(next) { location.href = safeNext(next) || defaultNext; }

  /** After a real sign-in: offer to copy this browser's demo data into the account, then continue. */
  function afterSignIn() {
    const demo = api.pendingDemo();
    if (!demo) { goNext(); return; }
    const nr = demo.reviews.filter((r) => !r.sample).length, np = Object.keys(demo.progress).length;
    let leaving = false;
    const leave = () => { if (!leaving) { leaving = true; goNext(); } };
    const d = dialog({
      title: "Bring your demo data?",
      body: "<p>This browser has " + nr + " demo review" + (nr === 1 ? "" : "s") + " and " + np + " practice " + (np === 1 ? "entry" : "entries") + ". Import them into your account?</p><p>If you don't, they stay saved in this browser and come back when you sign out.</p>",
      actions: [{ label: "Not now", onClick: leave }, { label: "Import", primary: true, onClick: () => {
        leaving = true;
        api.importDemo().then((n) => { window.CRX.toast("Imported " + n + " review" + (n === 1 ? "" : "s")); setTimeout(goNext, 600); })
          .catch((e) => { window.CRX.toast("Import failed: " + e.message + " Your demo data is still in this browser.", "info"); setTimeout(goNext, 1500); });
      } }]
    });
    d.addEventListener("close", () => setTimeout(leave, 0));
  }

  /** A small form inside the shared dialog. onSubmit(values, showError) resolves when done. */
  function formDialog(title, intro, fields, submitLabel, onSubmit) {
    const id = "f" + Date.now().toString(36);
    const d = dialog({
      title: title,
      body: intro + '<form id="' + id + '" novalidate style="display:grid;gap:12px;margin-top:12px">' + fields.map((f) =>
        '<div class="field"><label class="label" for="' + id + f.name + '">' + esc(f.label) + '</label><input class="input" id="' + id + f.name + '" name="' + f.name + '" type="' + f.type + '" autocomplete="' + f.auto + '"' + (f.value ? ' value="' + esc(f.value) + '"' : "") + "></div>").join("") +
        '<p class="field-error" role="alert"></p><button class="btn btn-primary" type="submit">' + esc(submitLabel) + "</button></form>",
      actions: [{ label: "Cancel" }]
    });
    const form = $("#" + id, d), err = $(".field-error", form), btn = $("button[type=submit]", form);
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      const values = {}; fields.forEach((f) => (values[f.name] = form.elements[f.name].value));
      err.textContent = ""; btn.disabled = true;
      Promise.resolve(onSubmit(values, (m) => (err.textContent = m), d)).finally(() => (btn.disabled = false));
    });
    return d;
  }

  function messageFor(e) {
    if (e.network) return "Couldn't reach the account server. Check your connection and try again, or use demo mode.";
    if (e.status >= 500) return "The account server had a problem. Try again in a moment.";
    return e.message;
  }

  function resetDialog(token) {
    formDialog("Choose a new password", "<p>Enter a new password for your account.</p>",
      [{ name: "password", label: "New password", type: "password", auto: "new-password" }], "Set password",
      (v, fail, d) => api.reset(token, v.password).then(() => {
        d.close(); history.replaceState(null, "", location.pathname);
        window.CRX.toast("Password changed. Sign in with your new password.");
        pw.focus();
      }, (e) => fail(messageFor(e))));
  }

  /* ---------- Nav background on scroll ---------- */
  const nav = $(".lp-nav");
  const onScroll = () => nav.classList.toggle("scrolled", window.scrollY > 8);
  window.addEventListener("scroll", onScroll, { passive: true }); onScroll();

  /* ---------- Sign-in form (format validation only) ---------- */
  const email = $("#email"), pw = $("#password");
  try { const r = localStorage.getItem("crx.rememberEmail"); if (r) { email.value = JSON.parse(r); $("#remember").checked = true; } } catch (e) {}

  function setErr(input, el, msg) { el.textContent = msg || ""; input.setAttribute("aria-invalid", msg ? "true" : "false"); }
  function validate() {
    let ok = true;
    const ev = email.value.trim();
    if (!ev) { setErr(email, $("#emailErr"), "Enter your email address."); ok = false; }
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(ev)) { setErr(email, $("#emailErr"), "That doesn't look like an email address. Check for a missing @ or domain."); ok = false; }
    else setErr(email, $("#emailErr"));
    if (!pw.value) { setErr(pw, $("#pwErr"), "Enter your password."); ok = false; }
    else if (pw.value.length < 8) { setErr(pw, $("#pwErr"), "Passwords are at least 8 characters."); ok = false; }
    else setErr(pw, $("#pwErr"));
    return ok;
  }
  email.addEventListener("blur", () => { if (email.value) validate(); });
  $("#loginForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    if (!validate()) { (email.getAttribute("aria-invalid") === "true" ? email : pw).focus(); return; }
    try {
      if ($("#remember").checked) localStorage.setItem("crx.rememberEmail", JSON.stringify(email.value.trim()));
      else localStorage.removeItem("crx.rememberEmail");
    } catch (err) {}
    if (await serverUp) {
      // Real sign-in. A failure never opens a session, demo or otherwise.
      const btn = $(".auth-submit"); btn.disabled = true;
      try {
        await api.login(email.value.trim(), pw.value);
        pw.value = "";
        afterSignIn();
      } catch (err) {
        setErr(pw, $("#pwErr"), err.status === 401 ? "Email or password is incorrect." : messageFor(err));
        pw.focus();
      } finally { btn.disabled = false; }
      return;
    }
    const name = email.value.trim().split("@")[0].replace(/[._-]+/g, " ").replace(/\b\w/g, (c) => c.toUpperCase()).slice(0, 32) || "Demo user";
    dialog({
      title: "Continue in demo mode",
      body: "<p>There's no account server in this frontend build, so your credentials weren't checked against anything, and the password was discarded.</p><p>You can continue with a local demo session named <strong style=\"color:var(--text)\">" + esc(name) + "</strong>. Everything you do stays in this browser.</p>",
      actions: [{ label: "Cancel" }, { label: "Continue as " + name, primary: true, onClick: () => enterDemo(name) }]
    });
    pw.value = "";
  });

  $("#pwToggle").addEventListener("click", (e) => {
    const show = pw.type === "password"; pw.type = show ? "text" : "password";
    e.currentTarget.setAttribute("aria-pressed", String(show));
    e.currentTarget.setAttribute("aria-label", show ? "Hide password" : "Show password");
  });
  $("#forgotBtn").addEventListener("click", async () => {
    if (await serverUp) {
      formDialog("Reset your password", "<p>Enter your account email. If an account exists, we'll send a reset link.</p>",
        [{ name: "email", label: "Email", type: "email", auto: "email", value: email.value.trim() }], "Send reset link",
        (v, fail, d) => api.forgot(v.email.trim()).then((r) => {
          d.close();
          if (r && r.devResetToken) {
            // Development servers only (crx.auth.expose-reset-token): no email service, so hand the token over directly.
            dialog({ title: "Development reset link", body: "<p>This server runs in development mode and has no email service, so the reset link is shown here instead of being emailed.</p>",
              actions: [{ label: "Close" }, { label: "Reset now", primary: true, onClick: () => resetDialog(r.devResetToken) }] });
          } else {
            dialog({ title: "Check your email", body: "<p>" + esc(r && r.message ? r.message : "If an account exists for that email, a reset link has been sent.") + "</p>" });
          }
        }, (err) => fail(messageFor(err))));
      return;
    }
    dialog({
      title: "Password recovery isn't available",
      body: "<p>Password recovery needs an email service and an account server, and neither is part of this frontend build. No email will be sent.</p><p>Use demo mode to explore every feature.</p>",
      actions: [{ label: "Close" }, { label: "Explore demo", primary: true, onClick: () => enterDemo("Demo user") }]
    });
  });
  $("#createBtn").addEventListener("click", async () => {
    if (await serverUp) {
      formDialog("Create an account", "<p>Your reviews and practice progress will be saved to this account. Passwords need at least 8 characters and two of: lowercase, uppercase, digits, symbols.</p>",
        [{ name: "name", label: "Name (optional)", type: "text", auto: "name" }, { name: "email", label: "Email", type: "email", auto: "email", value: email.value.trim() },
         { name: "password", label: "Password", type: "password", auto: "new-password" }], "Create account",
        (v, fail, d) => api.register(v.name.trim(), v.email.trim(), v.password).then(() => { d.close(); afterSignIn(); }, (err) => {
          const fields = err.code === "validation" ? "Check the email address and password." : null;
          fail(fields || messageFor(err));
        }));
      return;
    }
    dialog({
      title: "Account creation isn't available",
      body: "<p>Creating an account needs a backend that stores users securely, and this build doesn't have one. It would be misleading to pretend otherwise.</p><p>Demo mode gives you the full interface, and your progress is saved locally in this browser.</p>",
      actions: [{ label: "Close" }, { label: "Explore demo", primary: true, onClick: () => enterDemo("Demo user") }]
    });
  });

  /* ---------- Preview tabs (roving tabindex) ---------- */
  const tabs = $$('.preview-tabs [role="tab"]');
  function selectTab(t) {
    tabs.forEach((x) => { const on = x === t; x.setAttribute("aria-selected", String(on)); x.tabIndex = on ? 0 : -1; $("#" + x.getAttribute("aria-controls")).hidden = !on; });
  }
  tabs.forEach((t, i) => {
    t.addEventListener("click", () => selectTab(t));
    t.addEventListener("keydown", (e) => {
      if (e.key === "ArrowRight" || e.key === "ArrowLeft") { const n = tabs[(i + (e.key === "ArrowRight" ? 1 : tabs.length - 1)) % tabs.length]; selectTab(n); n.focus(); }
    });
  });
  if (window.CRX_TOPICS) {
    $("#pfTopics").innerHTML = window.CRX_TOPICS.map((t) => {
      const n = t.patterns.reduce((s, p) => s + p.count, 0);
      return '<div class="pf-topic"><strong>' + esc(t.label) + '</strong><span class="num">' + n + " problems · " + t.patterns.length + " pattern" + (t.patterns.length > 1 ? "s" : "") + "</span></div>";
    }).join("");
  }

  /* ---------- Hero background: video, with a canvas fallback ---------- */
  const video = $("#heroVideo"), canvas = $("#heroCanvas"), toggle = $("#videoToggle");
  const reduce = window.CRX.reduceMotion();
  let fallbackRunning = false, rafId = 0, paused = false;

  function startFallback() {
    if (fallbackRunning) return; fallbackRunning = true; video.style.display = "none";
    const ctx = canvas.getContext("2d"); const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const glyphs = "{}()[];=<>+-*/&|!?:.,0123456789abcdefghijklmnopqrstuvwxyz".split("");
    let cols = [], w = 0, h = 0;
    function size() {
      w = canvas.clientWidth; h = canvas.clientHeight; canvas.width = w * dpr; canvas.height = h * dpr; ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      cols = Array.from({ length: Math.ceil(w / 18) }, () => ({ y: Math.random() * h, v: 0.3 + Math.random() * 0.7 }));
    }
    size(); window.addEventListener("resize", size);
    function frame() {
      ctx.fillStyle = "rgba(8,9,10,0.22)"; ctx.fillRect(0, 0, w, h);
      ctx.font = "13px JetBrains Mono, monospace";
      cols.forEach((c, i) => {
        ctx.fillStyle = i % 9 === 0 ? "rgba(141,156,255,0.55)" : "rgba(180,183,190,0.18)";
        ctx.fillText(glyphs[(Math.random() * glyphs.length) | 0], i * 18, c.y);
        c.y += c.v * 4; if (c.y > h + 20) c.y = -20;
      });
      if (!paused) rafId = requestAnimationFrame(frame);
    }
    if (reduce) { frame(); paused = true; } else frame();
  }
  video.addEventListener("error", startFallback, true);
  const src = video.querySelector("source"); if (src) src.addEventListener("error", startFallback);
  if (reduce) { video.removeAttribute("autoplay"); video.pause(); setPaused(true); }
  else {
    const p = video.play && video.play(); if (p && p.catch) p.catch(() => { /* autoplay blocked: poster stays visible */ });
  }
  function setPaused(v) {
    paused = v; toggle.setAttribute("aria-pressed", String(v));
    toggle.querySelector("span").textContent = v ? "Play background" : "Pause background";
    toggle.querySelector("svg").innerHTML = v ? '<path d="M7 5v14l11-7Z"/>' : '<rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/>';
  }
  toggle.addEventListener("click", () => {
    const next = !paused; setPaused(next);
    if (fallbackRunning) { if (!next) { cancelAnimationFrame(rafId); startFallbackLoop(); } }
    else if (next) video.pause(); else video.play().catch(() => {});
  });
  function startFallbackLoop() { fallbackRunning = false; startFallback(); }
})();
