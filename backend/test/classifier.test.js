import test from 'node:test';
import assert from 'node:assert';
import { SourceClassifier } from '../src/adapters/SourceClassifier.js';

test('SourceClassifier detects all supported platforms', () => {
  assert.strictEqual(SourceClassifier.classify('https://www.youtube.com/watch?v=abc').platform, 'YouTube');
  assert.strictEqual(SourceClassifier.classify('https://youtu.be/abc').platform, 'YouTube');
  assert.strictEqual(SourceClassifier.classify('https://reddit.com/r/android/comments/123').platform, 'Reddit');
  assert.strictEqual(SourceClassifier.classify('https://redd.it/xyz').platform, 'Reddit');
  assert.strictEqual(SourceClassifier.classify('https://instagram.com/reel/123').platform, 'Instagram');
  assert.strictEqual(SourceClassifier.classify('https://facebook.com/story.php?id=1').platform, 'Facebook');
  assert.strictEqual(SourceClassifier.classify('https://x.com/user/status/123').platform, 'X');
  assert.strictEqual(SourceClassifier.classify('https://twitter.com/user/status/123').platform, 'X');
  assert.strictEqual(SourceClassifier.classify('https://t.me/channel/123').platform, 'Telegram');
  assert.strictEqual(SourceClassifier.classify('https://example.com/video.mp4').platform, 'Direct Media');
});

test('SourceClassifier rejects empty or invalid URLs', () => {
  assert.strictEqual(SourceClassifier.classify('').isSupported, false);
  assert.strictEqual(SourceClassifier.classify(null).isSupported, false);
});
