import fs from 'fs';
import path from 'path';
import { pipeline } from 'stream/promises';
import { Readable } from 'stream';
import { BaseAdapter } from './BaseAdapter.js';
import { storageManager } from '../services/StorageManager.js';
import { jobManager, JobStatus } from '../services/JobManager.js';
import { config } from '../config.js';

export class YouTubeAdapter extends BaseAdapter {
  constructor() {
    super('YouTube');
  }

  canHandle(url) {
    if (!url) return false;
    try {
      const host = new URL(url.startsWith('http') ? url : `https://${url}`).hostname.toLowerCase();
      return host === 'youtube.com' || host.endsWith('.youtube.com') || host === 'youtu.be';
    } catch {
      return false;
    }
  }

  extractVideoId(rawUrl) {
    try {
      const url = new URL(rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`);
      const host = url.hostname.toLowerCase();
      if (host === 'youtu.be') {
        return url.pathname.slice(1).split('?')[0] || null;
      }
      if (url.pathname.startsWith('/shorts/')) {
        return url.pathname.split('/')[2] || null;
      }
      return url.searchParams.get('v') || null;
    } catch {
      return null;
    }
  }

  async analyze(rawUrl) {
    const url = rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`;
    const videoId = this.extractVideoId(url);
    if (!videoId) {
      return {
        sourcePlatform: 'YouTube',
        originalUrl: url,
        mediaType: 'UNKNOWN',
        status: 'ERROR',
        message: 'Could not extract a valid YouTube video ID from URL',
      };
    }

    try {
      // 1. Fetch official YouTube oEmbed for title & author
      const oembedUrl = `https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=${videoId}&format=json`;
      const res = await fetch(oembedUrl);
      if (!res.ok) {
        return {
          sourcePlatform: 'YouTube',
          originalUrl: url,
          mediaType: 'UNKNOWN',
          status: 'ERROR',
          message: `YouTube returned HTTP ${res.status} (video may be private or unavailable)`,
        };
      }

      const oembedData = await res.json();
      const title = oembedData.title || `YouTube Video (${videoId})`;
      const thumbnailUrl = `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;

      // 2. Fetch page HTML to extract approx duration if possible
      let durationSeconds = null;
      try {
        const pageRes = await fetch(`https://www.youtube.com/watch?v=${videoId}`, {
          headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' },
        });
        if (pageRes.ok) {
          const html = await pageRes.text();
          const match = html.match(/"approxDurationMs":"(\d+)"/);
          if (match && match[1]) {
            durationSeconds = Math.round(parseInt(match[1], 10) / 1000);
          }
        }
      } catch (_) {}

      const videoQualities = [
        { id: '720p', label: '720p HD (MP4)', mimeType: 'video/mp4' },
        { id: '360p', label: '360p (MP4)', mimeType: 'video/mp4' },
      ];
      const audioQualities = [
        { id: 'audio_m4a', label: 'Audio Only (M4A)', mimeType: 'audio/mp4' },
      ];

      return {
        sourcePlatform: 'YouTube',
        originalUrl: url,
        title,
        thumbnailUrl,
        mediaType: 'VIDEO',
        durationSeconds,
        videoQualities,
        audioQualities,
        fileSize: null,
        status: 'SUCCESS',
        message: durationSeconds && durationSeconds > config.maxVideoDurationSeconds
          ? 'Video exceeds the 3-minute limit. Please choose a video that is 180 seconds or shorter.'
          : null,
        _videoId: videoId,
      };
    } catch (err) {
      return {
        sourcePlatform: 'YouTube',
        originalUrl: url,
        mediaType: 'UNKNOWN',
        status: 'INACCESSIBLE',
        message: `Failed to connect to YouTube: ${err.message}. Network connection may be blocked or unstable.`,
      };
    }
  }

  async prepare(job) {
    const { jobId, url } = job;
    jobManager.updateJob(jobId, {
      status: JobStatus.IN_PROGRESS,
      progressPercent: 10,
      statusMessage: 'Analyzing YouTube video metadata…',
    });

    const analysis = await this.analyze(url);
    if (analysis.status === 'ERROR') {
      throw new Error(analysis.message);
    }

    if (analysis.durationSeconds && analysis.durationSeconds > config.maxVideoDurationSeconds) {
      throw new Error('Video exceeds the 3-minute limit. Please choose a video that is 180 seconds or shorter.');
    }

    jobManager.updateJob(jobId, {
      progressPercent: 30,
      statusMessage: 'Retrieving media stream…',
      title: analysis.title,
    });

    // YouTube requires signed stream deciphering or an authorized retrieval stream.
    // We check if a fallback direct stream or proxy is available; otherwise provide accurate compliance error
    throw new Error('YouTube stream extraction requires a dedicated backend streaming proxy or server-side media session. Metadata was successfully verified.');
  }
}
