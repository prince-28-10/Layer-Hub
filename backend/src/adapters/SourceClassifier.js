export class SourceClassifier {
  static classify(rawUrl) {
    if (!rawUrl || typeof rawUrl !== 'string') {
      return { platform: 'Unknown', isSupported: false, error: 'Empty or invalid URL' };
    }

    try {
      const url = new URL(rawUrl.startsWith('http') ? rawUrl : `https://${rawUrl}`);
      const host = url.hostname.toLowerCase();

      if (this.isHost(host, ['youtube.com', 'youtu.be', 'youtube-nocookie.com'])) {
        return { platform: 'YouTube', isSupported: true };
      }
      if (this.isHost(host, ['reddit.com', 'redd.it', 'v.redd.it'])) {
        return { platform: 'Reddit', isSupported: true };
      }
      if (this.isHost(host, ['instagram.com', 'instagr.am'])) {
        return { platform: 'Instagram', isSupported: true };
      }
      if (this.isHost(host, ['facebook.com', 'fb.com', 'fb.watch'])) {
        return { platform: 'Facebook', isSupported: true };
      }
      if (this.isHost(host, ['x.com', 'twitter.com', 't.co'])) {
        return { platform: 'X', isSupported: true };
      }
      if (this.isHost(host, ['t.me', 'telegram.me'])) {
        return { platform: 'Telegram', isSupported: true };
      }
      if (this.isHost(host, ['wa.me', 'whatsapp.com', 'whatsapp.net'])) {
        return { platform: 'WhatsApp', isSupported: true };
      }

      // Check if URL ends with known media extension
      const pathname = url.pathname.toLowerCase();
      if (/\.(mp4|m4v|webm|mkv|mov|avi|flv|mp3|m4a|wav|aac|ogg|opus|jpg|jpeg|png|gif|webp)$/.test(pathname)) {
        return { platform: 'Direct Media', isSupported: true };
      }

      return { platform: 'Direct Media', isSupported: true, isGeneric: true };
    } catch (e) {
      return { platform: 'Unknown', isSupported: false, error: 'Malformed URL' };
    }
  }

  static isHost(host, domains) {
    return domains.some(domain => host === domain || host.endsWith(`.${domain}`));
  }
}
