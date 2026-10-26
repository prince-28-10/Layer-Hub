import express from 'express';
import cors from 'cors';
import { fileURLToPath } from 'url';
import path from 'path';
import { config } from './config.js';
import healthRoutes from './routes/health.js';
import mediaRoutes from './routes/media.js';
import { cleanupService } from './services/CleanupService.js';

export const app = express();

app.use(cors());
app.use(express.json());

// API Routes
app.use('/api', healthRoutes);
app.use('/api/media', mediaRoutes);

// Root route
app.get('/', (req, res) => {
  res.json({
    name: 'Layer Hub Media Service',
    version: '1.0.0',
    documentation: '/api/health',
  });
});

const isMainModule = () => {
  if (!process.argv[1]) return false;
  try {
    const currentFile = fileURLToPath(import.meta.url);
    const invokedFile = path.resolve(process.argv[1]);
    return currentFile === invokedFile;
  } catch {
    return false;
  }
};

if (isMainModule()) {
  cleanupService.start();
  app.listen(config.port, config.host, () => {
    console.log(`===============================================`);
    console.log(` Layer Hub Backend is running!`);
    console.log(` Listening on: http://${config.host}:${config.port}`);
    console.log(` Health check: http://localhost:${config.port}/api/health`);
    console.log(` Storage dir:  ${config.storageDir}`);
    console.log(` Video limit:  ${config.maxVideoDurationSeconds}s`);
    console.log(`===============================================`);
  });
}
