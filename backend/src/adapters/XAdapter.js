import fs from 'fs';
import path from 'path';
import { pipeline } from 'stream/promises';
import { Readable } from 'stream';
import { BaseAdapter } from './BaseAdapter.js';
import { storageManager } from '../services/StorageManager.js';
import { jobManager, JobStatus } from '../services/JobManager.js';
import { config } from '../config.js';

export class XAdapter extends BaseAdapter {
  constructor() {
    super('X');
  }

  canHandle(url) {
    if (!url) return false;
    try {
      const host = new URL(url.startsWith('http') ? url : `https://${url}`).hostname.toLowerCase();
      return host === 'x.com' || host === 'twitter.com' || host.endsWith('.twitter.com') || host === 't.co';
    } catch {
      return false;
    }
  }

  extractTweetId(rawUrl) {
    try {
      const url = new URL(rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`);
      const match = url.pathname.match(/status\/(\d+)/i);
      return match ? match[1] : null;
    } catch {
      return null;
    }
  }

  async analyze(rawUrl) {
    const url = rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`;
    const tweetId = this.extractTweetId(url);

    try {
      // 1. Query official Twitter / X oEmbed endpoint (works without credentials)
      const oembedUrl = `https://publish.twitter.com/oembed?url=${encodeURIComponent(url)}&omit_script=true`;
      const res = await fetch(oembedUrl);

      if (res.status === 404) {
        return {
          sourcePlatform: 'X',
          originalUrl: url,
          title: tweetId ? `Post on X (${tweetId})` : 'Post on X',
          thumbnailUrl: null,
          mediaType: 'UNKNOWN',
          durationSeconds: null,
          videoQualities: [],
          audioQualities: [],
          fileSize: null,
          status: 'RESTRICTED',
          message: 'This post on X was not found or is private/deleted.',
        };
      }

      if (res.status === 429) {
        return {
          sourcePlatform: 'X',
          originalUrl: url,
          title: 'Post on X',
          thumbnailUrl: null,
          mediaType: 'UNKNOWN',
          durationSeconds: null,
          videoQualities: [],
          audioQualities: [],
          fileSize: null,
          status: 'RATE_LIMITED',
          message: 'X is rate-limiting oEmbed requests. Try again later.',
        };
      }

      let authorName = 'Post on X';
      let title = 'Post on X';

      if (res.ok) {
        const data = await res.json();
        authorName = data.author_name || authorName;
        const rawHtml = data.html || '';
        const textMatch = rawHtml.match(/<p[^>]*>(.*?)<\/p>/s);
        if (textMatch && textMatch[1]) {
          title = textMatch[1]
            .replace(/<br\s*[\/]?>/gi, ' ')
            .replace(/<[^>]+>/g, '')
            .replace(/\s+/g, ' ')
            .trim();
        } else {
          title = `${authorName}'s Post on X`;
        }
      }

      // 2. If official Twitter API v2 Bearer Token is available, fetch media details
      if (config.twitterBearerToken && tweetId) {
        try {
          const apiUrl = `https://api.twitter.com/2/tweets/${tweetId}?expansions=attachments.media_keys&media.fields=duration_ms,preview_image_url,type,variants,url`;
          const apiRes = await fetch(apiUrl, {
            headers: {
              'Authorization': `Bearer ${config.twitterBearerToken}`,
            },
          });

          if (apiRes.ok) {
            const apiData = await apiRes.json();
            const mediaList = apiData.includes?.media || [];
            if (mediaList.length > 0) {
              const primaryMedia = mediaList[0];
              const isVideo = primaryMedia.type === 'video' || primaryMedia.type === 'animated_gif';
              const mediaType = isVideo ? 'VIDEO' : (primaryMedia.type === 'photo' ? 'IMAGE' : 'UNKNOWN');
              const durationSeconds = primaryMedia.duration_ms ? Math.round(primaryMedia.duration_ms / 1000) : null;
              const thumbnailUrl = primaryMedia.preview_image_url || primaryMedia.url || null;

              // Find best MP4 variant for video
              let streamUrl = null;
              if (isVideo && primaryMedia.variants) {
                const mp4Variants = primaryMedia.variants
                  .filter(v => v.content_type === 'video/mp4' && v.bit_rate)
                  .sort((a, b) => (b.bit_rate || 0) - (a.bit_rate || 0));
                streamUrl = mp4Variants[0]?.url || primaryMedia.variants[0]?.url;
              } else if (mediaType === 'IMAGE') {
                streamUrl = primaryMedia.url;
              }

              return {
                sourcePlatform: 'X',
                originalUrl: url,
                title,
                thumbnailUrl,
                mediaType,
                durationSeconds,
                videoQualities: isVideo ? [{ id: 'x_high', label: 'X Video (MP4)', mimeType: 'video/mp4' }] : [],
                audioQualities: isVideo ? [{ id: 'x_audio', label: 'Audio Stream', mimeType: 'audio/mp4' }] : [],
                fileSize: null,
                status: 'SUCCESS',
                message: null,
                _streamUrl: streamUrl,
              };
            }
          }
        } catch (_) {}
      }

      // If no Twitter API credentials, return official verified metadata from oEmbed
      return {
        sourcePlatform: 'X',
        originalUrl: url,
        title,
        thumbnailUrl: null,
        mediaType: 'UNKNOWN',
        durationSeconds: null,
        videoQualities: [],
        audioQualities: [],
        fileSize: null,
        status: 'SUCCESS',
        message: 'Post metadata retrieved via official X oEmbed. Media stream extraction requires official X API credentials (TWITTER_BEARER_TOKEN in backend/.env).',
      };
    } catch (err) {
      return {
        sourcePlatform: 'X',
        originalUrl: url,
        mediaType: 'UNKNOWN',
        status: 'ERROR',
        message: `Failed to analyze X URL: ${err.message}`,
      };
    }
  }

  async prepare(job) {
    const { jobId, url } = job;
    jobManager.updateJob(jobId, {
      status: JobStatus.IN_PROGRESS,
      progressPercent: 15,
      statusMessage: 'Inspecting X media stream…',
    });

    const analysis = await this.analyze(url);
    if (!config.twitterBearerToken || !analysis._streamUrl) {
      throw new Error('X media stream extraction requires official X API credentials (TWITTER_BEARER_TOKEN in backend/.env). Public scraping without authorization is restricted by X Developer Policy.');
    }

    if (analysis.mediaType === 'VIDEO' && analysis.durationSeconds && analysis.durationSeconds > config.maxVideoDurationSeconds) {
      throw new Error('Video exceeds the 3-minute limit. Please choose a video that is 180 seconds or shorter.');
    }

    jobManager.updateJob(jobId, {
      progressPercent: 40,
      statusMessage: 'Downloading media stream…',
      title: analysis.title,
    });

    const streamUrl = analysis._streamUrl;
    const res = await fetch(streamUrl);
    if (!res.ok) {
      throw new Error(`Failed to retrieve media stream from X (HTTP ${res.status})`);
    }

    const contentType = res.headers.get('content-type') || (analysis.mediaType === 'VIDEO' ? 'video/mp4' : 'image/jpeg');
    const ext = analysis.mediaType === 'VIDEO' ? '.mp4' : '.jpg';
    const filename = `x_${jobId.slice(0, 8)}${ext}`;
    const targetPath = path.join(storageManager.storageDir, `${jobId}_${filename}`);
    const partPath = `${targetPath}.part`;

    const writeStream = fs.createWriteStream(partPath);
    await pipeline(Readable.fromWeb(res.body), writeStream);
    fs.renameSync(partPath, targetPath);

    const stats = fs.statSync(targetPath);

    jobManager.updateJob(jobId, {
      progressPercent: 90,
      statusMessage: 'Finalizing media file…',
    });

    const record = storageManager.registerFile({
      filePath: targetPath,
      fileName: filename,
      mimeType: contentType,
      sizeBytes: stats.size,
    });

    jobManager.updateJob(jobId, {
      status: JobStatus.COMPLETED,
      progressPercent: 100,
      statusMessage: 'Media ready',
      mediaId: record.mediaId,
      downloadUrl: `/api/media/download/${record.mediaId}`,
      title: analysis.title,
      mimeType: record.mimeType,
    });

    return record;
  }
}
