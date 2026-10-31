/**
 * dsh App bridge — lets the legacy DshRemote Android client (dsh 0.1.x protocol)
 * talk to a dsh 0.2.x host.
 *
 *   legacy App  ──old /api/* ──▶  this bridge  ──Remote/typert /api/* ──▶  dsh 0.2.x
 *
 * HTTP:  dotted methods  →  namespace/method + {args:{request|_request:…}}
 * WS:    /api/events.mux + /api/events.host  ←  $events (global) + session/follow (per session)
 */
// 全局错误处理：防止未捕获异常导致 bridge 崩溃
process.on('unhandledRejection', (reason) => {
  log('unhandledRejection:', reason?.message || reason);
});
process.on('uncaughtException', (err) => {
  log('uncaughtException:', err.message);
});

const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const WebSocket = require('ws');

const LISTEN_PORT = Number(process.env.BRIDGE_PORT || 3084);
const DSH = { host: '127.0.0.1', port: Number(process.env.DSH_PORT || 3080) };
const AUTHORITY = `127.0.0.1:${DSH.port}`;
const CRED_FILE = path.join(os.homedir(), '.dsh', '.credentials.yaml');
const LOG = process.env.BRIDGE_LOG !== '0';
const log = (...a) => { if (LOG) console.log(new Date().toISOString().slice(11, 19), ...a); };

// ---------------- dsh browser-session cookie ----------------
const b64url = (b) => Buffer.from(b).toString('base64').replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');

function readSecret() {
  const lines = fs.readFileSync(CRED_FILE, 'utf8').split(/\r?\n/);
  const at = lines.findIndex((l) => l.trim() === 'client-connection/browser-session:');
  if (at === -1) throw new Error('browser-session credential not found in ' + CRED_FILE);
  for (let i = at + 1; i < Math.min(lines.length, at + 8); i += 1) {
    const m = /^\s+secret:\s*(\S+)\s*$/.exec(lines[i]);
    if (m) return Buffer.from(m[1].replaceAll('-', '+').replaceAll('_', '/'), 'base64');
  }
  throw new Error('secret field not found');
}

let cookieCache = { value: null, expiresAt: 0 };
function dshCookie() {
  if (cookieCache.value && Date.now() < cookieCache.expiresAt - 3600_000) return cookieCache.value;
  const secret = readSecret();
  if (secret.byteLength !== 32) throw new Error('unexpected secret length ' + secret.byteLength);
  const issuedAt = Date.now();
  const expiresAt = issuedAt + 30 * 1440 * 60 * 1000;
  const name = 'dsh-auth-' + b64url(crypto.createHash('sha256').update(AUTHORITY).digest());
  const body = b64url(Buffer.from(JSON.stringify({ version: 1, authority: AUTHORITY, issuedAt, expiresAt }), 'utf8'));
  const sig = b64url(crypto.createHmac('sha256', secret).update(body).digest());
  cookieCache = { value: `${name}=v1.${body}.${sig}`, expiresAt };
  return cookieCache.value;
}

const dshHeaders = (extra = {}) => ({ host: AUTHORITY, cookie: dshCookie(), ...extra });

// ---------------- wire size control (the phone link is slow) ----------------
/**
 * Write a JSON body, gzipping it when the client advertises gzip.
 *
 * OkHttp adds `Accept-Encoding: gzip` and transparently decompresses, so this
 * is invisible to the App but shrinks the session list by roughly 10x. That
 * matters because the tailnet path is frequently relayed (DERP) with a
 * 0.3-1.4 s RTT: a 400 KB pull there can take longer than the client's probe
 * budget, which is what made connecting look broken.
 */
function writeJsonBody(res, code, text, extraHeaders = {}) {
  const buf = Buffer.from(text, 'utf8');
  const accept = String(res.req?.headers?.['accept-encoding'] || '');
  const headers = { 'content-type': 'application/json', ...extraHeaders };
  if (buf.length >= 1024 && /\bgzip\b/i.test(accept)) {
    const gz = zlib.gzipSync(buf, { level: 6 });
    res.writeHead(code, {
      ...headers,
      'content-encoding': 'gzip',
      vary: 'accept-encoding',
      'content-length': gz.length,
    });
    res.end(gz);
    return;
  }
  res.writeHead(code, { ...headers, 'content-length': buf.length });
  res.end(buf);
}

