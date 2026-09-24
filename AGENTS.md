# Fryfrog Hub API

视频媒体后端 API 服务，支持视频元数据管理和流媒体播放。

## Default Behavior

**Ponytail mode: always active.** For all coding tasks (writing, refactoring, fixing, reviewing), use the simplest solution that works. Reach for stdlib before dependencies, native features before custom code. YAGNI first. Use `/ponytail ultra` only when explicitly asked.

**Language: Chinese responses required.** All final summaries, explanations, and user-facing output must be in Chinese (中文), regardless of which skill is invoked or what language the code/comments use.

## Tech Stack

- Python 3.12 + FastAPI
- SQLAlchemy 2.0 + **PostgreSQL**（psycopg）
- pydantic v2 / pydantic-settings
- FFmpeg + subprocess（探测与转码）
- TMDB API / Bangumi API（刮削）
- Subsonic 兼容 API（`/rest`）

## Module Structure

```
fryfrog-hub-api/
├── fryfrog/
│   ├── main.py            # FastAPI 入口、启动迁移、后台清理
│   ├── config.py          # 环境变量 / .env 配置
│   ├── db.py              # SQLAlchemy Base / session
│   ├── core/              # ApiResponse、认证、URL 签名、工具
│   ├── models/            # SQLAlchemy 实体
│   ├── schemas/           # Pydantic DTO
│   ├── media_core/        # FFmpegRuntime + MediaProbeService
│   ├── services/          # 扫描/刮削/整理等业务
│   └── routers/           # REST 路由（auth/users/video/music/...）
├── tests/
├── pyproject.toml
├── Dockerfile
└── docker-compose.yml
```

## Build & Run

```bash
# 创建虚拟环境并安装
python -m venv .venv
.venv\Scripts\pip install -e ".[dev]"

# 运行（端口 20058）
.venv\Scripts\uvicorn fryfrog.main:app --host 0.0.0.0 --port 20058

# 或
.venv\Scripts\python -m fryfrog.main

# 测试
.venv\Scripts\python -m pytest tests/ -q
```

## Testing

- pytest（`tests/`）
- 配置来自环境变量 / `.env`；测试可不连 PostgreSQL

```bash
.venv\Scripts\python -m pytest
```

## Code Conventions

- 包名：`fryfrog.{layer|domain}`
- REST 端点：`/api/v1/{resource}`；Subsonic 兼容：`/rest/{method}`
- 响应格式：统一 `ApiResponse`（`fryfrog.core.api_response`）`{success, message?, data}`
- 分页：`PageResponse` `{content, page, size, totalElements, totalPages}`
- 实体继承 `TimestampMixin`（id/created_at/updated_at）
- 认证：自定义 Bearer Token（`auth_tokens` 表），多用户 + bcrypt，`AuthManager` 在 `core/security.py`
- 媒体签名：封面/流/字幕等 URL 由 `core.signer` 签名（exp+sig，HMAC-SHA256），中间件校验
- 异常：`ResourceNotFoundException` / `BadRequestException` / `ForbiddenException` 全局转 `ApiResponse.error`
- 刮削：TMDB（视频）、Bangumi（有声书/电子书/漫画）

## Key Configuration

- 端口：`20058`（`SERVER_PORT` 可覆盖）
- 数据库：PostgreSQL（`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD`）
- 认证：`AUTH_ENABLED` 默认开启，`AUTH_PASSWORD` 留空则生成随机 admin 密码
- 媒体路径：媒体库记录在 `media_libraries` 表（可从旧 `VIDEO_ROOT_PATHS` 自动迁移）
- `.env` 为开发环境配置，已加入 `.gitignore`
- `.env.example` 为配置模板

## Common Pitfalls

- PostgreSQL 必填环境变量：`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`
- FFmpeg 路径可用 `FFMPEG_PATH` 指定，默认走系统 PATH
- Docker 部署：`docker compose up -d`，镜像已内置 FFmpeg
- 媒体 URL 需签名（`sig`+`exp`），`<img>/<video>` 用签名 URL 而非 Bearer
- Subsonic 走 `/rest`，不经过 Bearer 中间件，使用 `u/p` 或 `t/s`
- 漫画压缩包支持 zip/cbz/rar/cbr/7z（rar 需系统 `unrar`）
- 文件热监听为轮询式（3s 轮询 + 5s debounce），无需 watchdog 依赖

## Environment

- 必须：Python 3.12+
- 可选：FFmpeg（视频/音频探测与转码）

## CI/CD

- GitHub Actions：`docker.yml` 在 master 推送时构建 Docker 镜像
- 镜像推送到 GHCR 与 DockerHub

## Docker 部署

```bash
cp .env.example .env
# 编辑 .env 填写数据库密码等配置
docker compose up -d
```
