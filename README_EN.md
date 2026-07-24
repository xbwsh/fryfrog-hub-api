# Fryfrog Hub API

A unified media backend API service supporting metadata management and streaming for music, comics, ebooks, and video.

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

### Music Module

- **Streaming Playback** - Supports HTTP Range requests for resume playback
- **Lyrics Extraction** - Automatically extracts embedded lyrics from audio files
- **Cover Art Extraction** - Automatically extracts and caches album cover art
- **Auto Scanning** - Scans media library on startup, watches for file changes
- **Favorites** - Add/remove music from favorites
- **Metadata Management** - Full CRUD operations for music metadata

### Video Module

- **Video Streaming** - Supports HTTP Range requests for resume playback
- **TMDB Scraping** - Automatically fetches movie/TV metadata from TMDB
- **NFO Generation** - Generates Kodi-compatible NFO metadata files
- **Cover Download** - Automatically downloads poster and fanart images
- **Episode Management** - Auto-detects season/episode numbers, groups by series
- **Series Management** - Dedicated video series API with cover and fanart
- **Watch Progress** - Records playback position for resume
- **File Watching** - Auto-detects new video files and indexes them

### Comic Module

- **Online Reading** - Supports CBZ/CBR/ZIP/RAR formats, page-by-page browsing
- **Thumbnails** - Auto-generates comic thumbnail cache
- **Cover Extraction** - Auto-extracts cover images from archives
- **Auto Scanning** - Scans comic directory on startup
- **Favorites** - Add/remove comics from favorites
- **Reading Progress** - Records current page for resume reading

### Ebook Module

- **Online Reading** - Supports EPUB/PDF/TXT/MOBI/AZW/FB2 formats
- **Chapter Detection** - Auto-detects Chinese chapter titles
- **Chapter Navigation** - Browse by chapter, returns chapter list and content
- **File Download** - Direct ebook file download
- **Cover Display** - Auto-generates title placeholder when no cover available
- **Reading Progress** - Records current reading position for resume

### Media Series Management

- **Series Grouping** - Auto-groups comics/ebooks by series
- **Character Management** - Series character info and image management
- **Favorites** - Support series favorites
- **Re-scrape** - Re-fetch series synopsis, cover, and other metadata

### Common Features

- **Swagger Docs** - Auto-generated API documentation with online testing
- **CORS Support** - Pre-configured for frontend integration
- **Docker Deployment** - Dockerfile and docker-compose.yml included
- **SQLite Storage** - Lightweight database, no external setup required
- **Virtual Threads** - Java 21 virtual threads enabled for improved concurrency
- **Periodic Scanning** - Configurable periodic scan interval for media library updates
- **System Settings** - Runtime dynamic configuration management
- **Log Export** - Export log files for developer troubleshooting

## Tech Stack

- Java 21 + Spring Boot 3.2.x
- Spring Data JPA + SQLite
- Java 21 Virtual Threads
- jaudiotagger (music metadata extraction)
- Thumbnails4j (comic thumbnails)
- Apache Tika (comic/ebook metadata extraction)
- FFmpeg + ProcessBuilder (audio/video transcoding)
- TMDB API (video metadata scraping)
- Springdoc OpenAPI (Swagger docs)
- GitHub Actions (CI/CD Docker image build)

## Project Structure

```
fryfrog-hub-api/
├── app/             # Spring Boot entry point + global config/controllers
├── common/          # Shared entities, DTOs, utilities
├── music/           # Music module (jaudiotagger)
├── video/           # Video module (TMDB scraping + NFO generation + series management)
├── comic/           # Comic module (CBZ/CBR + thumbnails)
├── ebook/           # Ebook module (EPUB/PDF + chapter detection)
└── pom.xml          # Parent POM
```

## Quick Start

### Prerequisites

- JDK 21+
- Maven 3.9+
- Docker (optional, for docker-compose deployment)

### Local Development

```bash
# Clone the project
git clone https://github.com/xbwsh/fryfrog-hub-api.git
cd fryfrog-hub-api

# Create dev configuration (optional, uses application.yml defaults if not created)
cp app/src/main/resources/application-dev.yml.example app/src/main/resources/application-dev.yml
```

