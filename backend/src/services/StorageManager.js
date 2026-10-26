import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { config } from '../config.js';

class StorageManager {
  constructor() {
    this.storageDir = config.storageDir;
    this.fileRegistry = new Map();
    this.ensureDirectory();
  }

  ensureDirectory() {
    if (!fs.existsSync(this.storageDir)) {
      fs.mkdirSync(this.storageDir, { recursive: true });
    }
  }

  generateMediaId() {
    return crypto.randomUUID();
  }

  registerFile({ mediaId, filePath, fileName, mimeType, sizeBytes = 0, ttlMs = config.fileTtlMs }) {
    const id = mediaId || this.generateMediaId();
    const createdAt = Date.now();
    const expiresAt = createdAt + ttlMs;

    const record = {
      mediaId: id,
      filePath,
      fileName,
      mimeType,
      sizeBytes,
      createdAt,
      expiresAt,
    };

    this.fileRegistry.set(id, record);
    return record;
  }

  getFile(mediaId) {
    const record = this.fileRegistry.get(mediaId);
    if (!record) return null;
    if (Date.now() > record.expiresAt) {
      this.deleteFile(mediaId);
      return null;
    }
    if (!fs.existsSync(record.filePath)) {
      this.fileRegistry.delete(mediaId);
      return null;
    }
    return record;
  }

  deleteFile(mediaId) {
    const record = this.fileRegistry.get(mediaId);
    if (record) {
      try {
        if (fs.existsSync(record.filePath)) {
          fs.unlinkSync(record.filePath);
        }
      } catch (err) {
        console.error(`Failed to delete temporary file ${record.filePath}:`, err.message);
      }
      this.fileRegistry.delete(mediaId);
    }
  }

  cleanExpired() {
    const now = Date.now();
    let cleanedCount = 0;
    for (const [id, record] of this.fileRegistry.entries()) {
      if (now > record.expiresAt) {
        this.deleteFile(id);
        cleanedCount++;
      }
    }
    // Also clean orphaned files on disk older than TTL
    try {
      const files = fs.readdirSync(this.storageDir);
      for (const file of files) {
        const fullPath = path.join(this.storageDir, file);
        try {
          const stats = fs.statSync(fullPath);
          if (now - stats.mtimeMs > config.fileTtlMs) {
            fs.unlinkSync(fullPath);
            cleanedCount++;
          }
        } catch (_) {}
      }
    } catch (err) {
      console.error('Error scanning storage directory for cleanup:', err.message);
    }
    return cleanedCount;
  }
}

export const storageManager = new StorageManager();
