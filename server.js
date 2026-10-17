// Tiny relay so the phone page can talk to the TV app.
// POST /p/<room>  (JSON body)  -> sent to everyone listening on that room
// GET  /e/<room>               -> Server-Sent Events stream for that room
// Messages with "keep":true are remembered and sent to anyone who connects later.
const http = require('http');
const rooms = new Map(); // room -> {clients:Set, last:string|null, seen:number}
const ROOM_RE = /^[a-z0-9-]{4,40}$/;

function room(name) {
  let r = rooms.get(name);
  if (!r) { r = { clients: new Set(), last: null, seen: Date.now() }; rooms.set(name, r); }
  r.seen = Date.now();
  return r;
}
function cors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
}
const server = http.createServer((req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
  const url = new URL(req.url, 'http://x');
  const m = url.pathname.match(/^\/(p|e)\/([^/]+)$/);
  if (!m) { res.writeHead(200, { 'Content-Type': 'text/plain' }); return res.end('ok'); }
  const name = m[2].toLowerCase();
  if (!ROOM_RE.test(name)) { res.writeHead(400); return res.end('bad room'); }

  if (m[1] === 'e' && req.method === 'GET') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no'
    });
    res.write('retry: 3000\n\n');
    const r = room(name);
    if (r.last) res.write('event: last\ndata: ' + r.last + '\n\n');
    r.clients.add(res);
    const ping = setInterval(() => { try { res.write(': ping\n\n'); } catch (e) {} }, 15000);
    req.on('close', () => { clearInterval(ping); r.clients.delete(res); r.seen = Date.now(); });
    return;
  }
  if (m[1] === 'p' && req.method === 'POST') {
    let body = '';
    req.on('data', c => { body += c; if (body.length > 32768) req.destroy(); });
    req.on('end', () => {
      let obj;
      try { obj = JSON.parse(body); } catch (e) { res.writeHead(400); return res.end('bad json'); }
      const line = JSON.stringify(obj);
      const r = room(name);
      if (obj && obj.keep) r.last = line;
      for (const c of r.clients) { try { c.write('data: ' + line + '\n\n'); } catch (e) {} }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, listeners: r.clients.size }));
    });
    return;
  }
  res.writeHead(405); res.end();
});
// forget idle rooms after a day
setInterval(() => {
  const old = Date.now() - 86400000;
  for (const [k, r] of rooms) if (!r.clients.size && r.seen < old) rooms.delete(k);
}, 3600000);
server.keepAliveTimeout = 120000;
server.listen(process.env.PORT || 3000, () => console.log('relay up'));