/**
 * Projection values the legacy client actually renders.
 *
 * `session/list` ships every projection for every session. `turnOutline` alone
 * is ~227 KB of a ~400 KB list and the App has no model for it at all, so it
 * was pure download cost on every connect, every 2 s during a run, and on
 * every watchdog tick.
 */
const CLIENT_PROJECTIONS = new Set([
  'asOfSeq', 'title', 'sessionStats', 'goal', 'todos', 'permissions', 'plan',
  'tokenUsage', 'contextPressure', 'contextBreakdown', 'modelSelection',
  'inbox', 'subagentCatalog', 'agentPreset', 'subagent',
]);

/** Drop projection values the App cannot render. Never mutates the dsh reply shape. */
function slimSessionList(r) {
  const items = Array.isArray(r?.value?.items) ? r.value.items : null;
  if (!items) return r;
  for (const item of items) {
    const values = item?.projections?.values;
    if (!values || typeof values !== 'object' || Array.isArray(values)) continue;
    const kept = {};
    for (const key of Object.keys(values)) {
      if (CLIENT_PROJECTIONS.has(key)) kept[key] = values[key];
    }
    item.projections.values = kept;
  }
  return r;
}


// ---------------- unary RPC to dsh ----------------
/**
 * One unary call.
 *
 * The signed cookie is memoised for ~30 days, so if dsh ever regenerates its
 * `client-connection/browser-session` secret (reinstall, restored profile,
 * dsh upgrade), the cached cookie dies and every request 401s forever while
 * the host itself looks perfectly healthy — the exact "连接不上" symptom with
 * no clue on the host side. A 401 therefore re-signs once and retries.
 */
function dshRpc(endpoint, args, retried = false) {
  const body = JSON.stringify({
    type: 'client-request', rpcId: crypto.randomUUID(), method: endpoint, payload: { args: args ?? {} },
  });
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: DSH.host, port: DSH.port, path: `/api/${endpoint}`, method: 'POST',
      headers: dshHeaders({ 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) }),
    }, (res) => {
      if (res.statusCode === 401 && !retried) {
        res.resume();
        cookieCache = { value: null, expiresAt: 0 };
        log('dsh said 401 — re-signing the session cookie and retrying', endpoint);
        resolve(dshRpc(endpoint, args, true));
        return;
      }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let parsed;
        try { parsed = JSON.parse(text); } catch { reject(new Error(`non-JSON from ${endpoint}: ${text.slice(0, 160)}`)); return; }
        resolve(parsed?.result ?? { ok: false, error: { code: 'bridge/bad-response', message: text.slice(0, 160), details: {} } });
      });
    });
    req.on('error', reject);
    req.end(body);
  });
}

// ---------------- upstream mux connection (shared) ----------------
/** Subscribers: { channel: 'mux'|'host', send(frame) } */
const subscribers = new Set();
let activeSessionId = null;

function broadcast(channel, frame) {
  for (const s of subscribers) {
    if (s.channel !== channel) continue;
    try { s.send(frame); } catch { /* ignore */ }
  }
}

function openMux(onOpen) {
  const ws = new WebSocket(`ws://${DSH.host}:${DSH.port}/api/remote.mux`, {
    headers: dshHeaders({ origin: `http://${AUTHORITY}` }),
  });
  return ws;
}

// ---- $events (global notifications) ----
let eventsWs = null;
let eventsClientId = null;

/** waterfall eventId → kind, so /api/respond can shape the outcome */
const pendingWaterfalls = new Map();

