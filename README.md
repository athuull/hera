# Hera

A self-hosted music recommendation and automated downloader service powered by Last.fm, YouTube Music, and Downtify. Hera automatically discovers new music tailored to your listening taste, provides direct song and link downloading, and manages your local audio library.

## Features

- **Automated Cron Downloads**: Runs on a configurable schedule (e.g. nightly) to fetch personalized recommendations and download new tracks automatically up to a daily limit.
- **Direct Song & Artist Search**: Search songs by name directly across YouTube Music with instant track details, cover art preview, and single-click downloading.
- **Link & Stream Ingestion**: Paste Spotify or YouTube Music links (tracks, albums, playlists) for seamless automated downloading.
- **Personalized Recommendation Modes**:
  - **Hybrid**: Blends recent scrobbles, top artists, and listening genres.
  - **Top Artists**: Discovers tracks similar to your most-played artists.
  - **Recent Scrobbles**: Finds music similar to tracks you recently listened to.
  - **Top Genres / Tags**: Recommends tracks from your favorite genres.
  - **Manual Exploration**: Search directly by artist name, track similarity, or genre tags.
- **Intelligent Library Deduplication**: Multi-tier deduplication indexing (exact title normalization, unicode support, distinctive title extraction, and artist matching) across local files and ID3 tags before downloading to avoid duplicates.
- **Download History & Telemetry**: Full audit trail recording downloaded, skipped, and failed tracks with timestamps and reason logging.
- **Format & Metadata Management**: Configurable output formats (MP3 up to 320kbps CBR, FLAC, M4A, Opus), custom cover art resolution, synced lyrics (`.lrc`), and automatic `.webm` to `.mp3` format cleanup via ffmpeg.
- **Real-time Progress Dashboard**: Terminal-styled minimalist web interface with live dual-progress HUDs (track & batch level) and WebSocket activity feed.

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
