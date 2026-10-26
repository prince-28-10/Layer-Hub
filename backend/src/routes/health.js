import express from 'express';

const router = express.Router();

router.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    service: 'layerhub-backend',
    timestamp: new Date().toISOString(),
    uptimeSeconds: Math.floor(process.uptime()),
  });
});

export default router;
