import test from 'node:test';
import assert from 'node:assert';
import fs from 'fs';
import path from 'path';
import { jobManager, JobStatus } from '../src/services/JobManager.js';
import { storageManager } from '../src/services/StorageManager.js';

test('JobManager creates and tracks jobs', () => {
  const job = jobManager.createJob({
    url: 'https://example.com/test.mp4',
    mediaType: 'VIDEO',
    qualityId: '720p',
  });

  assert.ok(job.jobId);
  assert.strictEqual(job.status, JobStatus.PENDING);

  jobManager.updateJob(job.jobId, {
    status: JobStatus.IN_PROGRESS,
    progressPercent: 50,
  });

  const retrieved = jobManager.getJob(job.jobId);
  assert.strictEqual(retrieved.status, JobStatus.IN_PROGRESS);
  assert.strictEqual(retrieved.progressPercent, 50);
});

test('StorageManager registers and cleans expired files', () => {
  const testFile = path.join(storageManager.storageDir, 'test_sample.txt');
  fs.writeFileSync(testFile, 'sample content');

  // Register with 1ms TTL so it expires immediately
  const record = storageManager.registerFile({
    filePath: testFile,
    fileName: 'test_sample.txt',
    mimeType: 'text/plain',
    sizeBytes: 14,
    ttlMs: 1,
  });

  assert.ok(record.mediaId);

  // Wait 10ms for expiration
  return new Promise((resolve) => {
    setTimeout(() => {
      const fetched = storageManager.getFile(record.mediaId);
      assert.strictEqual(fetched, null);
      assert.strictEqual(fs.existsSync(testFile), false);
      resolve();
    }, 15);
  });
});
