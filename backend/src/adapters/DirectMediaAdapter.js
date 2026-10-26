import fs from 'fs';
import path from 'path';
import { pipeline } from 'stream/promises';
import { BaseAdapter } from './BaseAdapter.js';
import { storageManager } from '../services/StorageManager.js';
import { jobManager, JobStatus } from '../services/JobManager.js';
import { config } from '../config.js';

function probeMp4Buffer(buffer) {
  let offset = 0;
  while (offset + 8 <= buffer.length) {
    const size = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    if (size === 0) break;
    const boxSize = size === 1 && offset + 16 <= buffer.length
      ? Number(buffer.readBigUInt64BE(offset + 8))
      : size;
    const headerSize = size === 1 ? 16 : 8;

    if (type === 'moov') {
      let sub = offset + headerSize;
      const moovEnd = Math.min(offset + boxSize, buffer.length);
      while (sub + 8 <= moovEnd) {
        const subSize = buffer.readUInt32BE(sub);
        const subType = buffer.toString('ascii', sub + 4, sub + 8);
        if (subSize === 0) break;
        if (subType === 'mvhd' && sub + 8 + 24 <= buffer.length) {
          const version = buffer.readUInt8(sub + 8);
          let timescale = 0;
          let duration = 0;
          if (version === 0) {
            timescale = buffer.readUInt32BE(sub + 8 + 12);
            duration = buffer.readUInt32BE(sub + 8 + 16);
          } else if (sub + 8 + 32 <= buffer.length) {
            timescale = buffer.readUInt32BE(sub + 8 + 20);
            duration = Number(buffer.readBigUInt64BE(sub + 8 + 24));
          }
          if (timescale > 0 && duration > 0) {
            return duration / timescale;
          }
        }
        sub += subSize;
      }
    }
    offset += boxSize;
  }
  return null;
}

async function probeRemoteVideoDuration(url) {
  try {
    // 1. Try first 64KB
    const headRes = await fetch(url, { headers: { Range: 'bytes=0-65535' } });
    if (headRes.ok || headRes.status === 206) {
      const buf = Buffer.from(await headRes.arrayBuffer());
      const d = probeMp4Buffer(buf);
      if (d !== null) return d;
    }

    // 2. Try last 64KB (if moov is at end of file)
    const tailRes = await fetch(url, { headers: { Range: 'bytes=-65536' } });
    if (tailRes.ok || tailRes.status === 206) {
      const buf = Buffer.from(await tailRes.arrayBuffer());
      const d = probeMp4Buffer(buf);
      if (d !== null) return d;
    }
  } catch (_) {}
  return null;
}

export class DirectMediaAdapter extends BaseAdapter {
  constructor() {
    super('Direct Media');
  }

  canHandle(url) {
    if (!url || typeof url !== 'string') return false;
    try {
      const parsed = new URL(url.startsWith('http') ? url : `https://${url}`);
      const host = parsed.hostname.toLowerCase();
      if (['youtube.com', 'youtu.be', 'reddit.com', 'redd.it', 'instagram.com', 'twitter.com', 'x.com', 'facebook.com', 'fb.watch', 't.me'].some(h => host === h || host.endsWith(`.${h}`))) {
        return false;
      }
      return parsed.protocol === 'http:' || parsed.protocol === 'https:';
    } catch {
      return false;
    }
  }