**Dev Configuration**: `application-dev.yml` is in `.gitignore` and won't be committed. Customize for your local environment:
- Database path
- Media directories
- Proxy settings
- Auto-scrape toggles

```bash
# Start the application (dev mode)
mvn spring-boot:run -pl app

# Or build and run
mvn clean package -DskipTests
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar
```

### Docker Deployment

**NAS / Docker UI Deployment (Recommended)**

1. Pull the image: `ghcr.io/xbwsh/fryfrog-hub-api:latest`
2. Create a container, **set network mode to host**
3. Add volume mounts (configure your actual paths in the UI):

| Container Path | Purpose | Example Host Path |
|---|---|---|
| `/app/data` | Database | `/vol1/docker/fryfrog-hub/data` |
| `/app/data/media/music` | Music | `/vol1/1000/music` |
| `/app/data/media/video` | Videos | `/vol2/1000/Media` |
| `/app/data/media/comic` | Comics | `/vol1/1000/comic` |
| `/app/data/media/ebook` | Ebooks | `/vol1/1000/ebook` |

4. Optional environment variables (set in UI):
   - `TMDB_API_KEY` — TMDB API Key for video scraping
   - `AUTH_PASSWORD` — Login password (default `1234`)
   - `AUTH_ENABLED` — Enable/disable authentication (default `true`)
5. Access `http://NAS_IP:20058/swagger-ui.html` after startup

**docker-compose Deployment**

```yaml
services:
  fryfrog-hub-api:
    image: ghcr.io/xbwsh/fryfrog-hub-api:latest
    container_name: fryfrog-hub-api
    restart: unless-stopped
    network_mode: host
    environment:
      - AUTH_PASSWORD=your_password
      - TMDB_API_KEY=your_tmdb_api_key
    volumes:
      - ./data:/app/data
      # - /your/music/path:/app/data/media/music
      # - /your/video/path:/app/data/media/video
```

```bash
docker compose up -d
```

Default pulls the GHCR image. For local builds, edit `docker-compose.yml`, comment out the `image` line, and uncomment the `build` line.

### Production Deployment

```bash
# Set environment variables
export MUSIC_ROOT_PATHS=/path/to/your/music
export VIDEO_ROOT_PATHS=/path/to/your/video
export COMIC_ROOT_PATHS=/path/to/your/comic
export EBOOK_ROOT_PATHS=/path/to/your/ebook
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

### Music Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/music` | Get all tracks |
| GET | `/api/v1/music/{id}` | Get track details |
| GET | `/api/v1/music/{id}/stream` | Stream audio (supports Range) |
| GET | `/api/v1/music/{id}/cover` | Get cover image |
| PUT | `/api/v1/music/{id}/favorite` | Toggle favorite status |
| GET | `/api/v1/music/favorites` | Get favorites list |
| GET | `/api/v1/music/search/title?q=xxx` | Search by title |
| GET | `/api/v1/music/search/artist?q=xxx` | Search by artist |
| POST | `/api/v1/music/scan?path=xxx` | Scan music directory |

### Video Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/video` | Get all videos |
| GET | `/api/v1/video/{id}` | Get video details |
| GET | `/api/v1/video/{id}/stream` | Stream video |
| GET | `/api/v1/video/{id}/cover` | Get cover image |
| PUT | `/api/v1/video/{id}/favorite` | Toggle favorite status |
| GET | `/api/v1/video/{id}/progress` | Get watch progress |
| PUT | `/api/v1/video/{id}/progress` | Save watch progress |
| GET | `/api/v1/video/tmdb/search?q=xxx` | Search TMDB |
| POST | `/api/v1/video/{id}/tmdb/bind` | Bind TMDB metadata |
| POST | `/api/v1/video/tmdb/auto-scrape` | Auto-scrape all videos |
| POST | `/api/v1/video/scan?path=xxx` | Scan video directory |

### Video Series Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/video/series` | Get all series (including standalone) |
| GET | `/api/v1/video/series/{id}` | Get series details |
| GET | `/api/v1/video/series/{id}/cover` | Get series cover |
| GET | `/api/v1/video/series/{id}/fanart` | Get series fanart |