function startEvents() {
  eventsWs = openMux();
  eventsWs.on('open', () => {
    eventsWs.send(JSON.stringify({ type: 'open', streamId: 'events', endpoint: '$events', payload: { args: {} } }));
  });
  eventsWs.on('message', (buf) => {
    let msg;
    try { msg = JSON.parse(buf.toString()); } catch { return; }
    if (msg.type === 'error') { log('$events error', JSON.stringify(msg.error).slice(0, 200)); return; }
    if (msg.type !== 'item' || msg.streamId !== 'events') return;
    const v = msg.value;
    if (!v || typeof v !== 'object') return;
    if (v.type === 'ready') { eventsClientId = v.clientId; log('$events ready', v.clientId); return; }
    if (v.type === 'emit') return handleEmit(v.event, v.args);
    if (v.type === 'waterfall') return handleWaterfall(v);
  });
  const reopen = () => setTimeout(startEvents, 2000);
  eventsWs.on('close', reopen);
  eventsWs.on('error', () => {});
}

const asArray = (a) => (Array.isArray(a) ? a : a == null ? [] : [a]);

function handleEmit(event, args) {
  const a = asArray(args);
  switch (event) {
    case 'api-session/status':
      broadcast('host', { type: 'server-request', rpcId: crypto.randomUUID(), method: 'event', payload: { type: 'host/session-status', sessionId: a[0], running: a[1] === true } });
      return;
    case 'api-session/added':
      broadcast('host', { type: 'server-request', rpcId: crypto.randomUUID(), method: 'event', payload: { type: 'host/session-added' } });
      return;
    case 'api-session/removed':
      broadcast('host', { type: 'server-request', rpcId: crypto.randomUUID(), method: 'event', payload: { type: 'host/session-removed' } });
      return;
    case 'api-session/activity':
      // The legacy client answers session/projection by re-pulling the session list.
      broadcast('mux', { type: 'server-request', rpcId: crypto.randomUUID(), method: 'event', payload: { type: 'session/projection', sessionId: a[0] } });
      return;
    case 'agent-preset/selected':
    case 'commands/change':
    case 'goal/activation-changed':
      broadcast('mux', { type: 'server-request', rpcId: crypto.randomUUID(), method: 'event', payload: { type: 'session/projection', sessionId: a[0] } });
      return;
    default:
      return; // not part of the legacy surface
  }
}

/** Shape the App's respond payload into what the dsh waterfall listener resolves with.
 *
 *  - approval: the Cordis waterfall resolves the decision STRING ('allowed-once'|'rejected');
 *    DshRemote sends {approvalId, sessionId, outcome} — the `outcome` is the decision.
 *  - question: the waterfall resolves {answers:[{id, selected, custom?}]};
 *    DshRemote sends {sessionId, answer:{answers:[...]}} — unwrap `answer`.
 */
function normalizeRespondValue(kind, value) {
  if (kind === 'approval') {
    if (typeof value === 'string') return value;
    if (value && typeof value === 'object') {
      const decision = value.outcome ?? value.decision;
      if (decision === 'allowed-once' || decision === 'rejected') return decision;
      if (value.allow === true) return 'allowed-once';
      if (value.allow === false) return 'rejected';
    }
    return 'rejected'; // fail closed when the App's payload is unreadable
  }
  if (kind === 'question') {
    const answer = value && typeof value === 'object' ? (value.answer ?? value) : {};
    if (Array.isArray(answer.answers)) return { answers: answer.answers };
    if (Array.isArray(answer)) return { answers: answer };
    return { answers: [] };
  }
  return value ?? null;
}

function handleWaterfall(v) {
  const request = v.request && typeof v.request === 'object' ? v.request : {};
  if (v.event === 'approval/request') {
    pendingWaterfalls.set(v.eventId, { kind: 'approval' });
    broadcast('mux', {
      type: 'server-request', rpcId: v.eventId, method: 'event',
      payload: {
        type: 'approval/requested',
        sessionId: request.sessionId ?? v.agentId ?? null,
        approvalId: request.approvalId ?? request.id ?? v.eventId,
        toolName: request.toolName ?? '',
        ...(request.reason !== undefined ? { reason: request.reason } : {}),
      },
    });
    return;
  }
  if (v.event === 'user-questions/request') {
    pendingWaterfalls.set(v.eventId, { kind: 'question' });
    broadcast('mux', {
      type: 'server-request', rpcId: v.eventId, method: 'event',
      payload: {
        type: 'question/requested',
        sessionId: request.sessionId ?? v.agentId ?? null,
        questions: request.questions ?? [],
      },
    });
    return;
  }
  log('unmapped waterfall', v.event);
}

