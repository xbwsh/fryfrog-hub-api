# Fryfrog Hub API

A unified media backend API service supporting metadata management and streaming for music, comics, ebooks, and video.

[中文文档](./README.md)

## Features

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

### Common Features

- **Swagger Docs** - Auto-generated API documentation with online testing
- **CORS Support** - Pre-configured for frontend integration
- **Docker Deployment** - Dockerfile and docker-compose.yml included
- **H2 Dev Database** - In-memory database for development
- **SQLite Production Database** - No external database required

## Tech Stack

- Java 21 + Spring Boot 3.2.x
- Spring Data JPA + H2 (dev) / SQLite (prod)
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
├── app/             # Spring Boot entry point
├── common/          # Shared entities, DTOs, utilities
├── music/           # Music module (jaudiotagger)
├── video/           # Video module (TMDB scraping + NFO generation)
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

# Start the application (dev mode, uses H2 in-memory database)
mvn spring-boot:run -pl app

# Or build and run
mvn clean package -DskipTests
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar
```

### Docker Deployment

**NAS / Docker UI Deployment (Recommended)**

1. Pull the image: `ghcr.io/xbwsh/fryfrog-hub-api:latest`
2. Create a container, set network mode to `host`
3. Add volume mounts (configure your actual paths in the UI):

| Container Path | Purpose | Example Host Path |
|---|---|---|
| `/data` | Database | `/vol1/docker/fryfrog-hub/data` |
| `/data/media/music` | Music | `/vol1/1000/music` |
| `/data/media/comic` | Comics | `/vol1/1000/comic` |
| `/data/media/video` | Videos | `/vol1/1000/video` |
| `/data/media/ebook` | Ebooks | `/vol1/1000/ebook` |

4. Set environment variable: `SPRING_PROFILES_ACTIVE=prod`
5. Optional environment variables (set in UI):
   - `TMDB_API_KEY` — TMDB API Key for video scraping
   - `PROXY_HOST` / `PROXY_PORT` — Proxy settings

**docker-compose Deployment**

```bash
docker compose up -d
```

Default pulls the GHCR image. For local builds, edit `docker-compose.yml`, comment out the `image` line, and uncomment the `build` line.

### Production Deployment

```bash
# Set environment variables
export MUSIC_ROOT_PATH=/path/to/your/music
export VIDEO_ROOT_PATH=/path/to/your/video
export COMIC_ROOT_PATH=/path/to/your/comic
export EBOOK_ROOT_PATH=/path/to/your/ebook
export TMDB_API_KEY=your_tmdb_api_key  # Optional, for video scraping
export PROXY_HOST=127.0.0.1            # Optional, proxy address
export PROXY_PORT=7890                 # Optional, proxy port

# Start the application
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

## API Documentation

Access Swagger UI after starting the application:

http://localhost:20058/swagger-ui.html

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
| `MUSIC_ROOT_PATH` | `./media-library/music` | Music files directory |
| `VIDEO_ROOT_PATH` | `./media-library/video` | Video files directory |
| `COMIC_ROOT_PATH` | `./media-library/comic` | Comic files directory |
| `EBOOK_ROOT_PATH` | `./media-library/ebook` | Ebook files directory |
| `TMDB_API_KEY` | - | TMDB API Key (for video scraping) |
| `TMDB_AUTO_SCRAPE` | `false` | Auto-scrape videos on scan |
| `PROXY_HOST` | - | Proxy address |
| `PROXY_PORT` | - | Proxy port |

## Supported Formats

| Type | Formats |
|------|----------|
| Audio | MP3, FLAC, OGG, WAV, AAC, M4A |
| Video | MP4, MKV, AVI, MOV, FLV, WMV, WebM, M4V |
| Comic | CBZ, CBR, ZIP, RAR |
| Ebook | EPUB, PDF, MOBI, AZW, AZW3, FB2, TXT |

## Development Guide

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

## License

MIT