### Comic Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/comic` | Get all comics |
| GET | `/api/v1/comic/{id}` | Get comic details |
| GET | `/api/v1/comic/{id}/cover` | Get cover image |
| GET | `/api/v1/comic/{id}/pages` | Get page list |
| GET | `/api/v1/comic/{id}/pages/{pageNum}` | Get page image |
| PUT | `/api/v1/comic/{id}/favorite` | Toggle favorite status |
| GET | `/api/v1/comic/{id}/progress` | Get reading progress |
| PUT | `/api/v1/comic/{id}/progress` | Save reading progress |
| POST | `/api/v1/comic/scan?path=xxx` | Scan comic directory |

### Ebook Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/ebook` | Get all ebooks |
| GET | `/api/v1/ebook/{id}` | Get ebook details |
| GET | `/api/v1/ebook/{id}/cover` | Get cover image |
| GET | `/api/v1/ebook/{id}/read` | Read ebook online |
| GET | `/api/v1/ebook/{id}/chapters` | Get chapter list |
| GET | `/api/v1/ebook/{id}/chapters/{num}` | Get chapter content |
| GET | `/api/v1/ebook/{id}/download` | Download ebook file |
| PUT | `/api/v1/ebook/{id}/favorite` | Toggle favorite status |
| GET | `/api/v1/ebook/{id}/progress` | Get reading progress |
| PUT | `/api/v1/ebook/{id}/progress` | Save reading progress |
| POST | `/api/v1/ebook/scan?path=xxx` | Scan ebook directory |

### Media Series Endpoints

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/media/series` | Get series list (filterable by type) |
| GET | `/api/v1/media/series/{id}` | Get series details |
| GET | `/api/v1/media/series/{id}/cover` | Get series cover |
| GET | `/api/v1/media/series/{id}/characters` | Get series characters |
| GET | `/api/v1/media/series/character/{id}/image` | Get character image |
| PUT | `/api/v1/media/series/{id}/favorite` | Toggle series favorite |
| POST | `/api/v1/media/series/{id}/rescrape` | Re-scrape series |

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
| `DB_PATH` | `./fryfrog.db` | SQLite database path |
| `AUTH_ENABLED` | `true` | Enable/disable authentication |
| `AUTH_PASSWORD` | `1234` | Login password |
| `MUSIC_ROOT_PATHS` | - | Music files directory |
| `VIDEO_ROOT_PATHS` | - | Video files directory |
| `COMIC_ROOT_PATHS` | - | Comic files directory |
| `EBOOK_ROOT_PATHS` | - | Ebook files directory |
| `TMDB_API_KEY` | - | TMDB API Key (for video scraping) |
| `TMDB_LANGUAGE` | `zh-CN` | TMDB language |
| `TMDB_IMAGE_SIZE` | `original` | TMDB image size |
| `TMDB_INCLUDE_ADULT` | `true` | TMDB include adult content |
| `WATCHER_PERIODIC_SCAN` | `true` | Enable periodic scanning |
| `PERIODIC_SCAN_INTERVAL` | `30` | Periodic scan interval (minutes) |
| `FFMPEG_PATH` | - | FFmpeg path (optional, uses system PATH if not set) |
| `LOG_LEVEL` | `INFO` | Log level |

## Supported Formats

| Type | Formats |
|------|----------|
| Audio | MP3, FLAC, OGG, WAV, AAC, M4A |
| Video | MP4, MKV, AVI, MOV, FLV, WMV, WebM, M4V |
| Comic | CBZ, CBR, ZIP, RAR |
| Ebook | EPUB, PDF, MOBI, AZW, AZW3, FB2, TXT |

## Development Guide

### Prerequisites

- JDK 21+
- Maven 3.9+
- Docker (optional, for docker-compose deployment)

### Local Development

```bash
# Clone the project
git clone https://github.com/xbwsh/fryfrog-hub-api.git
cd fryfrog-hub-api

# Start the application (dev mode)
mvn spring-boot:run -pl app

# Or build and run
mvn clean package -DskipTests
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar
```

### Running Tests

```bash
# Run all tests
mvn test

# Run single module tests
mvn test -pl music
mvn test -pl video
mvn test -pl comic
mvn test -pl ebook

# Run single test class
mvn test -pl music -Dtest=MusicControllerStreamingTest
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