  async analyze(rawUrl) {
    const url = rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`;
    try {
      let response = await fetch(url, { method: 'HEAD', redirect: 'follow' });
      if (!response.ok || !response.headers.get('content-type')) {
        response = await fetch(url, {
          method: 'GET',
          headers: { Range: 'bytes=0-1024' },
          redirect: 'follow',
        });
      }

      if (!response.ok && response.status !== 206) {
        return {
          sourcePlatform: 'Direct Media',
          originalUrl: url,
          mediaType: 'UNKNOWN',
          status: 'ERROR',
          canPrepare: false,
          message: `The server responded with HTTP status ${response.status}`,
        };
      }

      const contentType = (response.headers.get('content-type') || '').toLowerCase().split(';')[0].trim();
      const contentLength = parseInt(response.headers.get('content-length') || '0', 10);
      const disposition = response.headers.get('content-disposition') || '';

      let filename = 'downloaded_media';
      const filenameMatch = disposition.match(/filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?/i);
      if (filenameMatch && filenameMatch[1]) {
        filename = decodeURIComponent(filenameMatch[1]);
      } else {
        const urlPath = new URL(url).pathname;
        const base = path.basename(urlPath);
        if (base && base.includes('.')) {
          filename = decodeURIComponent(base);
        }
      }

      let mediaType = 'UNKNOWN';
      let resolvedMime = contentType;
      if (contentType.startsWith('video/')) {
        mediaType = 'VIDEO';
      } else if (contentType.startsWith('audio/')) {
        mediaType = 'AUDIO';
      } else if (contentType.startsWith('image/')) {
        mediaType = 'IMAGE';
      } else if (contentType === 'application/octet-stream' || contentType === 'binary/octet-stream' || !contentType) {
        const lowerName = filename.toLowerCase();
        if (/\.(mp4|webm|mov|mkv|avi|m4v)$/.test(lowerName)) {
          mediaType = 'VIDEO';
          resolvedMime = 'video/mp4';
        } else if (/\.(mp3|wav|m4a|aac|flac|ogg)$/.test(lowerName)) {
          mediaType = 'AUDIO';
          resolvedMime = 'audio/mpeg';
        } else if (/\.(jpg|jpeg|png|webp|gif)$/.test(lowerName)) {
          mediaType = 'IMAGE';
          resolvedMime = 'image/jpeg';
        }
      }

      if (mediaType === 'UNKNOWN') {
        return {
          sourcePlatform: 'Direct Media',
          originalUrl: url,
          title: filename,
          mediaType: 'UNKNOWN',
          status: 'ERROR',
          canPrepare: false,
          message: `Unsupported or non-media content type (${contentType || 'none'})`,
        };
      }

      // Probe duration for video
      let durationSeconds = null;
      if (mediaType === 'VIDEO') {
        const probed = await probeRemoteVideoVideoDuration(url);
        if (probed !== null) {
          durationSeconds = Math.round(probed);
        }
      }

      // Check duration limit (180s)
      if (mediaType === 'VIDEO' && durationSeconds !== null && durationSeconds > config.maxVideoDurationSeconds) {
        return {
          sourcePlatform: 'Direct Media',
          originalUrl: url,
          title: filename,
          thumbnailUrl: null,
          mediaType,
          durationSeconds,
          videoQualities: [],
          audioQualities: [],
          fileSize: contentLength > 0 ? contentLength : null,
          status: 'ERROR',
          canPrepare: false,
          message: 'Video exceeds the 3-minute limit. Please choose a video that is 180 seconds or shorter.',
        };
      }

      const videoQualities = mediaType === 'VIDEO'
        ? [{ id: 'original', label: 'Original Quality', mimeType: resolvedMime }]
        : [];
      const audioQualities = (mediaType === 'VIDEO' || mediaType === 'AUDIO')
        ? [{ id: 'original_audio', label: 'Original Audio', mimeType: resolvedMime.startsWith('audio/') ? resolvedMime : 'audio/mp4' }]
        : [];

      return {
        sourcePlatform: 'Direct Media',
        originalUrl: url,
        title: filename,
        thumbnailUrl: mediaType === 'IMAGE' ? url : null,
        mediaType,
        durationSeconds,
        videoQualities,
        audioQualities,
        fileSize: contentLength > 0 ? contentLength : null,
        status: 'SUCCESS',
        canPrepare: true,
        message: null,
      };
    } catch (err) {
      return {
        sourcePlatform: 'Direct Media',
        originalUrl: url,
        mediaType: 'UNKNOWN',
        status: 'ERROR',
        canPrepare: false,
        message: `Failed to inspect direct URL: ${err.message}`,
      };
    }
  }

  async prepare(job) {
    const { jobId, url } = job;
    jobManager.updateJob(jobId, {
      status: JobStatus.IN_PROGRESS,
      progressPercent: 10,
      statusMessage: 'Connecting to direct media host…',
    });

    try {
      const response = await fetch(url, { redirect: 'follow' });
      if (!response.ok) {
        throw new Error(`Server returned HTTP ${response.status}`);
      }

      const contentType = (response.headers.get('content-type') || '').toLowerCase().split(';')[0].trim();
      const disposition = response.headers.get('content-disposition') || '';

      let ext = '.bin';
      if (contentType.includes('mp4')) ext = '.mp4';
      else if (contentType.includes('webm')) ext = '.webm';
      else if (contentType.includes('mp3')) ext = '.mp3';
      else if (contentType.includes('wav')) ext = '.wav';
      else if (contentType.includes('jpeg') || contentType.includes('jpg')) ext = '.jpg';
      else if (contentType.includes('png')) ext = '.png';
      else if (contentType.includes('gif')) ext = '.gif';

      let filename = `media_${jobId.slice(0, 8)}${ext}`;
      const filenameMatch = disposition.match(/filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?/i);
      if (filenameMatch && filenameMatch[1]) {
        filename = decodeURIComponent(filenameMatch[1]);
      } else {
        const urlBase = path.basename(new URL(url).pathname);
        if (urlBase && urlBase.includes('.')) {
          filename = decodeURIComponent(urlBase);
        }
      }

      const targetPath = path.join(storageManager.storageDir, `${jobId}_${filename}`);
      const partPath = `${targetPath}.part`;

      jobManager.updateJob(jobId, {
        progressPercent: 30,
        statusMessage: 'Downloading media stream…',
        title: filename,
      });

      const writeStream = fs.createWriteStream(partPath);
      if (response.body) {
        const { Readable } = await import('stream');
        const nodeReadable = Readable.fromWeb(response.body);
        await pipeline(nodeReadable, writeStream);
      } else {
        throw new Error('No response body returned from server');
      }

      // Read completed file and verify duration if video
      const stats = fs.statSync(partPath);
      if (stats.size === 0) {
        fs.unlinkSync(partPath);
        throw new Error('The downloaded media file was empty.');
      }

      // Check on-disk duration for MP4
      if (filename.endsWith('.mp4') || contentType.includes('mp4')) {
        try {
          const fileBuffer = fs.readFileSync(partPath);
          const probed = probeMp4Buffer(fileBuffer);
          if (probed !== null && probed > config.maxVideoDurationSeconds) {
            fs.unlinkSync(partPath);
            throw new Error('Video exceeds the 3-minute limit. Please choose a video that is 180 seconds or shorter.');
          }
        } catch (e) {
          if (e.message.includes('3-minute limit')) {
            throw e;
          }
        }
      }

      fs.renameSync(partPath, targetPath);

      jobManager.updateJob(jobId, {
        progressPercent: 90,
        statusMessage: 'Validating prepared media…',
      });

      const mediaRecord = storageManager.registerFile({
        filePath: targetPath,
        fileName: filename,
        mimeType: contentType || 'application/octet-stream',
        sizeBytes: stats.size,
      });

      jobManager.updateJob(jobId, {
        status: JobStatus.COMPLETED,
        progressPercent: 100,
        statusMessage: 'Media ready',
        mediaId: mediaRecord.mediaId,
        downloadUrl: `/api/media/download/${mediaRecord.mediaId}`,
        title: filename,
        mimeType: mediaRecord.mimeType,
      });

      return mediaRecord;
    } catch (err) {
      jobManager.updateJob(jobId, {
        status: JobStatus.FAILED,
        statusMessage: 'Preparation failed',
        error: err.message,
      });
      throw err;
    }
  }
}
