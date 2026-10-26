import express from 'express';
import fs from 'fs';
import { analyzeMedia, prepareMediaJob } from '../adapters/index.js';
import { jobManager } from '../services/JobManager.js';
import { storageManager } from '../services/StorageManager.js';
import { config } from '../config.js';

const router = express.Router();

// POST /api/media/analyze
router.post('/analyze', async (req, res) => {
  const { url } = req.body || {};
  if (!url || typeof url !== 'string' || !url.trim()) {
    return res.status(400).json({
      status: 'ERROR',
      message: 'The "url" field is required and must be a valid URL string.',
    });
  }

  try {
    const result = await analyzeMedia(url.trim());
    return res.json(result);
  } catch (err) {
    console.error('Error during media analysis:', err);
    return res.status(500).json({
      sourcePlatform: 'Unknown',
      originalUrl: url,
      mediaType: 'UNKNOWN',
      status: 'ERROR',
      message: `Media analysis encountered an error: ${err.message}`,
    });
  }
});

// POST /api/media/prepare
router.post('/prepare', async (req, res) => {
  const { url, mediaType, qualityId, durationSeconds } = req.body || {};
  if (!url || typeof url !== 'string') {
    return res.status(400).json({
      status: 'ERROR',
      message: 'The "url" parameter is required.',
    });
  }

  // Strict 180-second rule for video
  const duration = typeof durationSeconds === 'number' ? durationSeconds : null;
  if (mediaType === 'VIDEO' && duration !== null && duration > config.maxVideoDurationSeconds) {
    return res.status(400).json({
      status: 'ERROR',
      message: 'Video exceeds the 3-minute limit. Please choose a video that is 180 seconds or shorter.',
    });
  }

  const job = jobManager.createJob({
    url: url.trim(),
    mediaType: mediaType || 'VIDEO',
    qualityId: qualityId || 'original',
  });

  // Execute preparation asynchronously
  (async () => {
    try {
      await prepareMediaJob(job);
    } catch (err) {
      console.error(`Preparation failed for job ${job.jobId}:`, err.message);
      jobManager.updateJob(job.jobId, {
        status: 'FAILED',
        statusMessage: 'Preparation failed',
        error: err.message,
      });
    }
  })();

  return res.json({
    jobId: job.jobId,
    status: job.status,
    message: 'Media preparation has been initiated.',
  });
});

// GET /api/media/jobs/:jobId
router.get('/jobs/:jobId', (req, res) => {
  const { jobId } = req.params;
  const job = jobManager.getJob(jobId);
  if (!job) {
    return res.status(404).json({
      status: 'ERROR',
      message: `Job ${jobId} not found.`,
    });
  }

  return res.json({
    jobId: job.jobId,
    status: job.status,
    progressPercent: job.progressPercent,
    statusMessage: job.statusMessage,
    mediaId: job.mediaId,
    downloadUrl: job.downloadUrl,
    title: job.title,
    mimeType: job.mimeType,
    error: job.error,
  });
});

// GET /api/media/download/:mediaId
router.get('/download/:mediaId', (req, res) => {
  const { mediaId } = req.params;
  const record = storageManager.getFile(mediaId);
  if (!record) {
    return res.status(404).json({
      status: 'ERROR',
      message: 'The requested media file was not found or has expired.',
    });
  }

  res.setHeader('Content-Disposition', `attachment; filename="${encodeURIComponent(record.fileName)}"`);
  res.setHeader('Content-Type', record.mimeType);
  if (record.sizeBytes > 0) {
    res.setHeader('Content-Length', record.sizeBytes);
  }

  const stream = fs.createReadStream(record.filePath);
  stream.on('error', (err) => {
    console.error(`Error streaming file ${record.filePath}:`, err.message);
    if (!res.headersSent) {
      res.status(500).json({ status: 'ERROR', message: 'Failed to stream media file.' });
    }
  });

  stream.pipe(res);
});

export default router;
