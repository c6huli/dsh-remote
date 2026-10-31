// Measure the bytes a legacy client actually pulls, with and without gzip.
//
//   node diag-wire.mjs http://<host>:8080 [method]
//
// Raw node:http on purpose: fetch() would transparently decode the body and
// hide the one number that matters on a slow (DERP-relayed) link. Before the
// bridge started slimming and compressing `session.list`, this printed
// ~400000 / ~400000; it should now print ~13700 with gzip and ~119000 without.
import http from 'node:http';

const target = process.argv[2] || 'http://127.0.0.1:8080';
const method = process.argv[3] || 'session.list';
const u = new URL(`/api/${method}`, target.endsWith('/') ? target : `${target}/`);
const body = JSON.stringify({ args: {} });

function probe(acceptEncoding) {
  return new Promise((resolve) => {
    const req = http.request({
      host: u.hostname,
      port: u.port,
      path: u.pathname,
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(body),
        'user-agent': 'okhttp/4.12.0', // must match the Caddy route to the bridge
        ...(acceptEncoding ? { 'accept-encoding': acceptEncoding } : {}),
      },
    }, (res) => {
      let n = 0;
      res.on('data', (c) => { n += c.length; });
      res.on('end', () => resolve({ ae: acceptEncoding || '(none)', encoding: res.headers['content-encoding'] || '(none)', wire: n }));
    });
    req.on('error', (e) => resolve({ ae: acceptEncoding, encoding: 'ERR', wire: e.message }));
    req.end(body);
  });
}

console.log(`POST ${u.href}`);
for (const ae of ['gzip', 'identity']) {
  const r = await probe(ae);
  console.log(`accept-encoding=${r.ae.padEnd(9)} content-encoding=${String(r.encoding).padEnd(8)} bytes on wire=${r.wire}`);
}
