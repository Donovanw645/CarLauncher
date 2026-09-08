# Car Launcher

A dash-mounted Android launcher for a tablet that rides in the car: map and turn-by-turn
navigation, music controls, and vehicle status, all on one screen. The tablet joins your
iPhone hotspot for data and pairs to the car stereo over Bluetooth for audio.

**The APK is `apk/CarLauncher.apk`** (14 MB, signed, `minSdk 24` / Android 7.0+).

---

## One thing to know up front

You asked for the mapping and music to live *inside* the app rather than kicking you out
to another one. Music works exactly that way. Maps needed a different engine than you
might expect, and it is worth knowing why:

**Google Maps cannot be embedded in a third-party app.** The consumer app can only be
launched, not hosted, and Google's Navigation SDK (the one that does turn-by-turn inside
your own UI) is a licensed enterprise product, not something a sideloaded personal app
can use.

So the map here is built on **OpenStreetMap** instead, and it is genuinely in-app:
vector-quality raster tiles, place search, route drawing, spoken turn-by-turn, automatic
re-routing when you miss a turn. Nothing hands off to another app. The trade-off is that
OSM has no live traffic and its routing is a bit blunter than Google's — see
[Known limits](#known-limits).

---

## Installing

Only needed once — after this the launcher updates itself over the air.

1. On the tablet, open
   [the latest release](https://github.com/donovanw645/CarLauncher/releases/latest) and
   download the `.apk`.
2. Tap it. You will be asked to allow installs from your browser the first time.
3. Open **Car Launcher**.

Or over USB with debugging on:

```bash
adb install -r "%LOCALAPPDATA%\CarLauncherBuild\app\outputs\apk\release\CarLauncher-release-1.1-2.apk"
elease\CarLauncher-release-1.1-2.apk"
```

**Always install the release build, never the debug one.** They are signed with different
keys, and Android refuses to update in place across a key change — which forces an uninstall
and wipes every setting and permission on the tablet.

## Updating

After the first install the launcher updates itself. It checks
[the latest release](https://github.com/donovanw645/CarLauncher/releases/latest) every few
hours, and when a newer build exists a dot appears on the **Settings** icon in the nav rail.
Open **Settings → Software update** and press **Install now**; Android shows one confirmation
dialog and the launcher restarts on the new version.

Nothing is lost in an update — settings, camera feeds, saved places, granted permissions,
media-control access and the home-screen role all carry over, because it is an in-place
update of an identically signed APK.

Two deliberate limits:

- **A download never happens over the phone hotspot.** Auto-download waits for an unmetered
  network. You can always force it with **Download anyway**.
- **Install is blocked while the dashcam is recording or navigation is running**, since
  installing restarts the launcher.

The one-time grant Android needs ("install unknown apps") is offered by a button in the same
block the first time an update is ready.

### Publishing a new version

```bash
tools/release.sh 1.2 "What changed in this build"
```

That bumps `versionCode`, builds a signed release, checks the signing certificate actually
matches the installed one, writes `update.json`, commits, and publishes both to GitHub
Releases. The tablet picks it up from a URL that never changes between releases:

```
https://github.com/donovanw645/CarLauncher/releases/latest/download/update.json
```

Needs the GitHub CLI (`winget install --id GitHub.cli`, then `gh auth login`).

## First run — three things to turn on

The app asks for location, camera, microphone and Bluetooth permissions on launch — camera
and microphone are for the dashcam and nothing else. Two more are manual, both reachable
from the **Settings** tab:

| What | Where | Why |
|---|---|---|
| **Media control access** | Settings → Media control access → Open | Android only exposes other apps' media sessions to a notification listener. This is what lets the launcher drive Spotify / YouTube Music. It reads nothing else from your notifications. |
| **Use as the home screen** | Settings → Use as the tablet's home screen | Makes the launcher start with the tablet and come back when you press Home. This is what turns it into a real dash unit rather than an app you have to open. |
| **Bluetooth pairing** | Settings → Bluetooth settings | Pair the tablet to the car stereo. Audio then goes out over A2DP and the launcher shows the connected device in the top strip. |

---

## What each tab does

### Home
A large map on the left, Now Playing and transport controls on the right. The shortcuts
— Home, Work, Search, Library — sit as small chips on the map itself rather than taking a
row of their own, so the map gets the space. The top strip carries the clock, GPS speed,
the paired stereo, network/hotspot status and battery. While a route is running, the
next-turn banner overlays the map.

### Maps
Full-screen map with:
- **Search** by place name or address (OpenStreetMap / Nominatim), biased to where you are
- **Long-press anywhere** to drop a destination and drive there
- **Home / Work** one-tap destinations
- Spoken turn-by-turn with a "then" preview of the following manoeuvre
- Distance to the turn, distance remaining, time remaining and arrival clock
- **Automatic re-routing** — three consecutive fixes more than 60 m off the line triggers a new route
- Heading-up / north-up toggle, big zoom buttons, recentre
- **Night mode** that switches itself after dark (Settings → Night mode to force it)

### Media
Streaming only — there is no local file player. Two halves:

- **Left — the controller.** Drives whatever app currently owns audio: album art, scrubber,
  previous/next, and a volume slider. If more than one app has a session, chips at the top
  let you pick which one the controls point at.
- **Right — the library.** Pointed at **Apple Music** only; it connects on its own, with no
  app picker to wade through. Two tabs:
  - **Browse** — walks Apple Music's playlists, albums and artists with a search box. Tap
    a playlist to open it, tap a track to play it. Read
    [the Apple Music note](#about-apple-music-and-library-browsing) before relying on this.
  - **Queue** — the current queue with the live track highlighted. Tap any row to jump to
    it. This works regardless of whether browsing is allowed.

  Underneath both sits a **mirrored spectrum visualiser** in the app's own green-to-blue
  palette, with peak caps and a fading reflection. It tries the real output spectrum
  first via Android's `Visualizer` effect; when the OS or the playing app blocks that
  — see below — it falls back to a playback-driven animation that runs while music is
  playing and settles when it is paused.

### Media bar
Every tab except Home and Media carries a transport strip along the bottom: album art,
track and artist, −10 s, previous, play/pause, next, +30 s, and a hairline progress line.
Tap the art or the title to jump to the Media tab. Home and Media are excluded because
they each already have full-size controls of their own.

### CCTV
An NVR wall for the network cameras on the car. Settings → Cameras holds up to four
feeds, and a selector chooses whether **1, 2 or 4** are on screen at once. Tapping the
expand icon on any tile solos it full-width; tapping again returns to the grid — handy
for putting the rear camera up while reversing.

**Protocols.** Paste whatever your streaming server produces and press **Test**:

| Source | What to use | How it is handled |
|---|---|---|
| mjpg-streamer, ustreamer, motion | `http://192.168.1.50:8080/?action=stream` | MJPEG decoded natively |
| Any still-image endpoint | `http://192.168.1.50/snapshot.jpg` | Polled at 5 fps |
| MediaMTX, rtsp-simple-server, IP cameras | `rtsp://192.168.1.50:8554/front` | ExoPlayer, RTP forced over TCP |
| HLS | `http://…/index.m3u8` | ExoPlayer |

The kind is auto-detected — from the URL for RTSP/HLS, and from the response's content
type for HTTP, so an MJPEG URL that turns out to serve a single JPEG still works. Cameras
behind `http://user:pass@host/…` authenticate automatically.

**Test** actually connects and pulls a frame rather than just checking the URL parses. It
reports back like `OK · MJPEG · 640x480`, `OK · Snapshot · 1280x720 · polled at 5 fps`,
`RTSP OK on 192.168.1.50:8554`, or the specific failure.

**Performance.** This is the heaviest screen in the app, so streams run *only while the
tab is on screen* — leaving it closes every connection. Measured on the test rig: 36% CPU
with four MJPEG feeds decoding, 0% the moment you switch to another tab. Frames are also
downscaled to the tile they are drawn in and decoded to RGB_565, so a 1080p feed in a
quarter-screen tile does not decode at 1080p.

### Dashcam
Loop recording to the tablet, started with one button.

- **Live preview whenever the tab is open** — the camera comes up as soon as you land on
  the Dashcam tab, showing "Live · not recording" until you press record. Leaving the tab
  releases the camera again unless a recording is running.
- Records in fixed-length clips (1/3/5/10 min) at 480p–4K, rear or front camera
- **Choose where clips are saved** — Settings lists every storage volume the tablet
  exposes with its free space, so you can send recordings to the SD card and leave
  internal storage alone. Switching only affects new clips; existing ones stay put.
- **Runs as a foreground service**, so the loop keeps recording while you are on the map,
  in another app, or with the screen off
- Oldest clips are deleted automatically once the storage cap (2–16 GB) is reached
- Tap the **padlock** on a clip to keep it — kept clips move to a separate folder that
  pruning never touches, which is what you want after an incident
- Tap **play** on any clip to review it in place; the bin deletes it
- Optional cabin audio, and optional auto-start when the tablet powers up with the car

Clips live in `Android/data/com.donovan.carlauncher/files/Movies/dashcam/` on whichever
volume you picked, kept ones in `.../dashcam/saved/`. Settings shows the resolved path.
Nothing is uploaded anywhere.

### Apps
A plain grid of everything installed, for the occasional thing the launcher does not do.
Opening something here does leave the launcher — it is deliberately the last tab.

### Settings
Orientation lock, keep-screen-on, miles/kilometres, map style, voice guidance, Home and
Work addresses, custom tile/routing/search servers, and shortcuts to the permission
screens.

---

## About Apple Music and library browsing

Short version: **you can control Apple Music completely from the launcher, but you cannot
list its playlists inside the launcher, and that is Apple's choice rather than a gap in
this app.**

The detail, because it decides what is worth building:

- Apple ships **no MusicKit SDK for Android**. MusicKit exists for Apple platforms and as
  MusicKit JS for the web; there is no Android equivalent. The Apple Music Web API needs a
  developer token signed with a paid Apple Developer key, and playback of Apple Music
  content through it is restricted to Apple's own players.
- Android's one cross-app library mechanism is `MediaBrowserService` — the same interface
  Android Auto uses. Apple Music ships one (it supports Android Auto), but apps are free
  to check the caller and most big ones do.
- I tested this against YouTube Music on an Android 14 tablet. It refused:
  `No root for client com.donovan.carlauncher`. Spotify and Apple Music apply the same
  kind of allowlist, keyed to package name and signing certificate, and only approved
  clients (Android Auto, Assistant, Wear) get through. A sideloaded app cannot be on that
  list.

The Browse tab therefore tries Apple Music and shows you what it gets. If Apple Music
refuses, you get a plain explanation plus a **play-by-name box** — type "Discovery
Weekly", press Play, and the launcher asks Apple Music to start it without you leaving the
screen. What always works:

- play / pause / next / previous / seek, from the Media tab and the media bar
- track, artist, album and album art
- the **Queue tab**, which is your current playlist and is fully tappable
- volume

In practice that covers driving: start a playlist once, then run everything from the dash.

## Known limits

Worth knowing before you rely on it for a commute:

- **No live traffic.** OSM routing has no congestion data, so ETAs are free-flow estimates.
- **The default routing server is a public demo.** `router.project-osrm.org` is run for
  testing and has no uptime guarantee. If it is down, routes fail. Settings →
  *Routing server* takes any OSRM-compatible URL, so a self-hosted instance fixes this
  permanently.
- **Search is rate-limited.** Nominatim's usage policy allows about one request per
  second; the search box debounces to stay inside that. It is fine for personal use and
  will not survive being hammered.
- **Lane guidance and speed limits are not shown.** OSRM's step data does not carry them
  reliably.
- **Tiles need data on first sight.** Around 200 MB of tiles are cached on disk, so roads
  you drive often keep working if the hotspot drops, but genuinely new areas need a
  connection.
- **Night mode is a filter, not a dark map.** Every keyless dark tile service has now
  closed off (CARTO and Stadia both want an API key), so the app ships standard
  OpenStreetMap tiles and inverts them after dark. It reads well — see the screenshot —
  but it is not a purpose-built dark cartography. If you want the real thing, get a free
  key from Stadia, Thunderforest or MapTiler and paste their tile URL into Settings →
  *Custom tile server*; night mode then steps aside and uses their styling.
- **Auto-start on boot is best-effort.** Android 10+ blocks background activity starts.
  Setting the launcher as the home app is the reliable way to have it come up with the
  tablet.
- **Most streaming apps will not let you browse their library.** See
  [the Apple Music note](#about-apple-music-and-library-browsing). Transport control, art
  and the Queue tab work regardless.
- **The visualiser is usually animated, not analysed.** Reading the real output spectrum
  needs `Visualizer` on the global mix, which requires RECORD_AUDIO *and* the playing
  app's consent — since Android 10 most streaming apps refuse to be captured. The effect
  is created successfully either way; if no signal arrives the bars run on a
  playback-driven animation instead. It reacts to play/pause, not to the beat.
- **The dashcam records what the tablet camera sees.** A tablet screen-out on the
  windscreen points its rear camera at the road, which is the arrangement this assumes.
  Clip orientation follows the display, so keep the orientation lock set to landscape.
- **4K needs a camera that supports it.** If the tablet cannot do the requested size,
  CameraX falls back to the nearest resolution it can rather than failing.
- **Recording and heavy navigation together are warm work.** A cheap tablet in direct
  sun may thermal-throttle; 1080p at 3-minute clips is a sane default.
- **Cleartext HTTP is enabled app-wide** so LAN cameras work. Android blocks plain HTTP
  by default from API 28, and its network config cannot express "private ranges only",
  so `res/xml/network_security_config.xml` permits cleartext generally. Everything the
  launcher itself fetches (OpenStreetMap, OSRM, Nominatim) is still https.
- **RTSP playback is built but untested against real hardware.** MJPEG, snapshot polling
  and the error paths were all verified end to end against a live server; RTSP was only
  verified as far as the reachability probe, because there was no RTSP camera to point at.
- **The SD card option only appears if the tablet exposes one as a separate volume.**
  That is the normal case for a real microSD card. It uses the app-specific folder on
  that card, which needs no storage permission — but it also means Android deletes the
  clips if you uninstall the app.

---

## Rebuilding it

The toolchain is already installed on this machine at `C:\Android`:

```bash
cd "C:\Users\donov\OneDrive\Documents\Car Luncher\CarLauncher"
.\gradlew.bat assembleRelease
```

The build needs `JAVA_HOME=C:\Android\jdk17`. Output lands in
`%LOCALAPPDATA%\CarLauncherBuild\app\outputs\apk\release\`.

Build output is deliberately redirected out of the OneDrive folder — OneDrive locks files
mid-build and causes random "Access is denied" failures otherwise.

### Signing key

`carlauncher.jks` signs the release build, with credentials in `keystore.properties`. Both
are gitignored and must stay that way — the repo is public.

**Back that keystore up.** Android refuses to install an update signed with a different key,
so losing the file ends over-the-air updates permanently: every future version would need an
uninstall and reinstall, wiping settings and permissions each time. Its SHA-256 is pinned in
`tools/release.sh`, which refuses to publish an APK signed by anything else.

### Layout

```
CarLauncher/app/src/main/java/com/donovan/carlauncher/
  CarLauncherApp.kt          Application + service locator for every long-lived object
  MainActivity.kt            Single activity: full-screen, orientation, permissions
  data/Prefs.kt              Settings, persisted to SharedPreferences
  media/
    CarNotificationListener  The notification-listener stub Android requires
    MediaRemote.kt           Universal remote + queue, over MediaSessionManager
    MediaBrowserRepo.kt      Cross-app library browsing and search
    AudioSpectrum.kt         Output-mix FFT tap for the visualiser
  dashcam/
    Dashcam.kt               State, storage volumes, pruning, keep/delete
    DashcamService.kt        Foreground service owning CameraX, preview and the loop
  cctv/
    CameraStream.kt          MJPEG multipart / snapshot decoding
    CameraProbe.kt           The Test button: real connect, real frame
  update/
    UpdateManifest.kt        The published JSON, and the update state machine
    Updater.kt               Check, download, verify SHA-256, PackageInstaller
    InstallReceiver.kt       PackageInstaller status callbacks
  nav/
    Geo.kt                   Haversine, polyline decode, point-to-line, formatting
    GeocodeApi.kt            Nominatim search + reverse
    RouteApi.kt              OSRM routing, manoeuvre -> English
    NavEngine.kt             Step advance, announcements, off-route, re-route
    Speech.kt                TTS tagged as navigation guidance so music ducks
  system/                    Location, Bluetooth/network/battery, installed apps
  ui/                        Compose: shell, shared MapView holder, screens
```

---

Map data and tiles © OpenStreetMap contributors (ODbL). Routing by OSRM. Geocoding by
Nominatim.
