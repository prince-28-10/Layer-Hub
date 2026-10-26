import { config } from '../config.js';
import { storageManager } from './StorageManager.js';
import { jobManager } from './JobManager.js';

class CleanupService {
  constructor() {
    this.timer = null;
  }

  start() {
    if (this.timer) return;
    this.timer = setInterval(() => {
      this.runCleanup();
    }, config.cleanupIntervalMs);
    // Don't keep Node process alive just for cleanup timer during tests
    if (this.timer.unref) {
      this.timer.unref();
    }
  }

  stop() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  runCleanup() {
    const cleanedFiles = storageManager.cleanExpired();
    const cleanedJobs = jobManager.cleanOldJobs();
    if (cleanedFiles > 0 || cleanedJobs > 0) {
      console.log(`[Cleanup] Removed ${cleanedFiles} expired files and ${cleanedJobs} old jobs.`);
    }
  }
}

export const cleanupService = new CleanupService();
