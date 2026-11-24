'use strict';

const http = require('node:http');
const gate = require('/app/scripts/lib/a6-maintenance.cjs');

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const send = (status, body) => {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  try {
    if (req.method === 'GET' && url.pathname === '/lab/health') {
      const reply = await fetch('http://127.0.0.1:18090/health', {
        signal: AbortSignal.timeout(3000)
      });
      const bridge = await reply.json();
      return send(reply.ok ? 200 : 503, {
        whatsapp: 'SIMULATED', bridge: bridge.data?.status, phase: gate.snapshot().phase
      });
    }
    if (!gate.authorized(req.headers['x-a6-node-maintenance-token'])) return send(401, {});
    if (req.method === 'GET' && url.pathname === '/ops/maintenance/status') {
      return send(200, gate.snapshot());
    }
    if (req.method === 'POST' && url.pathname === '/ops/maintenance/enter') {
      const state = await gate.enter(30000);
      return send(state.quiescent ? 200 : 409, state);
    }
    if (req.method === 'POST' && url.pathname === '/ops/maintenance/resume') {
      return send(200, gate.resume());
    }
    return send(404, {});
  } catch {
    return send(503, { error: 'lab_request_failed' });
  }
}).listen(3001, '127.0.0.1');
