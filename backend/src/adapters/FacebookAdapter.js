import { BaseAdapter } from './BaseAdapter.js';
import { config } from '../config.js';

export class FacebookAdapter extends BaseAdapter {
  constructor() {
    super('Facebook');
  }

  canHandle(url) {
    if (!url) return false;
    try {
      const host = new URL(url.startsWith('http') ? url : `https://${url}`).hostname.toLowerCase();
      return host === 'facebook.com' || host.endsWith('.facebook.com') || host === 'fb.watch' || host === 'fb.com';
    } catch {
      return false;
    }
  }

  async analyze(rawUrl) {
    const url = rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`;
    const token = config.metaAppId && config.metaClientToken ? `${config.metaAppId}|${config.metaClientToken}` : null;

    if (token) {
      try {
        const oembedUrl = `https://graph.facebook.com/v19.0/oembed_post?url=${encodeURIComponent(url)}&access_token=${encodeURIComponent(token)}`;
        const res = await fetch(oembedUrl);

        if (res.ok) {
          const data = await res.json();
          const author = data.author_name ? data.author_name : 'Facebook';
          const title = data.title ? data.title.replace(/\s+/g, ' ').trim() : `Post by ${author}`;

          return {
            sourcePlatform: 'Facebook',
            originalUrl: url,
            title,
            thumbnailUrl: null,
            mediaType: 'UNKNOWN',
            durationSeconds: null,
            videoQualities: [],
            audioQualities: [],
            fileSize: null,
            status: 'SUCCESS',
            message: 'Post metadata retrieved via Meta Graph oEmbed. Direct video download is restricted by Meta Platform Terms.',
          };
        }

        if (res.status === 401 || res.status === 403) {
          return {
            sourcePlatform: 'Facebook',
            originalUrl: url,
            title: 'Facebook Media',
            thumbnailUrl: null,
            mediaType: 'UNKNOWN',
            durationSeconds: null,
            videoQualities: [],
            audioQualities: [],
            fileSize: null,
            status: 'RESTRICTED',
            message: 'Meta Graph API did not allow access. Post or video may be private or restricted.',
          };
        }
      } catch (_) {}
    }

    return {
      sourcePlatform: 'Facebook',
      originalUrl: url,
      title: 'Facebook Media',
      thumbnailUrl: null,
      mediaType: 'UNKNOWN',
      durationSeconds: null,
      videoQualities: [],
      audioQualities: [],
      fileSize: null,
      status: 'NOT_CONFIGURED',
      message: 'Facebook requires Meta Graph API credentials (META_APP_ID and META_CLIENT_TOKEN in backend/.env) to read post metadata. Direct unauthorized video download is not supported under Meta Platform Terms.',
    };
  }

  async prepare(job) {
    throw new Error("Facebook media preparation is restricted under Meta's Platform Terms.");
  }
}
