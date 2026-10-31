// End-to-end probe of the deployed stack, from the App's point of view.
//
//   node diag-app.mjs http://<host>:8080
//
// Walks exactly what the Android client does on connect: reachability probe,
// the two lists connect() awaits, the settings oracle, then both event
// streams. Use this first when the phone says "连接失败": if everything here
// is green, the host and the bridge are fine and the problem is the path
// between the phone and the host (Tailscale / DNS / firewall).
const base = (process.argv[2] || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const UA = 'okhttp/4.12.0'; // the Caddy route that reaches the bridge
const t0 = Date.now();
const log = (...a) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1)}s]`, ...a);

async function rpc(method, args = {}) {
  const r = await fetch(`${base}/api/${method}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': UA },
    body: JSON.stringify({ args }),
    signal: AbortSignal.timeout(15000),
  });
  const text = await r.text();
  let j = null;
  try { j = JSON.parse(text); } catch {}
  return {
    code: r.status,
    ok: j?.result?.ok ?? null,
    len: text.length,
    value: j?.result?.value,
    err: j?.result?.error || (j ? undefined : text.slice(0, 200)),
  };
}

(async () => {
  log('base =', base);

  // 1. reachability probe (what the App's probeRequest does: GET / < 5xx)
  try {
    const r = await fetch(`${base}/`, { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(8000) });
    log('probe GET / ->', r.status, r.status >= 100 && r.status < 500 ? 'REACHABLE' : 'UNREACHABLE');
    await r.text();
  } catch (e) { log('probe GET / FAILED:', e.message); }

  // 2. the two lists connect() waits for
  let firstSessionId = null;
  for (const m of ['session.list', 'workspace.list']) {
    try {
      const r = await rpc(m);
      const n = r.value ? (r.value.items?.length ?? Object.keys(r.value).length) : null;
      log(m, '->', r.code, 'ok=' + r.ok, 'items=' + n, r.err ? 'err=' + JSON.stringify(r.err).slice(0, 200) : '');
      if (m === 'session.list' && Array.isArray(r.value?.items)) {
        firstSessionId = r.value.items[0]?.sessionId ?? null;
        for (const s of r.value.items.slice(0, 6)) {
          log('   session', String(s.sessionId).slice(0, 20), 'running=' + s.running, 'cwd=' + JSON.stringify(s.cwd));
        }
      }
    } catch (e) { log(m, 'FAILED:', e.message); }
  }

  // 3. endpoints the panels need
  for (const [m, args] of [['settings.describe', {}], ['session.models', firstSessionId ? { sessionId: firstSessionId } : {}]]) {
    try {
      const r = await rpc(m, args);
      log(m, '->', r.code, 'ok=' + r.ok, 'len=' + r.len, r.err ? 'err=' + JSON.stringify(r.err).slice(0, 160) : '');
    } catch (e) { log(m, 'FAILED:', e.message); }
  }

  // 4. both event streams (a turn has to be running to see frames)
  const wsBase = base.replace(/^http/, 'ws');
  for (const path of ['/api/events.mux', '/api/events.host']) {
    await new Promise((resolve) => {
      let frames = 0;
      const started = Date.now();
      let ws;
      const done = (why) => {
        try { ws?.close(); } catch {}
        log(path, '->', why, 'frames=' + frames, 'in ' + (Date.now() - started) + 'ms');
        resolve();
      };
      try {
        ws = new WebSocket(wsBase + path, { headers: { 'User-Agent': UA } });
      } catch (e) { log(path, 'ctor FAILED:', e.message); resolve(); return; }
      ws.addEventListener('message', (ev) => {
        frames += 1;
        if (frames === 1) log('   first frame:', String(ev.data).slice(0, 160));
      });
      ws.addEventListener('open', () => log(path, 'OPEN'));
      ws.addEventListener('error', () => done('ERROR'));
      ws.addEventListener('close', (ev) => done('CLOSE ' + ev.code + ' ' + ev.reason));
      setTimeout(() => done(frames ? 'STREAMING' : 'NO FRAMES (idle host is normal)'), 6000);
    });
  }
  log('done');
  process.exit(0);
})();
