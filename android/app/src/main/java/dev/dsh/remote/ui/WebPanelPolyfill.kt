package dev.dsh.remote.ui

/**
 * Polyfills the Web panels need because they are served over plain HTTP.
 *
 * `crypto.randomUUID()` only exists in a **secure context** (https:// or
 * localhost). The panels load the host UI from `http://<host>:8080`, which is not
 * one, so any client plugin that calls it during init throws:
 *
 *     pageerror: crypto.randomUUID is not a function
 *
 * That single error is what kept 终端 and 侧边对话(beta) from rendering — with the
 * polyfill in place the terminal attaches its PTY and the side conversation
 * mounts normally (verified against the real host page).
 *
 * `getRandomValues` is *not* gated the same way, so the fallback builds a v4 UUID
 * from it. The body is defensive: if a browser already provides the API, the
 * original is left untouched.
 */
const val PANEL_POLYFILL_JS: String = """
(function () {
  try {
    if (typeof crypto !== 'object' || crypto === null) return;
    if (typeof crypto.randomUUID === 'function') return;
    var rnd = (typeof crypto.getRandomValues === 'function')
      ? function (arr) { return crypto.getRandomValues(arr); }
      : function (arr) { for (var i = 0; i < arr.length; i++) arr[i] = Math.floor(Math.random() * 256); return arr; };
    crypto.randomUUID = function () {
      var b = new Uint8Array(16);
      rnd(b);
      b[6] = (b[6] & 0x0f) | 0x40;   // version 4
      b[8] = (b[8] & 0x3f) | 0x80;   // variant 10xx
      var h = [];
      for (var i = 0; i < 16; i++) h.push((b[i] + 0x100).toString(16).slice(1));
      return h.slice(0, 4).join('') + '-' + h.slice(4, 6).join('') + '-' + h.slice(6, 8).join('')
        + '-' + h.slice(8, 10).join('') + '-' + h.slice(10, 16).join('');
    };
    window.__dshUuidPolyfilled = true;
  } catch (e) { /* never break the page over a polyfill */ }
})();
"""
