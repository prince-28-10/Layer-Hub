# Layer Hub downloader engine

## Boundary and current status

`downloader-engine` is a pure Kotlin/JVM module. It has no Android or Compose dependency. The app can use it through the `SourceAdapter` contract, whose four separate stages are source identification, metadata retrieval, format discovery, and media retrieval. The first complete adapter is `DirectMediaSourceAdapter`; it only becomes downloadable after a real HTTP response identifies an audio or video MIME type. It offers the original response only.

The debug-only `DownloaderEngineTestActivity` is a manual test harness. It reports real metadata and capability by source; only verified direct audio/video responses can download. Start it from a connected device with:

```shell
adb shell am start -a com.example.androidtest.action.DOWNLOADER_ENGINE_TEST
```

It displays the current app resolver's real Reddit metadata where configured, and direct-media response metadata from the engine. It never treats metadata as download capability. YouTube metadata is explicitly unconfigured until a server-side API proxy exists. Direct downloads use the same preparation path as the main app, including file verification and the three-minute video check. A successful transfer is saved under the app cache folder `layerhub-prepared`; it is not sent to another app.

The existing share flow, theme/background controls, and shared-file preparation path remain in place. Direct audio/video URLs now use the engine from the existing Prepare flow. Video duration is read from the completed temporary file before destinations appear; files whose duration cannot be verified or exceeds three minutes are deleted and rejected. Direct URLs do not have a genuine thumbnail provider, so the preview does not invent one.

## Direct-media behavior and limits

- Only public HTTP(S) URLs are considered. Social hosts are classified before the generic direct-media candidate path, so a platform post URL never becomes a direct download merely because of its domain or suffix.
- The server must provide `audio/*` or `video/*` Content-Type. `application/octet-stream`, image responses, HTML login/error pages, and changed MIME responses are rejected. The filename comes from `Content-Disposition` or the final URL path; no thumbnail is synthesized.
- Known platform hosts and media CDNs are identified as their source, and redirects to them are stopped. This prevents a generic URL from routing a platform stream through the direct adapter.
- Redirects are handled manually, limited to five hops, revalidated at each hop, and cannot downgrade an HTTPS request to HTTP. Userinfo, nonstandard ports, local/private IPs, and local hostnames are rejected by default.
- Files stream to a temporary `.part` file. Progress reports bytes actually written; cancellation is checked during transfer; the default limit is 512 MiB, with 10-second connect, 30-second read, and 20-minute total transfer limits. Partial files are deleted after errors/cancellation. A successful result is returned only after byte-count validation and final rename.
- DNS destinations are checked before each request, but Java `HttpURLConnection` does not expose a supported way to pin the resolved address to the socket. A production backend that accepts arbitrary user URLs should add socket-level DNS pinning and egress controls.

## Source-specific access assessment

### Reddit

The existing app metadata adapter uses OAuth and Reddit's documented `api/info` post lookup. It can map actual post titles, public previews/gallery images, verified Reddit-hosted video type, and duration when returned. Its access requires a registered Reddit Data API app, OAuth, a truthful descriptive User-Agent, current Reddit approval/eligibility for the intended use case, and compliance with limits. `REDDIT_CLIENT_ID` and `REDDIT_USERNAME` are local Gradle properties/environment settings; the installed-app client ID is an identifier, not a client secret. Reddit's Data API terms grant copy/display rights through the app and reserve other rights; this does not authorize a general save-to-device downloader. Reddit metadata can be available, but media retrieval remains disabled.

### YouTube

The official `videos.list` method can return `snippet.title`, thumbnail URLs, and `contentDetails.duration` for an accessible video. A general public-data request needs a Google Cloud project with YouTube Data API v3 enabled and an API key; private user data instead requires OAuth. Google recommends restricting API keys and says not to put API keys in client code. This project no longer injects `YOUTUBE_DATA_API_KEY` into Android `BuildConfig`; the app reports YouTube metadata as unconfigured until a small server-side metadata proxy is deployed. The proxy must keep its API key server-side and return only permitted metadata.

