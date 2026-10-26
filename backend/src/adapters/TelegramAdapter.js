import { BaseAdapter } from './BaseAdapter.js';

export class TelegramAdapter extends BaseAdapter {
  constructor() {
    super('Telegram');
  }

  canHandle(url) {
    if (!url) return false;
    try {
      const host = new URL(url.startsWith('http') ? url : `https://${url}`).hostname.toLowerCase();
      return host === 't.me' || host.endsWith('.t.me') || host === 'telegram.me';
    } catch {
      return false;
    }
  }

  async analyze(rawUrl) {
    const url = rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`;
    return {
      sourcePlatform: 'Telegram',
      originalUrl: url,
      title: 'Telegram Link',
      thumbnailUrl: null,
      mediaType: 'UNKNOWN',
      durationSeconds: null,
      videoQualities: [],
      audioQualities: [],
      fileSize: null,
      status: 'ERROR',
      message: 'Telegram media downloads require a bot session with file_id or MTProto user authorization.',
    };
  }

  async prepare(job) {
    throw new Error('Telegram downloads require Telegram Bot API token or client session.');
  }
}