// ---- session/follow (per active session streaming) ----
let followWs = null;
let followSessionId = null;

function restartFollow(sessionId) {
  followSessionId = sessionId;
  if (followWs) { try { followWs.removeAllListeners(); followWs.close(); } catch {} followWs = null; }
  if (!sessionId) return;
  const ws = openMux();
  followWs = ws;
  const mine = sessionId;
  ws.on('open', () => {
    ws.send(JSON.stringify({
      type: 'open', streamId: 'follow', endpoint: 'session/follow',
      payload: { args: { request: { address: { kind: 'session', sessionId: mine }, assistantStream: true } } },
    }));
    log('session/follow opened for', mine);
  });
  ws.on('message', (buf) => {
    let msg;
    try { msg = JSON.parse(buf.toString()); } catch { return; }
    if (msg.type === 'error') { log('follow error', JSON.stringify(msg.error).slice(0, 200)); return; }
    if (msg.type !== 'item' || msg.streamId !== 'follow') return;
    const v = msg.value;
    if (!v || typeof v !== 'object') return;
    if (v.type === 'snapshot') return; // legacy client pulls history via session.history
    if (v.type === 'event' && v.event) {
      broadcast('mux', {
        type: 'server-request', rpcId: crypto.randomUUID(), method: 'event',
        payload: { type: 'session/event', sessionId: mine, event: v.event },
      });
    }
  });
  ws.on('close', () => {
    if (followSessionId === mine) setTimeout(() => { if (followSessionId === mine) restartFollow(mine); }, 2000);
  });
  ws.on('error', () => {});
}

function noteSession(sessionId) {
  if (!sessionId || typeof sessionId !== 'string') return;
  if (sessionId === activeSessionId) return;
  activeSessionId = sessionId;
  restartFollow(sessionId);
}

// ---------------- workspace/follow cache ----------------
const workspaceState = { value: { items: [], archivedSessionIds: [] }, waiters: [], started: false };

function startWorkspaceFollow() {
  if (workspaceState.started) return;
  workspaceState.started = true;
  const connect = () => {
    const ws = openMux();
    ws.on('open', () => ws.send(JSON.stringify({ type: 'open', streamId: 'ws-follow', endpoint: 'workspace/follow', payload: { args: {} } })));
    ws.on('message', (buf) => {
      let msg;
      try { msg = JSON.parse(buf.toString()); } catch { return; }
      if (msg.type !== 'item' || msg.streamId !== 'ws-follow') return;
      const v = msg.value;
      const candidate = v?.value?.items ? v.value : v?.items ? v : null;
      if (!candidate) return;
      workspaceState.value = { items: candidate.items || [], archivedSessionIds: candidate.archivedSessionIds || [] };
      for (const fn of workspaceState.waiters.splice(0)) fn(workspaceState.value);
    });
    ws.on('close', () => setTimeout(connect, 3000));
    ws.on('error', () => {});
  };
  connect();
}

function workspaceList() {
  startWorkspaceFollow();
  if (workspaceState.value.items.length > 0) return Promise.resolve(workspaceState.value);
  return new Promise((resolve) => {
    const t = setTimeout(() => resolve(workspaceState.value), 4000);
    workspaceState.waiters.push((v) => { clearTimeout(t); resolve(v); });
  });
}

// ---------------- legacy endpoint translation ----------------
const WRAP_REQUEST = new Set([
  'session/attachment', 'session/create', 'session/updateQueue', 'session/fork',
  'workspace/archiveSession', 'workspace/rename', 'workspace/delete', 'workspace/create',
  'workspace/insertSessionBefore', 'workspace/insertBefore', 'workspace/pinSession',
  'workspace/unpinSession', 'workspace/unarchiveSession',
  'session/search', 'session/selectModel', 'session/rename', 'session/cancel',
]);

