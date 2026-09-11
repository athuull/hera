# Hera

A self-hosted music recommendation and downloader service built with Spring Boot. Hera uses Last.fm to discover music tailored to your taste and downloads tracks via Downtify.

## Features

- **Automated Downloads**: Runs on a configurable cron schedule (e.g. nightly) to fetch recommendations and download new tracks automatically up to a daily limit.
- **Manual Search & Download**: Explore recommendations on-demand from the web UI, choose specific songs, and download selected tracks.
- **Recommendation Modes**:
  - **Hybrid**: Blends recent scrobbles, top artists, and listening genres.
  - **Top Artists**: Discovers tracks similar to your most-played artists.
  - **Recent Scrobbles**: Finds music similar to tracks you recently listened to.
  - **Top Genres / Tags**: Recommends tracks from your favorite genres.
  - **Manual Exploration**: Search directly by artist name, track similarity, or genre tags.
- **Library Deduplication**: Checks existing local files and tags before downloading to prevent duplicates.
- **Format Management**: Configurable output formats (MP3 up to 320kbps, FLAC, M4A, Opus), auto-cleanup of `.webm` files via ffmpeg, and optional lyric (.lrc) downloading.
- **Minimalist Web UI**: Terminal-styled dashboard with real-time download progress via WebSockets.

## Quick Start

### Docker Compose

```yaml
services:
  downtify:
    image: henriquesebastiao/downtify:latest
    container_name: hera-downtify
    volumes:
      - ${MUSIC_DIR:-./music}:/downloads
    networks:
      - hera-net
    restart: unless-stopped

  hera:
    image: athuul/hera:latest
    container_name: hera
    depends_on:
      - downtify
    ports:
      - "8080:8080"
    environment:
      - DOWNTIFY_API_URL=http://downtify:8000
      - MUSIC_DOWNLOAD_DIR=/music/downloads
      - APP_CONFIG_DIR=/config
    volumes:
      - ${MUSIC_DIR:-./music}:/music/downloads
      - ${CONFIG_DIR:-./config}:/config
    networks:
      - hera-net
    restart: unless-stopped

networks:
  hera-net:
    driver: bridge
```

By default, downloads save to `./music` and configuration to `./config`. To customize these paths, copy `.env.example` to `.env` (or set `MUSIC_DIR` and `CONFIG_DIR`):

```bash
cp .env.example .env
# Edit MUSIC_DIR=/path/to/your/music and CONFIG_DIR=/path/to/your/config
```

Start with:

```bash
docker compose up -d
```

Open `http://localhost:8080` and enter your Last.fm API key and username in the settings panel.

## Configuration

Settings can be managed directly in the web UI (saved to `/config/settings.json`) or set via environment variables:

| Variable | Default | Description |
|---|---|---|
| `DOWNTIFY_API_URL` | `http://localhost:8000` | URL of the Downtify backend |
| `MUSIC_DOWNLOAD_DIR` | `/music/downloads` | Path to downloaded music folder |
| `APP_CONFIG_DIR` | `./` | Path where settings.json is stored |
| `LASTFM_API_KEY` | *(empty)* | Last.fm API key |
| `LASTFM_USERNAME` | *(empty)* | Last.fm username |
| `CRON_SCHEDULE` | `0 0 2 * * *` | Cron expression for auto-download schedule |

## Acknowledgments

- Backend downloading powered by [Downtify](https://github.com/henriquesebastiao/downtify) by Henrique Sebastião.
- Recommendations powered by the [Last.fm API](https://www.last.fm/api).
