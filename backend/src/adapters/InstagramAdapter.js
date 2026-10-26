import { BaseAdapter } from './BaseAdapter.js';
import { config } from '../config.js';

export class InstagramAdapter extends BaseAdapter {
  constructor() {
    super('Instagram');
  }

  canHandle(url) {
    if (!url) return false;
    try {
      const host = new URL(url.startsWith('http') ? url : `https://${url}`).hostname.toLowerCase();
      return host === 'instagram.com' || host.endsWith('.instagram.com') || host === 'instagr.am';
    } catch {
      return false;
    }
  }

  extractShortcode(rawUrl) {
    try {
      const url = new URL(rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`);
      const match = url.pathname.match(/\/(?:p|reel|tv)\/([A-Za-z0-9_-]+)/i);
      return match ? match[1] : null;
    } catch {
      return null;
    }
  }

  async analyze(rawUrl) {
    const url = rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`;
    const shortcode = this.extractShortcode(url);
    const token = config.instagramAccessToken || (config.metaAppId && config.metaClientToken ? `${config.metaAppId}|${config.metaClientToken}` : null);

    // If Meta credentials are provided, use official Meta Graph oEmbed API
    if (token) {
      try {
        const oembedUrl = `https://graph.facebook.com/v19.0/instagram_oembed?url=${encodeURIComponent(url)}&access_token=${encodeURIComponent(token)}`;
        const res = await fetch(oembedUrl);

        if (res.ok) {
          const data = await res.json();
          const author = data.author_name ? `@${data.author_name}` : 'Instagram';
          const title = data.title ? data.title.replace(/\s+/g, ' ').trim() : `Post by ${author}`;

          return {
            sourcePlatform: 'Instagram',
            originalUrl: url,
            title,
            thumbnailUrl: data.thumbnail_url || null,
            mediaType: 'UNKNOWN',
            durationSeconds: null,
            videoQualities: [],
            audioQualities: [],
            fileSize: null,
            status: 'SUCCESS',
            message: 'Post metadata retrieved via Meta Graph oEmbed. Media file download is restricted by Meta Platform Terms.',
          };
        }

        if (res.status === 401 || res.status === 403) {
          return {
            sourcePlatform: 'Instagram',
            originalUrl: url,
            title: shortcode ? `Instagram Post (${shortcode})` : 'Instagram Post',
            thumbnailUrl: null,
            mediaType: 'UNKNOWN',
            durationSeconds: null,
            videoQualities: [],
            audioQualities: [],
            fileSize: null,
            status: 'RESTRICTED',
            message: 'Meta Graph API did not allow access. Post may be private or credentials invalid.',
          };
        }
      } catch (_) {}
    }

    // Default when no Meta Graph API credentials configured
    return {
      sourcePlatform: 'Instagram',
      originalUrl: url,
      title: shortcode ? `Instagram Post (${shortcode})` : 'Instagram Post',
      thumbnailUrl: null,
      mediaType: 'UNKNOWN',
      durationSeconds: null,
      videoQualities: [],
      audioQualities: [],
      fileSize: null,
      status: 'NOT_CONFIGURED',
      message: 'Instagram requires Meta Graph API credentials (META_APP_ID and META_CLIENT_TOKEN in backend/.env) to read post metadata. Direct unauthorized media download is not supported under Meta Platform Terms.',
    };
  }

  async prepare(job) {
    throw new Error("Instagram media preparation is restricted under Meta's Platform Terms. Public scraping without credentials is not permitted.");
  }
}