const modelCatalogToLegacy = (cat) => ({
  current: cat?.default ?? null,
  routable: Array.isArray(cat?.routableProviders) && cat.routableProviders.length > 0,
  groups: Array.isArray(cat?.groups) ? cat.groups : [],
});

/** Cached `session/modelCatalog` result: it only changes on provider reconfiguration. */
let catalogCache = { at: 0, value: null };
async function modelCatalogCached() {
  if (catalogCache.value !== null && Date.now() - catalogCache.at < 60_000) return catalogCache.value;
  const r = await dshRpc('session/modelCatalog', {});
  if (r.ok) catalogCache = { at: Date.now(), value: r };
  return r;
}

const goalRef = (p) => ({ agentId: p.sessionId, ref: { id: p.refId ?? p.id, revision: p.revision } });

async function legacyCall(method, p) {
  p = p || {};
  switch (method) {
    case 'workspace.list':
      return { ok: true, value: await workspaceList() };

    case 'session.list':
      // Slimmed before it goes on the wire: the raw reply is ~400 KB, which the
      // App re-pulls on connect, on watchdog ticks and every 2 s during a run.
      return slimSessionList(await dshRpc('session/list', { _request: {} }));

    case 'settings.describe': {
      const r = await dshRpc('settings/describe', p);
      if (!r.ok) return r;
      // Trim per-namespace schema/base/user/secrets/applies metadata: the phone
      // only renders ns + current value + revision, and shipping the full ~62 KB
      // tree risked the client choking while decoding it.
      return {
        ok: true,
        value: {
          writable: r.value?.writable === true,
          namespaces: (Array.isArray(r.value?.namespaces) ? r.value.namespaces : []).map((n) => ({
            ns: n?.ns ?? '',
            value: n?.value ?? {},
            revision: typeof n?.revision === 'number' ? n.revision : 0,
          })).filter((n) => n.ns !== ''),
        },
      };
    }

    case 'session.models': {
      // Two RPCs, fetched concurrently; the catalog is cached because it only
      // changes when providers are reconfigured. A caller without a session
      // (the settings screen) just wants the catalog.
      const projPromise = p.sessionId
        ? dshRpc('session/projections', { request: { sessionId: p.sessionId } }).catch(() => null)
        : Promise.resolve(null);
      const [r, proj] = await Promise.all([modelCatalogCached(), projPromise]);
      if (!r.ok) return r;
      const legacy = modelCatalogToLegacy(r.value);
      // The catalog's `default` is the deployment default, NOT the model the
      // open Session is actually using — reading it made a successful
      // selectModel look like a no-op. Take the Session's real selection from
      // its modelSelection projection (`next`, falling back to `lastUsed`).
      try {
        const sel = proj?.ok ? proj.value?.values?.modelSelection : null;
        const cur = sel?.next ?? sel?.lastUsed;
        if (cur && cur.provider && cur.model) {
          legacy.current = {
            provider: cur.provider,
            model: cur.model,
            ...(cur.reasoningEffort !== undefined ? { reasoningEffort: cur.reasoningEffort } : {}),
          };
        }
      } catch (e) {
        log('session.models: projection lookup failed', e.message);
      }
      return { ok: true, value: legacy };
    }

    case 'session.history': {
      const proj = await dshRpc('session/projections', { request: { sessionId: p.sessionId } });
      const seq = proj?.ok ? proj.value?.asOfSeq ?? 0 : 0;
      const request = { address: { kind: 'session', sessionId: p.sessionId }, throughSeq: p.beforeSeq != null ? p.beforeSeq : seq };
      if (p.maxMessages != null) request.maxMessages = p.maxMessages;
      const r = await dshRpc('session/page', { request });
      return r.ok ? { ok: true, value: { events: r.value?.records ?? [], hasMore: r.value?.hasMore ?? false } } : r;
    }

    case 'session.prompt':
      return dshRpc('session/prompt', { request: { ...p, requestId: crypto.randomUUID() } });

    case 'agentPreset.list': {
      const r = await dshRpc('agentPresets/list', {});
      return r.ok ? { ok: true, value: { presets: r.value?.presets ?? [], authorable: false, hasDocument: false } } : r;
    }

    case 'agentPreset.select':
      return dshRpc('agentPresets/select', { agentId: p.sessionId, agentPreset: p.agentPreset });

    case 'commands/execute':
      return dshRpc('commands/execute', { agentId: p.agentId, line: p.line, submittedAttachments: Array.isArray(p.images) ? p.images : [] });

    case 'commands/list':
      return dshRpc('commands/list', { agentId: p.agentId });

    case 'goal.create':
      return dshRpc('goals/create', { agentId: p.sessionId, request: { objective: p.objective } });
    case 'goal.pause': return dshRpc('goals/pause', goalRef(p));
    case 'goal.resume': return dshRpc('goals/resume', goalRef(p));
    case 'goal.complete': return dshRpc('goals/complete', goalRef(p));
    case 'goal.clear': return dshRpc('goals/clear', goalRef(p));

    case 'subagent.list':
      return { ok: true, value: { entries: [], parentAvailable: false } };

    case 'subagent.history': {
      const request = {
        address: { kind: 'subagent', parentSessionId: p.parentSessionId, childSessionId: p.childSessionId, mode: p.mode || 'continuable' },
        throughSeq: Number.MAX_SAFE_INTEGER,
        maxMessages: p.maxMessages ?? 30,
      };
      const r = await dshRpc('session/page', { request });
      return r.ok ? { ok: true, value: { events: r.value?.records ?? [], hasMore: r.value?.hasMore ?? false } } : r;
    }

    case 'subagent.interrupt':
      return dshRpc('subagents/interruptByParent', { parentSessionId: p.parentSessionId, childSessionId: p.childSessionId, mode: p.mode || 'continuable' });

    default: {
      const endpoint = method.replace('.', '/');
      return dshRpc(endpoint, WRAP_REQUEST.has(endpoint) ? { request: p } : p);
    }
  }
}

