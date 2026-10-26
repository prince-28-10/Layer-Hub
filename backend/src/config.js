import dotenv from 'dotenv';
import path from 'path';
import { fileURLToPath } from 'url';

dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export const config = {
  port: parseInt(process.env.PORT || '3000', 10),
  host: process.env.HOST || '0.0.0.0',
  storageDir: path.resolve(__dirname, '..', process.env.STORAGE_DIR || './temp_storage'),
  maxVideoDurationSeconds: parseInt(process.env.MAX_VIDEO_DURATION_SECONDS || '180', 10),
  fileTtlMs: parseInt(process.env.FILE_TTL_MS || '900000', 10), // 15 mins
  cleanupIntervalMs: parseInt(process.env.CLEANUP_INTERVAL_MS || '120000', 10), // 2 mins
  redditClientId: process.env.REDDIT_CLIENT_ID || '',
  redditClientSecret: process.env.REDDIT_CLIENT_SECRET || '',
  redditUserAgent: process.env.REDDIT_USER_AGENT || 'LayerHub/1.0',
  twitterBearerToken: process.env.TWITTER_BEARER_TOKEN || '',
  metaAppId: process.env.META_APP_ID || '',
  metaClientToken: process.env.META_CLIENT_TOKEN || '',
  instagramAccessToken: process.env.INSTAGRAM_ACCESS_TOKEN || '',
};
