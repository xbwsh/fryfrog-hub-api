# Fryfrog Hub API

Video media backend API service for metadata management and streaming.

[中文文档](./README.md)

## Features

### Authentication

- **Password Login** - Password verification with Token generation
- **Token Management** - Logout and status query support
- **Configurable** - Enable/disable authentication via environment variables

### Media Library Management

- **Library CRUD** - Dynamically add, edit, delete media directories
- **Enable/Disable** - Enable or pause library scanning as needed
- **Unified Scanning** - One-click scan of all enabled libraries
- **Directory Browsing** - Browse server directories for frontend directory selector

### Video Module

- **Video Streaming** - Supports HTTP Range requests for resume playback
- **TMDB Scraping** - Automatically fetches movie/TV metadata from TMDB
- **NFO Generation** - Generates Kodi-compatible NFO metadata files
- **Cover Download** - Automatically downloads poster and fanart images
- **Episode Management** - Auto-detects season/episode numbers, groups by series
- **Series Management** - Dedicated video series API with cover and fanart
- **Watch Progress** - Records playback position for resume
- **File Watching** - Auto-detects new video files and indexes them
- **Live Transcoding** - 1080p/720p/480p transcoded streaming with subtitle burn-in
- **External Subtitles** - Lists and serves SRT/ASS/VTT subtitles from the video directory
- **Frame Selection** - Generates multi-position frame candidates for cover/fanart
- **Movie Logo** - Fetch/set TMDB wordmark logos (local cache + remote fallback)
- **Playlist** - Generates series M3U playlists (PotPlayer/IINA compatible)

### Music Module

- **Scan & Index** - Scans music libraries with ffprobe, builds artist/album/song index
- **Mojibake Repair** - Auto-repairs legacy GBK/Big5 tag encoding
- **Streaming** - Song streaming with cover art and lyrics
- **Favorites & Ratings** - Star and rating for songs/albums/artists
- **Playlists** - Playlist CRUD and bookmarks
- **Subsonic API** - `/rest` endpoints compatible with Subsonic clients

### Common Features

- **Swagger Docs** - Auto-generated API documentation with online testing
- **CORS Support** - Pre-configured for frontend integration
- **Docker Deployment** - Dockerfile and docker-compose.yml included
- **PostgreSQL** - PostgreSQL database
- **Virtual Threads** - Java 21 virtual threads enabled for improved concurrency
- **Periodic Scanning** - Configurable periodic scan interval for media library updates
- **System Settings** - Runtime dynamic configuration management
- **Log Export** - Export log files for developer troubleshooting

## Tech Stack

- Java 21 + Spring Boot 3.2.x
- Spring Data JPA + PostgreSQL
- Java 21 Virtual Threads
- FFmpeg + ProcessBuilder (video transcoding)
- TMDB API (video metadata scraping)
- Springdoc OpenAPI (Swagger docs)
- GitHub Actions (CI/CD Docker image build)

## Project Structure

```
fryfrog-hub-api/
├── app/             # Spring Boot entry point + global config/controllers
├── common/          # Shared entities, DTOs, utilities
├── video/           # Video module (TMDB scraping + NFO generation + series management + transcoding)
├── music/           # Music module (scan/index + streaming + Subsonic API)
└── pom.xml          # Parent POM
```

## Quick Start

### Prerequisites

- JDK 21+
- Maven 3.9+
- PostgreSQL
- FFmpeg (optional, needed for video features)
- Docker (optional, for docker-compose deployment)

### Local Development

```bash
# Clone the project
git clone https://github.com/xbwsh/fryfrog-hub-api.git
cd fryfrog-hub-api

# Configure environment variables (refer to .env.example)
cp .env.example .env
# Edit .env to fill in database and other configurations

# Start the application
mvn spring-boot:run -pl app
```

### Docker Deployment

```bash
# Copy and configure environment variables
cp .env.example .env
# Edit .env to fill in database password and other configurations

# Start services
docker compose up -d
```

Docker Compose will start both PostgreSQL and the API service, with data persisted to Docker volume.

### Production Deployment

```bash
# Set environment variables
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=fryfroghub
export DB_USERNAME=your_db_user
export DB_PASSWORD=your_db_password
export VIDEO_ROOT_PATHS=/path/to/your/video
export TMDB_API_KEY=your_tmdb_api_key  # Optional, for video scraping
export AUTH_PASSWORD=your_password      # Optional, login password

# Start the application
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar
```

## API Documentation

Access Swagger UI after starting the application:

http://localhost:20058/swagger-ui.html

### Authentication Endpoints

| Method | Path | Description |
|------|------|------|
| POST | `/api/v1/auth/login` | Login (returns Token) |
| POST | `/api/v1/auth/logout` | Logout (invalidate Token) |
| GET | `/api/v1/auth/status` | Auth status (check if login required) |

