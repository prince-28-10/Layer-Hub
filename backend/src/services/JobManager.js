import crypto from 'crypto';

export const JobStatus = {
  PENDING: 'PENDING',
  IN_PROGRESS: 'IN_PROGRESS',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
};

class JobManager {
  constructor() {
    this.jobs = new Map();
  }

  createJob({ url, mediaType, qualityId }) {
    const jobId = crypto.randomUUID();
    const job = {
      jobId,
      url,
      mediaType,
      qualityId,
      status: JobStatus.PENDING,
      progressPercent: 0,
      statusMessage: 'Job queued for preparation',
      mediaId: null,
      downloadUrl: null,
      title: null,
      mimeType: null,
      error: null,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    this.jobs.set(jobId, job);
    return job;
  }

  getJob(jobId) {
    return this.jobs.get(jobId) || null;
  }

  updateJob(jobId, updates) {
    const job = this.jobs.get(jobId);
    if (!job) return null;
    Object.assign(job, updates, { updatedAt: Date.now() });
    return job;
  }

  cleanOldJobs(maxAgeMs = 1800000) { // 30 mins
    const now = Date.now();
    let cleaned = 0;
    for (const [id, job] of this.jobs.entries()) {
      if (now - job.createdAt > maxAgeMs) {
        this.jobs.delete(id);
        cleaned++;
      }
    }
    return cleaned;
  }
}

export const jobManager = new JobManager();