// ---------------- HTTP server ----------------
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', 'http://bridge.invalid');
  if (url.pathname.startsWith('/remote/')) return handleRemote(req, res, url);
  if (!url.pathname.startsWith('/api/')) return proxyThrough(req, res);

  let body = '';
  try {
    for await (const c of req) body += c;
  } catch (err) {
    log('request aborted:', err.message);
    return;
  }
  let payload = {};
  try { payload = JSON.parse(body || '{}')?.payload ?? {}; } catch { res.writeHead(400); res.end('bad json'); return; }

  if (payload && typeof payload.sessionId === 'string') noteSession(payload.sessionId);

  if (url.pathname === '/api/respond') {
    const rpcId = JSON.parse(body || '{}')?.rpcId;
    const value = JSON.parse(body || '{}')?.result?.value;
    const pending = pendingWaterfalls.get(rpcId);
    pendingWaterfalls.delete(rpcId);
    if (!pending || !eventsClientId) { log('respond: no pending waterfall for', rpcId); res.writeHead(200); res.end('{"ok":true}'); return; }
    const r = await dshRpc('$events/result', { clientId: eventsClientId, eventId: rpcId, outcome: { kind: 'result', value: normalizeRespondValue(pending.kind, value) } });
    log('respond', pending.kind, rpcId, JSON.stringify(r).slice(0, 180));
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end('{"ok":true}');
    return;
  }

  const method = url.pathname.slice('/api/'.length);
  try {
    const result = await legacyCall(method, payload);
    if (result && result.ok === false) {
      log('rpc FAIL', method, JSON.stringify(result.error || {}).slice(0, 200));
    } else {
      log('rpc ok', method);
    }
    writeJsonBody(res, 200, JSON.stringify({ type: 'server-response', rpcId: crypto.randomUUID(), result }));
  } catch (error) {
    log('call failed', method, error.message);
    writeJsonBody(res, 200, JSON.stringify({ type: 'server-response', rpcId: crypto.randomUUID(), result: { ok: false, error: { code: 'bridge/failure', message: String(error.message), details: {} } } }));
  }
});

