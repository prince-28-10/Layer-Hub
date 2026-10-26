# Layer Hub source metadata setup

Layer Hub resolves source metadata separately from media retrieval. Metadata alone does not mean Layer Hub has a media file or a supported way to prepare one.

## Local configuration

The Reddit metadata adapter reads these Gradle properties (or same-named environment variables) into `BuildConfig`. Keep them out of source control. On Windows, they can be placed in `%USERPROFILE%\.gradle\gradle.properties`:

```properties
REDDIT_CLIENT_ID=your_registered_installed_app_client_id
REDDIT_USERNAME=your_reddit_username_for_the_user_agent_contact
```

The Android project currently uses package name `com.example.androidtest`. The Reddit client ID is an installed-app identifier, not a client secret. Gradle builds still succeed without these values; Reddit then returns an explicit not-configured result. No YouTube API key is included in the Android app.

## Reddit

Register an installed Reddit app and obtain Data API access for Layer Hub's intended use. Layer Hub requests an app-only OAuth token from Reddit, then reads a post by its `t3_` fullname through the documented [`/api/info` endpoint](https://www.reddit.com/dev/api/#GET_api_info). It uses the Reddit username as the contact portion of its descriptive User-Agent. No Reddit client secret is embedded in the app.

The Data API requires OAuth and applies rate limits. Reddit can reject access, and posts in private or restricted communities, deleted posts, and removed posts may not be available. Commercial or otherwise unapproved uses need separate Reddit permission. In those cases Layer Hub shows a configuration, restricted, rate-limited, or unavailable status. Gallery previews use an actual image returned by Reddit. The API does not grant a general save-to-device media download path, so Reddit media is metadata-only in Layer Hub.

## YouTube

The official [`videos.list`](https://developers.google.com/youtube/data/reference/rest/v3/videos/list) API can return title, available thumbnails, and ISO 8601 duration. Layer Hub does not call it from Android because Google recommends keeping API keys out of client code. To enable metadata, deploy a server-side proxy, create/select a Google Cloud project, enable **YouTube Data API v3**, and restrict the API key to that API and the proxy's server egress. The proxy should return only the permitted metadata and must not expose its key to the app. Private YouTube data would require user OAuth.

Until that proxy is configured, YouTube metadata is explicitly unconfigured. The Data API does not provide media files. YouTube policies prohibit downloading audiovisual copies without prior written approval, so metadata access does not enable media preparation or quality choices.

## Scope and limitations

- Reddit is the only source metadata network adapter currently configured in the app. YouTube response mapping remains covered by unit tests, but its network adapter is disabled until a server-side proxy is configured.
- Instagram, Facebook, X, Telegram, and Reddit video-host-only links remain explicitly unsupported.
- Direct audio/video URLs use the modular downloader engine and stream only server-identified media into temporary app storage. The app checks byte integrity and reads video duration from the completed file before showing destinations. Files over three minutes or with unverifiable duration are deleted.
- Shared media continues through the existing `content://` preparation path.
- Direct responses have no fabricated thumbnail or format list: only the actual original stream is offered.
- Reddit may return duration metadata, but Reddit media retrieval remains disabled; YouTube duration metadata is unavailable until its proxy is deployed.