### Media Library Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/media-libraries` | Get all libraries |
| GET | `/api/v1/media-libraries/{id}` | Get library details |
| POST | `/api/v1/media-libraries` | Create library |
| PUT | `/api/v1/media-libraries/{id}` | Update library |
| DELETE | `/api/v1/media-libraries/{id}` | Delete library |
| PUT | `/api/v1/media-libraries/{id}/toggle` | Enable/disable library |
| POST | `/api/v1/media-libraries/scan` | Scan all enabled libraries |
| POST | `/api/v1/media-libraries/{id}/scan` | Scan specific library |
| GET | `/api/v1/media-libraries/browse` | Browse server directories |

### Video Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/video/{id}` | Get video details |
| PUT | `/api/v1/video/{id}/metadata` | Edit video metadata |
| GET | `/api/v1/video/search/title?q=xxx` | Search by title |
| GET | `/api/v1/video/search/director?q=xxx` | Search by director |
| GET | `/api/v1/video/favorites` | Favorite videos |
| PUT | `/api/v1/video/{id}/favorite?status=true` | Set favorite status |
| GET | `/api/v1/video/{id}/actors` | Get actors |
| GET | `/api/v1/video/actor/{actorId}` | Get actor detail (bio/birthday/credits, DB-cached) |
| GET | `/api/v1/video/actor/{actorId}/works` | Get actor works (grouped by series, paginated) |
| GET | `/api/v1/video/actor/{actorId}/refresh` | Refresh actor detail cache (admin) |
| GET | `/api/v1/video/{id}/nfo` | Get NFO content |
| GET | `/api/v1/video/{id}/progress` | Get watch progress |
| PUT | `/api/v1/video/{id}/progress` | Save watch progress |
| PUT | `/api/v1/video/{id}/watched` | Set watched status |
| DELETE | `/api/v1/video/{id}/progress` | Clear watch progress |

#### Streaming & Subtitles

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/video/{id}/stream` | Stream video (Range supported) |
| GET | `/api/v1/video/{id}/stream/transcode?quality=1080p` | Transcoded streaming |
| GET | `/api/v1/video/{id}/playlist.m3u` | Series M3U playlist |
| GET | `/api/v1/video/{id}/subtitles` | List external subtitles |
| GET | `/api/v1/video/{id}/subtitles/{filename}` | Get subtitle file |

#### Image Resources

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/video/{id}/cover` | Get cover image |
| GET | `/api/v1/video/{id}/fanart` | Get fanart image |
| GET | `/api/v1/video/actor/{actorId}/image` | Get actor image |
| GET | `/api/v1/video/{id}/logo` | Get movie logo |
| GET | `/api/v1/video/{id}/logo-options` | List logo options |
| POST | `/api/v1/video/{id}/logo` | Set movie logo |
| POST | `/api/v1/video/{id}/frames` | Generate frame candidates |
| GET | `/api/v1/video/{id}/frames/{index}` | Get candidate frame |
| POST | `/api/v1/video/{id}/frames/select` | Select frame as cover/fanart |

#### TMDB Scraping & Batch Tasks (admin)

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/video/tmdb/search?q=xxx` | Search TMDB |
| POST | `/api/v1/video/{id}/tmdb/bind` | Bind TMDB metadata (async) |
| POST | `/api/v1/video/{id}/tmdb/unbind` | Unbind TMDB metadata |
| POST | `/api/v1/video/{id}/tmdb/refresh` | Refresh TMDB metadata |
| POST | `/api/v1/video/tmdb/rescrape-library/{libraryId}` | Rescrape library (async) |
| POST | `/api/v1/video/refresh-all-actors` | Batch refresh actors (async) |
| POST | `/api/v1/video/{id}/refresh-logo` | Refresh single movie logo |
| POST | `/api/v1/video/refresh-all-logos` | Batch refresh logos (async) |
| POST | `/api/v1/video/refresh-all-resolutions` | Batch probe resolutions (async) |
| GET | `/api/v1/video/scrape/progress?module=xxx` | Scrape/batch task progress |
| POST | `/api/v1/video/{id}/nfo` | Generate NFO file (admin) |
| POST | `/api/v1/video/{id}/covers` | Download covers (admin) |

### Video Series Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/video/series` | Get all series (including standalone) |
| GET | `/api/v1/video/series/grouped-by-library` | Get series grouped by library |
| GET | `/api/v1/video/series/{id}` | Get series details |
| GET | `/api/v1/video/series/{id}/cover` | Get series cover |
| GET | `/api/v1/video/series/{id}/fanart` | Get series fanart |
| GET | `/api/v1/video/series/{id}/logo` | Get series logo |
| GET | `/api/v1/video/series/{id}/logo-options` | List series logo options |
| POST | `/api/v1/video/series/{id}/refresh-logo` | Refresh series logo |
| POST | `/api/v1/video/series/{id}/logo` | Set series logo |
| GET | `/api/v1/video/series/{id}/season/{seasonNumber}/cover` | Get season cover |
| POST | `/api/v1/video/series/{id}/refresh-season-covers` | Refresh series season assets |
| POST | `/api/v1/video/series/refresh-all-season-covers` | Batch refresh season covers |
| PUT | `/api/v1/video/series/{id}/favorite` | Set series favorite |
| PUT | `/api/v1/video/series/{id}/metadata` | Edit series metadata |
| GET | `/api/v1/video/series/{id}/actors` | Get series actors |
| POST | `/api/v1/video/series/{id}/frames/select` | Set series fanart |
| GET | `/api/v1/video/series/calendar` | Calendar |
| GET | `/api/v1/video/series/favorites` | Favorite series |

