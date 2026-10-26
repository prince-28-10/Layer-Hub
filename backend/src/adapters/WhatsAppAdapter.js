import { BaseAdapter } from './BaseAdapter.js';

export class WhatsAppAdapter extends BaseAdapter {
  constructor() {
    super('WhatsApp');
  }

  canHandle(url) {
    if (!url) return false;
    try {
      const host = new URL(url.startsWith('http') ? url : `https://${url}`).hostname.toLowerCase();
      return host === 'wa.me' || host === 'whatsapp.com' || host.endsWith('.whatsapp.com') || host.endsWith('.whatsapp.net');
    } catch {
      return false;
    }
  }

  async analyze(rawUrl) {
    const url = rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`;
    return {
      sourcePlatform: 'WhatsApp',
      originalUrl: url,
      title: 'WhatsApp Link',
      thumbnailUrl: null,
      mediaType: 'UNKNOWN',
      durationSeconds: null,
      videoQualities: [],
      audioQualities: [],
      fileSize: null,
      status: 'UNSUPPORTED',
      message: 'WhatsApp links point to conversations or invites, not public media files. To process WhatsApp media, share the video, audio, or image file directly from WhatsApp.',
    };
  }

  async prepare(job) {
    throw new Error('WhatsApp links cannot be downloaded. Share the media file directly from WhatsApp.');
  }
}
