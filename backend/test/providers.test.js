import test from 'node:test';
import assert from 'node:assert/strict';
import { analyzeMedia } from '../src/adapters/index.js';
import { SourceClassifier } from '../src/adapters/SourceClassifier.js';
import { InstagramAdapter } from '../src/adapters/InstagramAdapter.js';
import { FacebookAdapter } from '../src/adapters/FacebookAdapter.js';
import { RedditAdapter } from '../src/adapters/RedditAdapter.js';
import { XAdapter } from '../src/adapters/XAdapter.js';
import { WhatsAppAdapter } from '../src/adapters/WhatsAppAdapter.js';

test('Provider URL classification recognizes all platforms independently of credentials', () => {
  assert.equal(SourceClassifier.classify('https://instagram.com/p/C4abc123').platform, 'Instagram');
  assert.equal(SourceClassifier.classify('https://www.facebook.com/watch/?v=12345').platform, 'Facebook');
  assert.equal(SourceClassifier.classify('https://www.reddit.com/r/technology/comments/123456/title').platform, 'Reddit');
  assert.equal(SourceClassifier.classify('https://x.com/OpenAI/status/123456789').platform, 'X');
  assert.equal(SourceClassifier.classify('https://twitter.com/NASA/status/987654321').platform, 'X');
  assert.equal(SourceClassifier.classify('https://wa.me/123456789').platform, 'WhatsApp');
});

test('Instagram returns NOT_CONFIGURED without Meta credentials', async () => {
  const result = await analyzeMedia('https://www.instagram.com/reel/C123456789/');
  assert.equal(result.sourcePlatform, 'Instagram');
  assert.equal(result.status, 'NOT_CONFIGURED');
  assert.equal(result.mediaType, 'UNKNOWN');
  assert.ok(result.message.includes('META_APP_ID'));
  assert.ok(result.title.includes('C123456789'));
});

test('Facebook returns NOT_CONFIGURED without Meta credentials', async () => {
  const result = await analyzeMedia('https://www.facebook.com/watch/?v=999888777');
  assert.equal(result.sourcePlatform, 'Facebook');
  assert.equal(result.status, 'NOT_CONFIGURED');
  assert.equal(result.mediaType, 'UNKNOWN');
  assert.ok(result.message.includes('META_APP_ID'));
});

test('Reddit returns NOT_CONFIGURED without backend credentials', async () => {
  const result = await analyzeMedia('https://www.reddit.com/r/android/comments/abc123z/interesting_post/');
  assert.equal(result.sourcePlatform, 'Reddit');
  assert.equal(result.status, 'NOT_CONFIGURED');
  assert.equal(result.mediaType, 'UNKNOWN');
  assert.ok(result.message.includes('REDDIT_CLIENT_ID'));
});

test('X retrieves real metadata via official public oEmbed without credentials', async () => {
  // Test with a known official public tweet (e.g. Twitter / X official tweet)
  const result = await analyzeMedia('https://twitter.com/Twitter/status/20');
  assert.equal(result.sourcePlatform, 'X');
  assert.equal(result.status, 'SUCCESS');
  assert.ok(result.title && result.title.length > 0);
  assert.equal(result.mediaType, 'UNKNOWN');
  assert.ok(result.message.includes('oEmbed'));
});

test('WhatsApp returns clear UNSUPPORTED message directing user to share file directly', async () => {
  const result = await analyzeMedia('https://wa.me/15551234567');
  assert.equal(result.sourcePlatform, 'WhatsApp');
  assert.equal(result.status, 'UNSUPPORTED');
  assert.ok(result.message.includes('share'));
});

test('Adapters reject preparation with lawful explanations when stream download is unsupported', async () => {
  const insta = new InstagramAdapter();
  await assert.rejects(
    async () => insta.prepare({ jobId: '1', url: 'https://instagram.com/p/test' }),
    /Platform Terms/
  );

  const fb = new FacebookAdapter();
  await assert.rejects(
    async () => fb.prepare({ jobId: '2', url: 'https://facebook.com/watch/?v=1' }),
    /Platform Terms/
  );

  const x = new XAdapter();
  await assert.rejects(
    async () => x.prepare({ jobId: '3', url: 'https://x.com/user/status/1' }),
    /TWITTER_BEARER_TOKEN/
  );

  const wa = new WhatsAppAdapter();
  await assert.rejects(
    async () => wa.prepare({ jobId: '4', url: 'https://wa.me/123' }),
    /directly/
  );
});
