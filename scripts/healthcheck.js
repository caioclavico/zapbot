// Sem dependências ou acesso à sessão. Falha apenas sinaliza saúde ao Docker.
const http = require('node:http');
const req = http.get('http://127.0.0.1:3001/health', { timeout: 5000 }, res => {
  let body = '';
  res.setEncoding('utf8');
  res.on('data', chunk => { body += chunk; });
  res.on('end', () => {
    console.log(body);
    try {
      const health = JSON.parse(body);
      process.exitCode = res.statusCode === 200 && health.status === 'ok' &&
        health.whatsapp === 'READY' && health.chromium === true ? 0 : 1;
    } catch { process.exitCode = 1; }
  });
  res.on('error', () => { process.exitCode = 1; });
});
req.on('timeout', () => req.destroy(new Error('Healthcheck excedeu 5s')));
req.on('error', error => { console.error(error.message); process.exitCode = 1; });