// ---------------- /remote/* host helper routes (workspace file browse) ----------------
// The dsh 0.1.x host served these under /remote/*; 0.2.x removed them, so the
// bridge re-implements them directly against the local filesystem.

function sendJson(res, code, obj, extraHeaders = {}) {
  writeJsonBody(res, code, JSON.stringify(obj), extraHeaders);
}

function sendRemoteErr(res, code, message) {
  sendJson(res, code, { ok: false, error: String(message) });
}

/** Resolve one user-supplied path, rejecting traversal and NUL. */
function safeResolve(raw) {
  if (typeof raw !== 'string' || raw.length === 0) return null;
  if (raw.includes('\0')) return null;
  const seg = raw.split(/[\\/]/);
  if (seg.includes('..')) return null;
  // A bare drive letter ("D:") means the drive root.
  if (/^[A-Za-z]:$/.test(raw)) return `${raw[0]}:\\`;
  return path.resolve(raw);
}

function entryOf(full) {
  let st;
  try { st = fs.statSync(full); } catch { return null; }
  return {
    name: path.basename(full),
    path: full,
    isDirectory: st.isDirectory(),
    size: st.isDirectory() ? 0 : st.size,
    mtime: st.mtimeMs,
  };
}

function listDrives() {
  const drives = [];
  for (let c = 65; c <= 90; c += 1) {
    const d = `${String.fromCharCode(c)}:\\`;
    try { if (fs.statSync(d).isDirectory()) drives.push({ name: d.slice(0, 2), path: d, isDirectory: true, size: 0, mtime: 0 }); } catch { /* skip */ }
  }
  return drives;
}

function listDirectory(dir) {
  const entries = fs.readdirSync(dir, { withFileTypes: true })
    .map((e) => {
      const full = path.join(dir, e.name);
      let st;
      try { st = fs.statSync(full); } catch { return null; }
      return { name: e.name, path: full, isDirectory: e.isDirectory() || st.isDirectory(), size: st.isDirectory() ? 0 : st.size, mtime: st.mtimeMs };
    })
    .filter(Boolean)
    .sort((a, b) => (a.isDirectory === b.isDirectory ? a.name.localeCompare(b.name) : a.isDirectory ? -1 : 1));
  const parent = path.dirname(dir) === dir ? '' : path.dirname(dir);
  // Drives are a root-level navigation aid only: returning them for every
  // directory makes the client render just the drive list and never descend.
  return { path: dir, parent, entries, drives: [] };
}

async function readJsonBody(req) {
  let body = '';
  for await (const c of req) body += c;
  return JSON.parse(body || '{}');
}

