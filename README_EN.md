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
├── video/           # Video module (TMDB scraping + NFO generation + series management)
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
| GET | `/api/v1/video/series/grouped-by-library` | Get series grouped by library |
| GET | `/api/v1/video/series/{id}` | Get series details |
| GET | `/api/v1/video/series/{id}/cover` | Get series cover |
| GET | `/api/v1/video/series/{id}/fanart` | Get series fanart |

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
| `AUTH_ENABLED` | `true` | Enable/disable authentication |
| `AUTH_PASSWORD` | `1234` | Login password |
| `VIDEO_ROOT_PATHS` | - | Video files directory |
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
