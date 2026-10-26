import test from 'node:test';
import assert from 'node:assert';
import http from 'node:http';
import { app } from '../src/index.js';

test('Strict 180-second rule: 179s and 180s allowed, 181s rejected with exact message', async () => {
  const server = http.createServer(app);
  await new Promise(resolve => server.listen(0, resolve));
  const port = server.address().port;

  try {
    // 1. 179s should be allowed
    const res179 = await fetch(`http://localhost:${port}/api/media/prepare`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: 'https://example.com/test179.mp4',
        mediaType: 'VIDEO',
        durationSeconds: 179,
      }),
    });
    assert.strictEqual(res179.status, 200);
    const body179 = await res179.json();
    assert.ok(body179.jobId);

    // 2. 180s should be allowed
    const res180 = await fetch(`http://localhost:${port}/api/media/prepare`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: 'https://example.com/test180.mp4',
        mediaType: 'VIDEO',
        durationSeconds: 180,
      }),
    });
    assert.strictEqual(res180.status, 200);
    const body180 = await res180.json();
    assert.ok(body180.jobId);

    // 3. 181s must be rejected with 400 and exact message
    const res181 = await fetch(`http://localhost:${port}/api/media/prepare`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: 'https://example.com/test181.mp4',
        mediaType: 'VIDEO',
        durationSeconds: 181,
      }),
    });
    assert.strictEqual(res181.status, 400);
    const body181 = await res181.json();
    assert.strictEqual(
      body181.message,
      'Video exceeds the 3-minute limit. Please choose a video that is 180 seconds or shorter.'
    );
  } finally {
    server.close();
  }
});

test('Image media skips the duration check', async () => {
  const server = http.createServer(app);
  await new Promise(resolve => server.listen(0, resolve));
  const port = server.address().port;

  try {
    const res = await fetch(`http://localhost:${port}/api/media/prepare`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: 'https://example.com/image.jpg',
        mediaType: 'IMAGE',
      }),
    });
    assert.strictEqual(res.status, 200);
  } finally {
    server.close();
  }
});