Neither `videos.list` nor another YouTube Data API method is a media-file download API. YouTube policies prohibit downloading/caching/storing audiovisual copies without prior written YouTube approval, and the developer policy guide also disallows download/audio extraction features outside the YouTube Premium experience. No YouTube downloader is implemented. Written YouTube approval and a separately authorized retrieval method would be prerequisites before changing that status.

### Instagram, Facebook, X, and Telegram

- **Instagram:** Meta's official Instagram API is for Professional accounts (Business/Creator) linked to a Facebook Page and requires an app, user token, and requested permissions. It does not provide arbitrary consumer-account post access. Any future adapter must be limited to objects the authenticated account is allowed to access; no general link downloader is implemented.
- **Facebook:** The official Graph API requires an app and appropriate user/Page access tokens and permissions. Its supported page-management and publishing flows do not establish a general public-post video download API. Arbitrary Facebook URLs remain unsupported.
- **X:** X API v2 post lookup can return post/media metadata and media fields such as `url`, `preview_image_url`, `duration_ms`, and `variants` when the app has the needed endpoint access and OAuth authorization. The current X developer console uses consumption-based billing. No X resolver/downloader is implemented; API entitlement, OAuth, current terms for saving the actual media, and a separately validated media retrieval path are still required.
- **Telegram:** Bot API `getFile` downloads a file only after a bot has received a `file_id`, and the hosted Bot API currently limits downloads to 20 MB. It does not resolve arbitrary public post URLs. Access to a user's chats would require an authorized Telegram client/MTProto session (for example through TDLib), user consent, and the relevant chat access. No arbitrary Telegram link downloader is implemented.

## Setup and next work

1. For Reddit metadata, register/request the app's Data API access and provide `REDDIT_CLIENT_ID` and `REDDIT_USERNAME` through uncommitted Gradle properties or environment variables. Obtain separate written approval/agreement if the app's use case requires it. Do not put a Reddit client secret in the Android client.
2. For YouTube metadata, deploy an authenticated/rate-limited server-side proxy for official `videos.list?part=snippet,contentDetails&id=...`; enable YouTube Data API v3 and restrict the API key to that server and API. No local Android key property is supported.
3. Before adding source retrieval, document the authorized endpoint and permitted media use for that source, then implement it in a source-specific `SourceAdapter`; do not reuse direct URL rules for platform extractors.
4. Add a real thumbnail provider for direct media only if a source exposes an authorized thumbnail URL. Extend the three-minute validation only when a new adapter can retrieve and verify the completed file before handoff.

## References

- [YouTube videos.list](https://developers.google.com/youtube/data/reference/rest/v3/videos/list)
- [YouTube API credentials](https://developers.google.com/youtube/registering_an_application)
- [Google API key security guidance](https://docs.cloud.google.com/docs/authentication/api-keys-best-practices)
- [YouTube Developer Policies](https://developers.google.com/youtube/terms/developer-policies)
- [YouTube policy compliance guide](https://developers.google.com/youtube/terms/developer-policies-guide)
- [Reddit Data API Wiki](https://support.reddithelp.com/hc/en-us/articles/16160319875092-Reddit-Data-API-Wiki)
- [Reddit Data API Terms](https://redditinc.com/policies/data-api-terms)
- [Meta Instagram API docs](https://www.postman.com/meta/instagram/documentation/6yqw8pt/instagram-api)
- [Meta Facebook API docs](https://www.postman.com/meta/facebook/documentation/r56bjfd/facebook-api)
- [X post/media API fields](https://docs.x.com/x-api/posts/english-language-firehose-stream)
- [Telegram Bot API getFile](https://core.telegram.org/bots/api)
- [Telegram Bot API file size FAQ](https://core.telegram.org/bots/faq)