async function handleRemote(req, res, url) {
  const p = url.pathname;
  try {
    if (p === '/remote/list') {
      const raw = url.searchParams.get('path');
      if (!raw) return sendJson(res, 200, { path: '', parent: '', entries: [], drives: listDrives() });
      const dir = safeResolve(raw);
      if (!dir || !fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return sendRemoteErr(res, 400, `not a directory: ${raw}`);
      return sendJson(res, 200, listDirectory(dir));
    }

    if (p === '/remote/mkdir') {
      const b = await readJsonBody(req);
      const dir = safeResolve(b.dir);
      const name = String(b.name || '').trim();
      if (!dir || !name || !fs.existsSync(dir)) return sendRemoteErr(res, 400, 'bad mkdir request');
      if (name.includes('/') || name.includes('\\') || name === '.' || name === '..') return sendRemoteErr(res, 400, 'bad folder name');
      fs.mkdirSync(path.join(dir, name), { recursive: false });
      return sendJson(res, 200, { ok: true });
    }

    if (p === '/remote/delete') {
      const b = await readJsonBody(req);
      const target = safeResolve(b.path);
      if (!target || !fs.existsSync(target)) return sendRemoteErr(res, 404, 'path not found');
      fs.rmSync(target, { recursive: true, force: true });
      return sendJson(res, 200, { ok: true });
    }

    if (p === '/remote/rename') {
      const b = await readJsonBody(req);
      const target = safeResolve(b.path);
      const name = String(b.name || '').trim();
      if (!target || !name || !fs.existsSync(target)) return sendRemoteErr(res, 400, 'bad rename request');
      if (name.includes('/') || name.includes('\\') || name === '.' || name === '..') return sendRemoteErr(res, 400, 'bad name');
      fs.renameSync(target, path.join(path.dirname(target), name));
      return sendJson(res, 200, { ok: true });
    }

    if (p === '/remote/copy') {
      const b = await readJsonBody(req);
      const target = safeResolve(b.path);
      if (!target || !fs.existsSync(target)) return sendRemoteErr(res, 404, 'path not found');
      const base = path.basename(target);
      const dot = base.lastIndexOf('.');
      const stem = dot > 0 ? base.slice(0, dot) : base;
      const ext = dot > 0 ? base.slice(dot) : '';
      const dest = path.join(path.dirname(target), `${stem}-copy${ext}`);
      if (fs.statSync(target).isDirectory()) fs.cpSync(target, dest, { recursive: true });
      else fs.copyFileSync(target, dest);
      return sendJson(res, 200, { ok: true });
    }

    if (p === '/remote/upload') {
      const b = await readJsonBody(req);
      const dir = safeResolve(b.dir);
      const name = String(b.name || '').trim();
      if (!dir || !name || !fs.existsSync(dir)) return sendRemoteErr(res, 400, 'bad upload request');
      if (name.includes('/') || name.includes('\\')) return sendRemoteErr(res, 400, 'bad name');
      const data = Buffer.from(String(b.data || ''), 'base64');
      fs.writeFileSync(path.join(dir, name), data);
      return sendJson(res, 200, { ok: true });
    }

    if (p === '/remote/file') {
      const target = safeResolve(url.searchParams.get('path'));
      if (!target || !fs.existsSync(target) || !fs.statSync(target).isFile()) return sendRemoteErr(res, 404, 'file not found');
      const data = fs.readFileSync(target);
      const type = (target.match(/\.(\w+)$/)?.[1] || 'bin').toLowerCase();
      const mime = type === 'txt' || type === 'md' || type === 'json' || type === 'log' ? 'text/plain'
        : type === 'png' ? 'image/png' : type === 'jpg' || type === 'jpeg' ? 'image/jpeg' : 'application/octet-stream';
      res.writeHead(200, { 'content-type': mime, 'content-length': data.length });
      res.end(data);
      return;
    }

    return sendRemoteErr(res, 404, `unknown remote route: ${p}`);
  } catch (e) {
    log('remote error', p, e.message);
    return sendRemoteErr(res, 500, String(e.message || e));
  }
}

function proxyThrough(req, res) {
  const headers = { ...req.headers, ...dshHeaders() };
  delete headers['content-length'];
  const up = http.request({ host: DSH.host, port: DSH.port, path: req.url, method: req.method, headers }, (r) => {
    res.writeHead(r.statusCode || 502, r.headers);
    r.pipe(res);
  });
  up.on('error', () => { res.writeHead(502); res.end(); });
  req.pipe(up);
}

// ---------------- WebSocket upgrade ----------------
const wss = new WebSocket.Server({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url || '/', 'http://bridge.invalid');
  const channel = url.pathname === '/api/events.host' ? 'host' : url.pathname === '/api/events.mux' ? 'mux' : null;
  if (!channel) { socket.destroy(); return; }
  wss.handleUpgrade(req, socket, head, (ws) => {
    log(channel + ': client connected');
    const sub = { channel, send: (frame) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(frame)); } };
    subscribers.add(sub);
    if (activeSessionId && !followWs) restartFollow(activeSessionId);
    ws.on('close', () => { subscribers.delete(sub); log(channel + ': client disconnected'); });
    ws.on('error', () => {});
  });
});

startEvents();
server.listen(LISTEN_PORT, '127.0.0.1', () => log(`bridge listening on 127.0.0.1:${LISTEN_PORT} → dsh ${AUTHORITY}`));
