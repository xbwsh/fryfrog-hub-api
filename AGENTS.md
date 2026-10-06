# Fryfrog Hub API

视频媒体后端 API 服务，支持视频元数据管理和流媒体播放。

## Default Behavior

**Ponytail mode: always active.** For all coding tasks (writing, refactoring, fixing, reviewing), use the simplest solution that works. Reach for stdlib before dependencies, native features before custom code. YAGNI first. Use `/ponytail ultra` only when explicitly asked.

**Language: Chinese responses required.** All final summaries, explanations, and user-facing output must be in Chinese (中文), regardless of which skill is invoked or what language the code/comments use.

## Tech Stack

- Python 3.12 + FastAPI
- SQLAlchemy 2.0 + **SQLite**（单文件，零安装）
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
- 配置来自环境变量 / `.env`；测试用内存/临时 SQLite

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
- 数据库：SQLite（`SQLITE_PATH`，默认 `data/fryfrog.db`）
- 认证：`AUTH_ENABLED` 默认开启，`AUTH_PASSWORD` 留空则生成随机 admin 密码
- 媒体路径：媒体库记录在 `media_libraries` 表（可从旧 `VIDEO_ROOT_PATHS` 自动迁移）
- `.env` 为开发环境配置，已加入 `.gitignore`
- `.env.example` 为配置模板

## Common Pitfalls

- SQLite 文件路径可用 `SQLITE_PATH` 覆盖，备份时连同 `data/media_secret.key` 一起拷走
- FFmpeg 路径可用 `FFMPEG_PATH` 指定，默认走系统 PATH
- Docker 部署：`docker compose up -d`，镜像已内置 FFmpeg
- 媒体 URL 需签名（`sig`+`exp`），`<img>/<video>` 用签名 URL 而非 Bearer
- Subsonic 走 `/rest`，不经过 Bearer 中间件，使用 `u/p` 或 `t/s`
- 漫画压缩包支持 zip/cbz/rar/cbr/7z（rar 需系统 `unrar`）与 .pdf（`pypdfium2` 按页渲染）
- 文件热监听优先用 Linux inotify（ctypes 调 libc，无第三方依赖），不可用时退回轮询；5s debounce，`WATCHER_ENABLED=false` 可整体关闭
- 扫描会清理磁盘上已删除的记录：宽限期 `SCAN_MISSING_GRACE_SECONDS`（默认 1800s）满仍缺失才删行
- 护栏：本轮实见文件数低于上轮存量的 `SCAN_GUARD_MIN_RATIO`（默认 0.5）时整轮暂缓删除，避免挂载掉线清空库
- 扫描按 `mtime`+`size` 跳过未变文件的 ffprobe（`videos.media_probed_mtime`）
- `POST /media-libraries/scan` 与 `/{id}/scan` **立即返回**，扫描跑在后台线程；客户端靠
  轮询进度判断完成，因此扫描必须写进度表（见下）
- 扫描进度双键：`scan:{TYPE}:{id}` 供 `/scan/progress` 聚合；`pipeline:{id}` 供
  `/{id}/pipeline-progress` 的 `stage`/`currentItem`（客户端据此判断完成，不写会一直显示 idle）
- 同一库并发扫描会被护栏挡掉（热监听 + 周期扫描 + 手动可能同时触发）；占位超过
  `SCAN_STALE_AFTER_SECONDS`（默认 1800s）视为卡死并允许抢占
- `init_db()` 会按模型元数据为老库幂等补列（`create_all` 只建表不加列）

## Environment

- 必须：Python 3.12+
- 可选：FFmpeg（视频/音频探测与转码）

## CI/CD

- GitHub Actions：`docker.yml` 在 master 推送时构建 Docker 镜像（先跑 `pytest`
  再构建，测试红则不出镜像），推送 GHCR + DockerHub
- `smoke.yml`：镜像构建成功后，用 `.github/scripts/make_old_db.py` 造的**旧 schema 库**
  把新镜像真跑一遍（启动 → 登录 → 列库 → 扫描 → 校验就地补列），覆盖 pytest 够不到的
  容器启动/迁移/端口/认证；也可 `workflow_dispatch` 手动触发
- 升级已有部署前先备份 `./db`（SQLite + `media_secret.key`）

## Docker 部署

```bash
cp .env.example .env
# 编辑 .env 填写数据库密码等配置
docker compose up -d
```
