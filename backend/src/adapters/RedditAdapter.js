import fs from 'fs';
import path from 'path';
import { pipeline } from 'stream/promises';
import { Readable } from 'stream';
import { BaseAdapter } from './BaseAdapter.js';
import { storageManager } from '../services/StorageManager.js';
import { jobManager, JobStatus } from '../services/JobManager.js';
import { config } from '../config.js';

export class RedditAdapter extends BaseAdapter {
  constructor() {
    super('Reddit');
    this.accessToken = null;
    this.tokenExpiresAt = 0;
  }

  canHandle(url) {
    if (!url) return false;
    try {
      const host = new URL(url.startsWith('http') ? url : `https://${url}`).hostname.toLowerCase();
      return host === 'reddit.com' || host.endsWith('.reddit.com') || host === 'redd.it' || host === 'v.redd.it';
    } catch {
      return false;
    }
  }

  extractPostId(rawUrl) {
    try {
      const url = new URL(rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`);
      const host = url.hostname.toLowerCase();
      const parts = url.pathname.split('/').filter(Boolean);

      if (host === 'redd.it') {
        return parts[0] || null;
      }
      const commentsIndex = parts.indexOf('comments');
      if (commentsIndex !== -1 && parts[commentsIndex + 1]) {
        return parts[commentsIndex + 1];
      }
      const galleryIndex = parts.indexOf('gallery');
      if (galleryIndex !== -1 && parts[galleryIndex + 1]) {
        return parts[galleryIndex + 1];
      }
      return null;
    } catch {
      return null;
    }
  }

  async getAccessToken() {
    if (this.accessToken && Date.now() < this.tokenExpiresAt) {
      return { token: this.accessToken };
    }

    if (!config.redditClientId) {
      return {
        error: {
          status: 'NOT_CONFIGURED',
          message: 'Reddit metadata requires REDDIT_CLIENT_ID and REDDIT_CLIENT_SECRET configured in backend/.env. Reddit Data API requires OAuth2 authentication.',
        },
      };
    }

    try {
      const authCredentials = config.redditClientSecret
        ? `${config.redditClientId}:${config.redditClientSecret}`
        : `${config.redditClientId}:`;
      const basicAuth = Buffer.from(authCredentials).toString('base64');
      const body = config.redditClientSecret
        ? 'grant_type=client_credentials'
        : 'grant_type=https%3A%2F%2Foauth.reddit.com%2Fgrants%2Finstalled_client&device_id=DO_NOT_TRACK_THIS_DEVICE';

      const res = await fetch('https://www.reddit.com/api/v1/access_token', {
        method: 'POST',
        headers: {
          'Authorization': `Basic ${basicAuth}`,
          'Content-Type': 'application/x-www-form-urlencoded',
          'User-Agent': config.redditUserAgent,
        },
        body,
      });

      if (res.status === 429) {
        return {
          error: {
            status: 'RATE_LIMITED',
            message: 'Reddit is rate-limiting authorization requests. Try again later.',
          },
        };
      }

      if (!res.ok) {
        return {
          error: {
            status: 'NOT_CONFIGURED',
            message: `Reddit rejected app credentials (HTTP ${res.status}). Verify REDDIT_CLIENT_ID and REDDIT_CLIENT_SECRET in backend/.env.`,
          },
        };
      }

      const data = await res.json();
      if (!data.access_token) {
        return {
          error: {
            status: 'NOT_CONFIGURED',
            message: 'Reddit authorization did not return an access token.',
          },
        };
      }

      const expiresIn = (data.expires_in || 3600) * 1000;
      this.accessToken = data.access_token;
      this.tokenExpiresAt = Date.now() + expiresIn - 30000;

      return { token: this.accessToken };
    } catch (err) {
      return {
        error: {
          status: 'INACCESSIBLE',
          message: `Failed to connect to Reddit authentication: ${err.message}`,
        },
      };
    }
  }

  async analyze(rawUrl) {
    const url = rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`;
    const postId = this.extractPostId(url);

    // If no credentials configured, report structured NOT_CONFIGURED without crashing
    if (!config.redditClientId) {
      return {
        sourcePlatform: 'Reddit',
        originalUrl: url,
        title: postId ? `Reddit Post (${postId})` : 'Reddit Post',
        thumbnailUrl: null,
        mediaType: 'UNKNOWN',
        durationSeconds: null,
        videoQualities: [],
        audioQualities: [],
        fileSize: null,
        status: 'NOT_CONFIGURED',
        message: 'Reddit metadata requires REDDIT_CLIENT_ID and REDDIT_CLIENT_SECRET in backend/.env. Reddit Data API requires OAuth2 authentication.',
      };
    }

    const tokenRes = await this.getAccessToken();
    if (tokenRes.error) {
      return {
        sourcePlatform: 'Reddit',
        originalUrl: url,
        title: postId ? `Reddit Post (${postId})` : 'Reddit Post',
        thumbnailUrl: null,
        mediaType: 'UNKNOWN',
        durationSeconds: null,
        videoQualities: [],
        audioQualities: [],
        fileSize: null,
        status: tokenRes.error.status,
        message: tokenRes.error.message,
      };
    }

    try {
      const endpoint = postId
        ? `https://oauth.reddit.com/comments/${postId}?raw_json=1`
        : `https://oauth.reddit.com${new URL(url).pathname}?raw_json=1`;

      const res = await fetch(endpoint, {
        headers: {
          'Authorization': `Bearer ${tokenRes.token}`,
          'User-Agent': config.redditUserAgent,
        },
      });

      if (res.status === 429) {
        return {
          sourcePlatform: 'Reddit',
          originalUrl: url,
          mediaType: 'UNKNOWN',
          status: 'RATE_LIMITED',
          message: 'Reddit is rate-limiting metadata requests. Try again later.',
        };
      }

      if (res.status === 401 || res.status === 403) {
        return {
          sourcePlatform: 'Reddit',
          originalUrl: url,
          mediaType: 'UNKNOWN',
          status: 'RESTRICTED',
          message: 'Reddit did not allow access to this post (HTTP 403). It may be private, restricted, or removed.',
        };
      }

      if (res.status === 404) {
        return {
          sourcePlatform: 'Reddit',
          originalUrl: url,
          mediaType: 'UNKNOWN',
          status: 'RESTRICTED',
          message: 'This Reddit post is deleted, removed, or no longer accessible.',
        };
      }

      if (!res.ok) {
        return {
          sourcePlatform: 'Reddit',
          originalUrl: url,
          mediaType: 'UNKNOWN',
          status: 'ERROR',
          message: `Reddit returned HTTP ${res.status}`,
        };
      }

      const data = await res.json();
      const post = data?.[0]?.data?.children?.[0]?.data;
      if (!post) {
        return {
          sourcePlatform: 'Reddit',
          originalUrl: url,
          mediaType: 'UNKNOWN',
          status: 'RESTRICTED',
          message: 'Could not extract post data from Reddit response. The post may have been removed.',
        };
      }

      const title = post.title || 'Reddit Media';
      let mediaType = 'UNKNOWN';
      let durationSeconds = null;
      let streamUrl = null;
      let thumbnailUrl = null;

      if (post.preview?.images?.[0]?.source?.url) {
        thumbnailUrl = post.preview.images[0].source.url;
      } else if (post.thumbnail && post.thumbnail.startsWith('http')) {
        thumbnailUrl = post.thumbnail;
      }

      const redditVideo = post.secure_media?.reddit_video || post.media?.reddit_video;
      if (redditVideo) {
        mediaType = 'VIDEO';
        durationSeconds = redditVideo.duration ? parseInt(redditVideo.duration, 10) : null;
        streamUrl = redditVideo.fallback_url;
      } else if (post.url && /\.(jpg|jpeg|png|gif|webp)$/i.test(post.url)) {
        mediaType = 'IMAGE';
        streamUrl = post.url;
        if (!thumbnailUrl) thumbnailUrl = post.url;
      } else if (post.is_gallery && post.gallery_data?.items?.length > 0) {
        mediaType = 'IMAGE';
        const firstMediaId = post.gallery_data.items[0].media_id;
        const mediaMeta = post.media_metadata?.[firstMediaId];
        streamUrl = mediaMeta?.s?.u || thumbnailUrl;
        if (!thumbnailUrl) thumbnailUrl = streamUrl;
      }

      if (mediaType === 'UNKNOWN') {
        return {
          sourcePlatform: 'Reddit',
          originalUrl: url,
          title,
          thumbnailUrl,
          mediaType: 'UNKNOWN',
          durationSeconds: null,
          videoQualities: [],
          audioQualities: [],
          fileSize: null,
          status: 'SUCCESS',
          message: 'Post metadata resolved. This Reddit post does not contain direct video or image media files.',
        };
      }

      const videoQualities = mediaType === 'VIDEO'
        ? [{ id: 'reddit_fallback', label: 'Reddit Video (MP4)', mimeType: 'video/mp4' }]
        : [];
      const audioQualities = mediaType === 'VIDEO'
        ? [{ id: 'reddit_audio', label: 'Audio Stream', mimeType: 'audio/mp4' }]
        : [];

      return {
        sourcePlatform: 'Reddit',
        originalUrl: url,
        title,
        thumbnailUrl,
        mediaType,
        durationSeconds,
        videoQualities,
        audioQualities,
        fileSize: null,
        status: 'SUCCESS',
        message: null,
        _streamUrl: streamUrl,
      };
    } catch (err) {
      return {
        sourcePlatform: 'Reddit',
        originalUrl: url,
        mediaType: 'UNKNOWN',
        status: 'ERROR',
        message: `Failed to analyze Reddit post: ${err.message}`,
      };
    }
  }

  async prepare(job) {
    const { jobId, url } = job;
    jobManager.updateJob(jobId, {
      status: JobStatus.IN_PROGRESS,
      progressPercent: 15,
      statusMessage: 'Analyzing Reddit post for media stream…',
    });

    const analysis = await this.analyze(url);
    if (analysis.status !== 'SUCCESS' || !analysis._streamUrl) {
      throw new Error(analysis.message || 'No direct media stream found for this Reddit post');
    }

    if (analysis.mediaType === 'VIDEO' && analysis.durationSeconds && analysis.durationSeconds > config.maxVideoDurationSeconds) {
      throw new Error('Video exceeds the 3-minute limit. Please choose a video that is 180 seconds or shorter.');
    }

    jobManager.updateJob(jobId, {
      progressPercent: 35,
      statusMessage: 'Downloading media from Reddit…',
      title: analysis.title,
    });

    const streamUrl = analysis._streamUrl;
    const res = await fetch(streamUrl, {
      headers: { 'User-Agent': config.redditUserAgent },
    });
    if (!res.ok) {
      throw new Error(`Failed to download media stream (HTTP ${res.status})`);
    }

    const contentType = res.headers.get('content-type') || (analysis.mediaType === 'VIDEO' ? 'video/mp4' : 'image/jpeg');
    const ext = analysis.mediaType === 'VIDEO' ? '.mp4' : '.jpg';
    const filename = `reddit_${jobId.slice(0, 8)}${ext}`;
    const targetPath = path.join(storageManager.storageDir, `${jobId}_${filename}`);
    const partPath = `${targetPath}.part`;

    const writeStream = fs.createWriteStream(partPath);
    await pipeline(Readable.fromWeb(res.body), writeStream);
    fs.renameSync(partPath, targetPath);

    const stats = fs.statSync(targetPath);

    jobManager.updateJob(jobId, {
      progressPercent: 90,
      statusMessage: 'Finalizing Reddit media…',
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
