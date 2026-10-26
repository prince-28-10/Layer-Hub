import { DirectMediaAdapter } from './DirectMediaAdapter.js';
import { RedditAdapter } from './RedditAdapter.js';
import { YouTubeAdapter } from './YouTubeAdapter.js';
import { InstagramAdapter } from './InstagramAdapter.js';
import { XAdapter } from './XAdapter.js';
import { FacebookAdapter } from './FacebookAdapter.js';
import { TelegramAdapter } from './TelegramAdapter.js';
import { WhatsAppAdapter } from './WhatsAppAdapter.js';
import { SourceClassifier } from './SourceClassifier.js';
import { config } from '../config.js';

const adapters = [
  new YouTubeAdapter(),
  new RedditAdapter(),
  new InstagramAdapter(),
  new FacebookAdapter(),
  new XAdapter(),
  new TelegramAdapter(),
  new WhatsAppAdapter(),
  new DirectMediaAdapter(),
];

export function getAdapterForUrl(url) {
  for (const adapter of adapters) {
    if (adapter.canHandle(url)) {
      return adapter;
    }
  }
  return null;
}

export async function analyzeMedia(rawUrl) {
  const classification = SourceClassifier.classify(rawUrl);
  if (!classification.isSupported) {
    return {
      sourcePlatform: classification.platform,
      originalUrl: rawUrl,
      mediaType: 'UNKNOWN',
      status: 'ERROR',
      message: classification.error || 'Unsupported or invalid URL',
    };
  }

  const adapter = getAdapterForUrl(rawUrl);
  if (!adapter) {
    return {
      sourcePlatform: classification.platform,
      originalUrl: rawUrl,
      mediaType: 'UNKNOWN',
      status: 'ERROR',
      message: `No adapter available for platform: ${classification.platform}`,
    };
  }

  const analysis = await adapter.analyze(rawUrl);
  // Ensure classification platform is respected
  if (!analysis.sourcePlatform || analysis.sourcePlatform === 'Unknown') {
    analysis.sourcePlatform = classification.platform;
  }
  return analysis;
}

export async function prepareMediaJob(job) {
  const { url, mediaType } = job;
  const adapter = getAdapterForUrl(url);
  if (!adapter) {
    throw new Error('No compatible adapter found for URL');
  }

  // Pre-check duration before retrieval if already known
  const analysis = await adapter.analyze(url);
  if (analysis.status === 'SUCCESS' && analysis.mediaType === 'VIDEO') {
    if (analysis.durationSeconds && analysis.durationSeconds > config.maxVideoDurationSeconds) {
      throw new Error('Video exceeds the 3-minute limit. Please choose a video that is 180 seconds or shorter.');
    }
  }

  return await adapter.prepare(job);
}
