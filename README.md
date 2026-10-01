<p align="center">
  <img src=".github/logo.png" alt="YamSync logo" width="128">
</p>

<h1 align="center">YamSync</h1>

<p align="center">
  <strong>An Android client for <a href="https://github.com/FuzzyGrim/Yamtrack">Yamtrack</a>, the self-hosted media tracker</strong>
</p>

<p align="center">
  <a href="https://developer.android.com/about/versions/oreo/"><img src="https://img.shields.io/badge/Min%20SDK-26-blue?style=flat" alt="Min SDK 26"></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue?style=flat" alt="GPLv3"></a>
  <a href="https://github.com/MaizeShark/YamSync/releases/latest"><img src="https://img.shields.io/github/v/release/MaizeShark/YamSync?style=flat&logo=github&label=Latest" alt="Latest release"></a>
</p>

YamSync puts your Yamtrack library on your phone: anime, manga, TV shows and seasons, movies, games,
books, comics and board games, all from your own server.

> [!IMPORTANT]
> **This project is largely written by an AI.** YamSync is a fork of [AniSync](https://github.com/Marco-9456/AniSync).
> The port from AniList to Yamtrack was written by an LLM, Anthropic's Claude in Claude Code:
> the Yamtrack client, the rewritten screens, the tests and this README. MaizeShark directed and
> reviewed the work and tested it on a real device and server. Read the code with that in mind, and
> please report anything that looks wrong.

> [!NOTE]
> YamSync is not affiliated with Yamtrack or with AniSync.

---

## Features

- **Every Yamtrack media type.** One library with a type picker: anime, manga, TV, seasons, movies,
  games (play time in hours and minutes), books, comics and board games.
- **Tracking.** Status, score (0–10), progress, start and end dates and notes. Rewatches are tracked
  as new entries, the way Yamtrack keeps them. Status, score and remove also work on a selection.
- **Home.** Everything in progress with a +1 button, plus what releases next.
- **Search.** Every type and every metadata source your server uses (MyAnimeList, MangaUpdates, TMDB,
  IGDB, Hardcover, Open Library, Comic Vine, BoardGameGeek). One tap adds to Planning.
- **Details.** Synopsis, info, seasons and episodes with "mark watched", cast, related titles,
  streaming providers and, for anime, openings and endings from AnimeThemes.
- **Calendar, widgets and notifications.** Built from your server's release calendar: new episodes of
  what you are watching, and premieres of what you planned.
- **Several accounts and servers**, with an optional "stay signed in" that renews the session by itself.

## Requirements

- Android 8.0 (API 26) or newer.
- A Yamtrack server you can reach from your phone, with a username and password. Plain `http://`
  addresses on your home network work. Servers that only allow single sign-on are not supported yet.

## Installing

Download the APK for your phone from the [releases page](https://github.com/MaizeShark/YamSync/releases)
(`arm64-v8a` fits almost every recent phone, `universal` fits everything), or build it yourself.
YamSync checks this repository for new releases and can update itself.

On first launch, enter the address you open Yamtrack at in a browser (for example
`http://192.168.1.10:8000`), your username and your password.

## How it works

Yamtrack has no API for apps. YamSync therefore talks to it the way a browser does: it signs in with
the normal login form, reads your library from Yamtrack's CSV export and the home page, edits entries
through the same forms the web UI posts, and reads releases from the calendar feed. Your Yamtrack
server needs no changes or plugins.

The downside is that a Yamtrack update which changes its pages can break YamSync until it catches up.
When a page can't be read, YamSync says so instead of showing empty data. It was developed and tested
against Yamtrack v0.26.3. All of this sits behind one interface (`YamtrackApi`), so a proper JSON API
can replace it later without touching the rest of the app.

Everything stays between your phone and your server. YamSync has no analytics or tracking. If
"stay signed in" is on, your password is stored encrypted on the device so the app can sign in again
when the session ends.

## Known limitations

- Yamtrack's own lists aren't shown yet.
- Servers behind single sign-on, HTTPS through a reverse proxy, and Yamtrack hosted under a sub-path
  are untested.
- The developer documents in [`docs/`](docs) are inherited from AniSync and partly describe the old
  AniList code.

## Building

You need JDK 17 and the Android SDK.

```sh
./gradlew assembleStableDebug       # debug APKs in app/build/outputs/apk/stable/debug/
./gradlew testStableDebugUnitTest   # unit tests
```

The page parsers are tested against saved Yamtrack pages in `app/src/test/resources/yamtrack/`. A
second set of tests runs against a live server when you set `YAMTRACK_TEST_URL`,
`YAMTRACK_TEST_USER` and `YAMTRACK_TEST_PASSWORD` (use a throwaway account: they create and delete
entries).

### Releases

Pushing a tag like `v1.0.1` runs `.github/workflows/build-release.yml`, which builds signed APKs and
attaches them to a GitHub release. It needs the repository secrets `SIGNING_KEY` (the base64-encoded
keystore), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Raise `versionName` in
`app/build.gradle.kts` to match the tag, because the in-app updater compares the two.

## Credits

- [AniSync](https://github.com/Marco-9456/AniSync) by Marco-9456. YamSync is a fork of it and keeps its
  interface, widgets and much of its code.
- [Yamtrack](https://github.com/FuzzyGrim/Yamtrack) by FuzzyGrim, the tracker this app talks to.
- [AnimeThemes](https://animethemes.moe) for openings and endings.

## License

GNU General Public License v3.0, the same as AniSync. See [LICENSE](LICENSE).

AniSync's README asks that forks not use the AniSync name for an AniList client. YamSync uses its own
name and is not an AniList client.
