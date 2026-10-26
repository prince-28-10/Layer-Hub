import test from 'node:test';
import assert from 'node:assert';
import http from 'node:http';
import { app } from '../src/index.js';

test('GET /api/health returns 200 with service status', async (t) => {
  const server = http.createServer(app);
  await new Promise(resolve => server.listen(0, resolve));
  const port = server.address().port;

  try {
    const res = await fetch(`http://localhost:${port}/api/health`);
    assert.strictEqual(res.status, 200);
    const body = await res.json();
    assert.strictEqual(body.status, 'ok');
    assert.strictEqual(body.service, 'layerhub-backend');
    assert.ok(body.timestamp);
    assert.ok(typeof body.uptimeSeconds === 'number');
  } finally {
    server.close();
  }
});