### Music Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/music/home` | Home aggregated data |
| GET | `/api/v1/music/songs` | Song list |
| GET | `/api/v1/music/songs/{id}` | Song details |
| GET | `/api/v1/music/songs/{id}/stream` | Song streaming |
| GET | `/api/v1/music/songs/{id}/cover` | Song cover |
| GET | `/api/v1/music/songs/{id}/lyrics` | Song lyrics |
| GET | `/api/v1/music/albums` | Album list |
| GET | `/api/v1/music/albums/{id}` | Album details |
| GET | `/api/v1/music/albums/{id}/songs` | Album songs |
| GET | `/api/v1/music/albums/{id}/cover` | Album cover |
| GET | `/api/v1/music/artists` | Artist list |
| GET | `/api/v1/music/artists/{id}` | Artist details |
| GET | `/api/v1/music/artists/{id}/cover` | Artist cover |
| GET | `/api/v1/music/genres` | Genre list |
| GET/POST | `/api/v1/music/playlists` | Playlists list/create |
| GET/PUT/DELETE | `/api/v1/music/playlists/{id}` | Playlist detail/update/delete |
| GET/POST/DELETE | `/api/v1/music/bookmarks[/{songId}]` | Bookmarks management |
| GET/PUT | `/api/v1/music/play-queue` | Play queue get/update |
| PUT | `/api/v1/music/{type}/{id}/star` | Set star favorite |
| PUT | `/api/v1/music/{type}/{id}/rating` | Set rating |
| POST | `/api/v1/music/scrobble` | Report playback |
| POST | `/api/v1/music/scan` | Scan music library |
| POST | `/api/v1/music/organize` | Organize music directory |
| * | `/rest` | Subsonic compatible API |

### Settings Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/settings` | Get all settings |
| GET | `/api/v1/settings/{key}` | Get single setting |
| PUT | `/api/v1/settings/{key}` | Update setting |

### Log Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/logs` | List available log files |
| GET | `/api/v1/logs/{fileName}` | Export log file |

### Response Format

```json
{
  "success": true,
  "message": "optional message",
  "data": { ... }
}
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|------|--------|------|
| `SERVER_PORT` | `20058` | Server port |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `fryfroghub` | Database name |
| `DB_USERNAME` | - | Database username |
| `DB_PASSWORD` | - | Database password |
| `DB_POOL_SIZE` | `10` | Database connection pool size |
| `AUTH_ENABLED` | `true` | Enable/disable authentication |
| `AUTH_PASSWORD` | - | Initial admin password (empty generates a random one and prints it to logs) |
| `AUTH_TOKEN_TTL` | `604800` | Token TTL (seconds), default 7 days |
| `AUTH_LOGIN_MAX_FAILURES` | `5` | Login failure lockout threshold |
| `AUTH_LOGIN_LOCK_MINUTES` | `15` | Lockout duration (minutes) |
| `VIDEO_ROOT_PATHS` | - | Video files directory |
| `VIDEO_BASE_URL` | - | Override external base URL for M3U etc. (reverse proxy/NAT) |
| `TMDB_API_KEY` | - | TMDB API Key (for video scraping) |
| `TMDB_LANGUAGE` | `zh-CN` | TMDB language |
| `TMDB_IMAGE_SIZE` | `original` | TMDB image size |
| `TMDB_INCLUDE_ADULT` | `true` | TMDB include adult content |
| `WATCHER_PERIODIC_SCAN` | `true` | Enable periodic scanning |
| `PERIODIC_SCAN_INTERVAL` | `30` | Periodic scan interval (minutes) |
| `FFMPEG_PATH` | - | FFmpeg path (optional, uses system PATH if not set) |
| `LOG_LEVEL` | `INFO` | Log level |

## Supported Formats

| Type | Format | Description |
|------|------|------|
| **Video** | MP4, MKV, AVI, MOV, FLV, WMV, WebM, M4V | Supports Range requests, resume playback |
| **Music** | MP3, FLAC, WAV, M4A, AAC, OGG, OPUS, WMA, APE, MPC, DSF, etc. | Tag index via ffprobe |

## Development Guide

### Running Tests

```bash
# Run all tests
mvn test

# Run video module tests
mvn test -pl video

# Run single test class
mvn test -pl video -Dtest=VideoControllerTest
```

### Code Conventions

- Package naming: `com.fryfrog.hub.{module}.{layer}`
- REST endpoints: `/api/v1/{resource}`
- Response format: Unified `ApiResponse<T>`
- Entities extend `BaseEntity` (includes id, createdAt, updatedAt)
- Authentication: Custom Bearer Token auth (not Spring Security)
- Exception handling: `@RestControllerAdvice` global exception handler

## License

MIT
