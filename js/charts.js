/* Code ReveiwX — score gauge and trend chart (vanilla SVG).
   Motion: gauge sweep + count-up only when a new result appears (state indication);
   trend line draws in once. Both are skipped under prefers-reduced-motion. */
(function () {
  "use strict";
  const C = 2 * Math.PI * 72; // gauge circumference (r = 72)

  function grade(score) {
    if (score >= 90) return "Excellent: few or no rule matches";
    if (score >= 75) return "Good: a few issues worth fixing";
    if (score >= 55) return "Fair: several issues to address";
    return "Needs work: start with the critical findings";
  }

  function gaugeHTML(id) {
    let ticks = "";
    for (let i = 0; i < 40; i++) { const a = (i / 40) * 2 * Math.PI; const x1 = 80 + Math.cos(a) * 66, y1 = 80 + Math.sin(a) * 66, x2 = 80 + Math.cos(a) * 78, y2 = 80 + Math.sin(a) * 78; ticks += '<line x1="' + x1.toFixed(1) + '" y1="' + y1.toFixed(1) + '" x2="' + x2.toFixed(1) + '" y2="' + y2.toFixed(1) + '"/>'; }
    return '<div class="gauge" id="' + id + '"><svg viewBox="0 0 160 160" aria-hidden="true"><circle class="g-track" cx="80" cy="80" r="72"/><circle class="g-fill" cx="80" cy="80" r="72" style="stroke-dasharray:' + C.toFixed(2) + ';stroke-dashoffset:' + C.toFixed(2) + '"/><g class="g-ticks">' + ticks + '</g></svg><div class="gauge-center"><span class="gauge-num">0</span><span class="gauge-den">/ 100</span></div></div>';
  }

  function setGauge(el, score, animate) {
    const fill = el.querySelector(".g-fill"), num = el.querySelector(".gauge-num");
    const color = score >= 75 ? "var(--accent)" : score >= 55 ? "var(--sev-medium)" : "var(--sev-high)";
    fill.style.stroke = color;
    fill.style.opacity = score > 0 ? 1 : 0;
    const target = C * (1 - score / 100);
    const reduce = window.CRX.reduceMotion();
    if (!animate || reduce) { fill.style.transition = "none"; fill.style.strokeDashoffset = target; num.textContent = score; return; }
    fill.getBoundingClientRect(); // commit the start state before transitioning
    requestAnimationFrame(() => { fill.style.strokeDashoffset = target; });
    const t0 = performance.now(), dur = 900;
    const ease = (t) => 1 - Math.pow(1 - t, 4); // matches the strong ease-out of the stroke
    (function tick(now) {
      const p = Math.min(1, (now - t0) / dur); num.textContent = Math.round(score * ease(p));
      if (p < 1) requestAnimationFrame(tick);
    })(t0);
  }

  /* Trend line: points [{x: label, y: 0..100, id}] */
  function trend(container, points, opts) {
    opts = opts || {};
    const W = Math.max(300, Math.round(container.clientWidth || 640)), H = 220, pad = { l: 36, r: 14, t: 14, b: 26 };
    if (!points.length) { container.innerHTML = ""; return; }
    const iw = W - pad.l - pad.r, ih = H - pad.t - pad.b;
    const xs = (i) => pad.l + (points.length === 1 ? iw / 2 : (i / (points.length - 1)) * iw);
    const ys = (v) => pad.t + ih - (v / 100) * ih;
    let grid = "";
    [0, 25, 50, 75, 100].forEach((v) => { grid += '<line x1="' + pad.l + '" x2="' + (W - pad.r) + '" y1="' + ys(v) + '" y2="' + ys(v) + '" stroke="var(--line)"/><text x="' + (pad.l - 8) + '" y="' + (ys(v) + 4) + '" text-anchor="end" font-size="10" fill="var(--text-3)" font-family="var(--font-mono)">' + v + "</text>"; });
    const d = points.map((p, i) => (i ? "L" : "M") + xs(i).toFixed(1) + " " + ys(p.y).toFixed(1)).join(" ");
    const area = d + " L" + xs(points.length - 1).toFixed(1) + " " + ys(0) + " L" + xs(0).toFixed(1) + " " + ys(0) + " Z";
    const dots = points.map((p, i) => '<circle cx="' + xs(i).toFixed(1) + '" cy="' + ys(p.y).toFixed(1) + '" r="3.5" fill="var(--surface)" stroke="var(--accent)" stroke-width="2"/>').join("");
    const labels = points.map((p, i) => (points.length <= 8 || i % Math.ceil(points.length / 8) === 0 || i === points.length - 1) ? '<text x="' + xs(i).toFixed(1) + '" y="' + (H - 6) + '" text-anchor="middle" font-size="10" fill="var(--text-3)" font-family="var(--font-mono)">' + window.CRX.esc(p.x) + "</text>" : "").join("");
    container.innerHTML = '<div class="trend-wrap" style="position:relative"><svg viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="' + window.CRX.esc(opts.label || "Score trend") + '" style="width:100%;height:auto;overflow:visible">' +
      '<defs><linearGradient id="tg" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="var(--accent)" stop-opacity=".18"/><stop offset="1" stop-color="var(--accent)" stop-opacity="0"/></linearGradient></defs>' +
      grid + '<path d="' + area + '" fill="url(#tg)"/><path class="trend-line" d="' + d + '" fill="none" stroke="var(--accent)" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>' + dots + labels +
      '<line class="trend-x" y1="' + pad.t + '" y2="' + (pad.t + ih) + '" stroke="var(--line-3)" stroke-dasharray="3 3" visibility="hidden"/></svg>' +
      '<div class="trend-tip" role="status" style="position:absolute;top:0;pointer-events:none;padding:6px 9px;border-radius:8px;background:var(--surface-3);box-shadow:var(--shadow-pop);font-size:12px;font-family:var(--font-mono);font-variant-numeric:tabular-nums;white-space:nowrap;opacity:0;transition:opacity 120ms ease"></div></div>';
    const svg = container.querySelector("svg"), path = container.querySelector(".trend-line"), tip = container.querySelector(".trend-tip"), xl = container.querySelector(".trend-x");
    if (!window.CRX.reduceMotion() && path.getTotalLength) {
      const L = path.getTotalLength(); path.style.strokeDasharray = L; path.style.strokeDashoffset = L;
      path.getBoundingClientRect(); path.style.transition = "stroke-dashoffset 800ms cubic-bezier(0.23, 1, 0.32, 1)"; path.style.strokeDashoffset = 0;
    }
    // crosshair inspection
    svg.addEventListener("pointermove", (e) => {
      const r = svg.getBoundingClientRect(); const x = ((e.clientX - r.left) / r.width) * W;
      let best = 0; points.forEach((p, i) => { if (Math.abs(xs(i) - x) < Math.abs(xs(best) - x)) best = i; });
      const p = points[best]; xl.setAttribute("x1", xs(best)); xl.setAttribute("x2", xs(best)); xl.setAttribute("visibility", "visible");
      tip.innerHTML = window.CRX.esc(p.tip || p.x) + " · <b>" + p.y + "</b>/100"; tip.style.opacity = 1;
      const px = (xs(best) / W) * r.width; tip.style.left = Math.min(Math.max(px - tip.offsetWidth / 2, 0), r.width - tip.offsetWidth) + "px"; tip.style.top = ((ys(p.y) / H) * r.height - 40) + "px";
    });
    svg.addEventListener("pointerleave", () => { tip.style.opacity = 0; xl.setAttribute("visibility", "hidden"); });
  }

  Object.assign(window.CRX, { gaugeHTML, setGauge, grade, trend });
})();
