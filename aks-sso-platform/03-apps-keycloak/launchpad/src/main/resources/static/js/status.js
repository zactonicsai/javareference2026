/* Live status badges: polls /status and updates every element with data-status-for="<service id>".
   No framework, no external resources (CSP: script-src 'self'). */
(function () {
  'use strict';
  var script = document.currentScript;
  var interval = Math.max(5000, parseInt((script && script.dataset.interval) || '15000', 10));

  function setBadge(el, s) {
    el.classList.remove('badge-up', 'badge-down', 'badge-unknown');
    el.classList.add('badge-' + s.state.toLowerCase());
    var label = el.querySelector('.label');
    if (label) label.textContent = s.state;
    el.title = 'last check ' + (s.checkedAt ? new Date(s.checkedAt).toLocaleTimeString() : 'never') + (s.message ? ' — ' + s.message : '');
  }

  function render(data) {
    (data.services || []).forEach(function (s) {
      document.querySelectorAll('[data-status-for="' + s.id + '"]').forEach(function (el) { setBadge(el, s); });
      document.querySelectorAll('[data-latency-for="' + s.id + '"]').forEach(function (el) { el.textContent = s.latencyMs + ' ms'; });
    });
    var overall = document.getElementById('overall');
    var text = document.getElementById('overall-text');
    if (overall && text) {
      var down = data.down || 0;
      overall.className = 'inline-flex items-center gap-2 rounded-full border px-3 py-1.5 font-medium ' +
        (down === 0 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-amber-200 bg-amber-50 text-amber-700');
      var dot = overall.querySelector('.dot');
      if (dot) dot.className = 'dot ' + (down === 0 ? 'bg-emerald-500' : 'bg-amber-500');
      text.textContent = down === 0 ? 'All services up' : down + ' service(s) unavailable';
    }
    var updated = document.getElementById('updated');
    if (updated) updated.textContent = 'checked ' + new Date().toLocaleTimeString();
  }

  function poll() {
    fetch('/status', { credentials: 'same-origin', cache: 'no-store' })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(render)
      .catch(function (e) {
        var updated = document.getElementById('updated');
        if (updated) updated.textContent = 'status unavailable (' + e.message + ')';
      });
  }

  setTimeout(poll, 2000);
  setInterval(poll, interval);
})();
