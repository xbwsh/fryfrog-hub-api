# Fryfrog Hub API

视频媒体后端 API 服务，支持视频元数据管理和流媒体播放。

[English](./README_EN.md)

## 功能特性 / Features

### 认证系统 / Authentication

-   **密码登录** - 支持密码验证，返回 Token
-   **Token 管理** - 支持登出、状态查询
-   **可配置** - 通过环境变量启用/禁用认证

### 媒体资源库管理 / Media Library Management

-   **资源库 CRUD** - 动态添加、编辑、删除媒体目录
-   **启用/禁用** - 按需启用或暂停扫描资源库
-   **统一扫描** - 一键扫描所有启用的资源库
-   **目录浏览** - 浏览服务器目录，用于前端目录选择器

### 视频模块 / Video Module

-   **视频流播放** - 支持 HTTP Range 请求断点续播
-   **TMDB 刮削** - 自动从 TMDB 获取电影/电视剧元数据
-   **NFO 生成** - 生成 Kodi 兼容的 NFO 元数据文件
-   **封面下载** - 自动下载竖屏海报和横屏背景图
-   **剧集管理** - 自动识别季数/集数，按系列分组
-   **系列管理** - 独立的视频系列 API，支持系列封面和背景图
-   **季级别海报** - 支持每季独立的封面图片
-   **集封面** - 从 TMDB 获取每集的截图作为封面
-   **演员管理** - 自动下载演员头像，存储在系列根目录
-   **观看进度** - 记录播放位置，支持续播
-   **文件监控** - 自动检测新视频文件并索引
-   **批量刷新** - 支持批量刷新系列/电影的海报、封面和演员信息
-   **实时转码** - 1080p/720p/480p 转码流播放，支持字幕烧录
-   **外挂字幕** - 列出并播放视频目录下的 SRT/ASS/VTT 等字幕
-   **截帧选图** - 生成多位置关键帧候选，供用户选择作为封面/背景图
-   **电影 Logo** - 获取/设置 TMDB 字标 logo（本地缓存 + 远程兜底）
-   **播放列表** - 生成同系列 M3U 播放列表（PotPlayer/IINA 等可打开）

### 音乐模块 / Music Module

-   **扫描建库** - 遍历音乐资源库，用 ffprobe 读取标签建库（歌手/专辑/单曲）
-   **标签乱码修复** - 自动修复 GBK/Big5 等老编码标签乱码
-   **流媒体播放** - 单曲流播放与封面/歌词关联
-   **收藏与评分** - 歌曲/专辑/歌手星级与评分
-   **播放列表** - 播放列表 CRUD 与书签
-   **Subsonic 兼容 API** - 提供 `/rest` 端点，可对接 Subsonic 客户端

### 通用功能 / Common Features

-   **Swagger 文档** - 自动生成 API 文档，支持在线测试
-   **CORS 支持** - 已配置跨域，可直接对接前端
-   **Docker 部署** - 提供 Dockerfile 和 docker-compose.yml，支持一键部署
-   **PostgreSQL** - 使用 PostgreSQL 数据库
-   **虚拟线程** - 启用 Java 21 虚拟线程，提升并发性能
-   **定时扫描** - 支持配置定时扫描间隔，自动更新媒体库
-   **系统设置** - 运行时动态配置管理
-   **日志导出** - 导出日志文件，方便反馈开发者排查问题

## 技术栈 / Tech Stack

- Java 21 + Spring Boot 3.2.x
- Spring Data JPA + PostgreSQL
- Java 21 虚拟线程（Virtual Threads）
- FFmpeg + ProcessBuilder（视频转码）
- TMDB API（视频元数据刮削）
- Springdoc OpenAPI（Swagger 文档）
- GitHub Actions（自动构建 Docker 镜像）

## 项目结构 / Project Structure

```
fryfrog-hub-api/
├── app/             # Spring Boot 启动模块 + 全局配置/控制器
├── common/          # 共享实体、DTO、工具类
├── video/           # 视频模块（TMDB 刮削 + NFO 生成 + 系列管理 + 转码）
├── music/           # 音乐模块（扫描建库 + 播放 + Subsonic 兼容 API）
└── pom.xml          # Parent POM
```

## 快速开始 / Quick Start

### 环境要求 / Prerequisites

- JDK 21+
- Maven 3.9+
- PostgreSQL
- FFmpeg（可选，视频功能需要）
- Docker（可选，用于 docker-compose 部署）

### 本地开发 / Local Development

```bash
# 克隆项目
git clone https://github.com/xbwsh/fryfrog-hub-api.git
cd fryfrog-hub-api

# 配置环境变量（参考 .env.example）
cp .env.example .env
# 编辑 .env 填写数据库等配置

# 启动应用
mvn spring-boot:run -pl app
```

### Docker 部署 / Docker Deployment

```bash
# 复制环境变量模板并配置
cp .env.example .env
# 编辑 .env 填写数据库密码等配置

# 启动服务
docker compose up -d
```

Docker Compose 会同时启动 PostgreSQL 和 API 服务，数据持久化到 Docker volume。

### 生产部署 / Production Deployment

```bash
# 设置环境变量
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=fryfroghub
export DB_USERNAME=your_db_user
export DB_PASSWORD=your_db_password
export VIDEO_ROOT_PATHS=/path/to/your/video
export TMDB_API_KEY=your_tmdb_api_key  # 可选，用于视频刮削
export AUTH_PASSWORD=your_password      # 可选，首次启动时作为 admin 初始密码（留空则自动生成随机密码）

# 启动应用
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar
```

## API 文档 / API Documentation

启动应用后访问 Swagger UI：

http://localhost:20058/swagger-ui.html

### 认证接口 / Authentication Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/login` | 登录（用户名+密码，返回 Token 与用户信息） |
| POST | `/api/v1/auth/logout` | 登出（注销当前 Token） |
| GET | `/api/v1/auth/status` | 认证状态（前端判断是否需要登录） |
| GET | `/api/v1/auth/me` | 当前登录用户信息 |

### 用户管理接口 / User Management Endpoints（需管理员）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/users` | 用户列表 |
| GET | `/api/v1/users/me` | 当前用户信息 |
| GET | `/api/v1/users/{id}` | 用户详情（管理员或本人） |
| POST | `/api/v1/users` | 创建用户 |
| PUT | `/api/v1/users/{id}` | 更新用户（昵称/头像/角色/启用状态） |
| DELETE | `/api/v1/users/{id}` | 删除用户 |
| PUT | `/api/v1/users/me/password` | 修改自己的密码（需原密码） |
| PUT | `/api/v1/users/{id}/password` | 管理员重置指定用户密码 |
| GET | `/api/v1/users/{id}/libraries` | 查看用户可访问的媒体库（管理员） |
| PUT | `/api/v1/users/{id}/libraries` | 分配用户可访问的媒体库，body `{"libraryIds":[...]}`（管理员，幂等替换） |

### 多用户数据隔离与媒体签名 / Per-user Data & Signed Media URLs

- **观看进度 / 收藏按用户隔离**：`watch_progress` 以 `(user_id, video_id)` 唯一，收藏存于 `favorites(user_id, content_type, content_id)` 表，不同用户互不影响。认证关闭时退化为匿名全局共享。
- **媒体库授权（RBAC-Lite）**：管理员可给普通用户分配可访问的媒体库（`user_libraries` 表）。ADMIN/匿名/后台任务可见全部启用库；普通用户仅可见「被分配的库 ∩ 启用库」，未分配则为空列表，且**未归属任何媒体库的游离内容（libraryId 为空）对普通用户不可见**。
- **写操作权限**：默认所有状态变更请求（创建/修改/删除/扫描/刮削/设置图集等）仅 ADMIN 可执行；普通用户仅保留查看 + 自身数据写入（收藏、观看进度、修改自己密码、登出）。媒体库列表对普通用户只返回其授权库，目录浏览/系统设置/日志为管理功能。认证关闭时维持全开放。
- **媒体资源签名 URL**：视频封面、背景图、流、季封面、外挂字幕的 URL 均带 `exp`（过期时间）与 `sig`（HMAC 签名）参数，由后端 DTO 自动拼接，前端直接使用无需改造。启动时签名密钥随机生成，重启后旧 URL 失效，前端重新拉取列表即可。
- **历史数据**：首次启动自动将旧的全局进度与收藏迁移到初始管理员（admin）。
- **说明**：转码流（`/stream/transcode`）、演员头像、海报占位图等端点仍维持公开放行。

### 媒体资源库接口 / Media Library Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/media-libraries` | 获取所有资源库 |
| GET | `/api/v1/media-libraries/{id}` | 获取资源库详情 |
| POST | `/api/v1/media-libraries` | 创建资源库 |
| PUT | `/api/v1/media-libraries/{id}` | 更新资源库 |
| DELETE | `/api/v1/media-libraries/{id}` | 删除资源库 |
| PUT | `/api/v1/media-libraries/{id}/toggle` | 启用/禁用资源库 |
| POST | `/api/v1/media-libraries/scan` | 扫描所有启用的资源库 |
| POST | `/api/v1/media-libraries/{id}/scan` | 扫描指定资源库 |
| GET | `/api/v1/media-libraries/browse` | 浏览服务器目录 |

### 视频接口 / Video Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/{id}` | 获取视频详情 |
| PUT | `/api/v1/video/{id}/metadata` | 编辑视频元数据 |
| GET | `/api/v1/video/search/title?q=xxx` | 按标题搜索 |
| GET | `/api/v1/video/search/director?q=xxx` | 按导演搜索 |
| GET | `/api/v1/video/favorites` | 收藏列表 |
| PUT | `/api/v1/video/{id}/favorite?status=true` | 设置收藏状态 |
| GET | `/api/v1/video/{id}/actors` | 获取演员列表 |
| GET | `/api/v1/video/actor/{actorId}` | 获取演员详情（简介/生日/作品，落库缓存） |
| GET | `/api/v1/video/actor/{actorId}/works` | 获取演员作品列表（按系列聚合，分页） |
| GET | `/api/v1/video/actor/{actorId}/refresh` | 刷新演员详情缓存（管理员） |
| GET | `/api/v1/video/{id}/nfo` | 获取 NFO 内容 |
| GET | `/api/v1/video/{id}/progress` | 获取观看进度 |
| PUT | `/api/v1/video/{id}/progress` | 保存观看进度 |
| PUT | `/api/v1/video/{id}/watched` | 设置已观看状态 |
| DELETE | `/api/v1/video/{id}/progress` | 清除观看进度 |

#### 流媒体与字幕 / Streaming & Subtitles

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/{id}/stream` | 视频流播放（支持 Range 断点续播） |
| GET | `/api/v1/video/{id}/stream/transcode?quality=1080p` | 实时转码播放 |
| GET | `/api/v1/video/{id}/playlist.m3u` | 生成系列 M3U 播放列表 |
| GET | `/api/v1/video/{id}/subtitles` | 列出外挂字幕 |
| GET | `/api/v1/video/{id}/subtitles/{filename}` | 获取字幕文件 |

#### 图片资源 / Image Resources

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/{id}/cover` | 获取封面图片 |
| GET | `/api/v1/video/{id}/fanart` | 获取横屏背景图 |
| GET | `/api/v1/video/actor/{actorId}/image` | 获取演员头像 |
| GET | `/api/v1/video/tmdb-image-proxy?path=xxx&size=w500` | TMDB 图片本地代理（作品封面等） |
| GET | `/api/v1/video/{id}/logo` | 获取电影 Logo |
| GET | `/api/v1/video/{id}/logo-options` | 查询 Logo 选项 |
| POST | `/api/v1/video/{id}/logo` | 设置电影 Logo |
| POST | `/api/v1/video/{id}/frames` | 生成截帧候选列表 |
| GET | `/api/v1/video/{id}/frames/{index}` | 获取候选帧图片 |
| POST | `/api/v1/video/{id}/frames/select` | 选定截帧作为封面/背景图 |

#### TMDB 刮削与批量任务 / TMDB Scraping & Batch Tasks（需管理员）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/tmdb/search?q=xxx` | 搜索 TMDB |
| POST | `/api/v1/video/{id}/tmdb/bind` | 绑定 TMDB 元数据（异步） |
| POST | `/api/v1/video/{id}/tmdb/unbind` | 解绑 TMDB 元数据 |
| POST | `/api/v1/video/{id}/tmdb/refresh` | 刷新单个视频 TMDB 元数据 |
| POST | `/api/v1/video/tmdb/rescrape-library/{libraryId}` | 按资源库重新刮削（异步） |
| POST | `/api/v1/video/refresh-all-actors` | 批量刷新演员（异步） |
| POST | `/api/v1/video/{id}/refresh-logo` | 补全单个电影 Logo |
| POST | `/api/v1/video/refresh-all-logos` | 批量补全 Logo（异步） |
| POST | `/api/v1/video/refresh-all-resolutions` | 批量补全分辨率（异步） |
| GET | `/api/v1/video/scrape/progress?module=xxx` | 查询刮削/批量任务进度 |
| POST | `/api/v1/video/{id}/nfo` | 生成 NFO 文件（管理员） |
| POST | `/api/v1/video/{id}/covers` | 下载封面图片（管理员） |

### 视频系列接口 / Video Series Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/series` | 获取所有系列（含独立电影） |
| GET | `/api/v1/video/series/grouped-by-library` | 按资源库分组获取系列 |
| GET | `/api/v1/video/series/{id}` | 获取系列详情（含季封面 URL） |
| GET | `/api/v1/video/series/{id}/cover` | 获取系列封面 |
| GET | `/api/v1/video/series/{id}/fanart` | 获取系列背景图 |
| GET | `/api/v1/video/series/{id}/logo` | 获取系列 Logo |
| GET | `/api/v1/video/series/{id}/logo-options` | 查询系列 Logo 选项 |
| POST | `/api/v1/video/series/{id}/refresh-logo` | 补全系列 Logo |
| POST | `/api/v1/video/series/{id}/logo` | 设置系列 Logo |
| GET | `/api/v1/video/series/{id}/season/{seasonNumber}/cover` | 获取季封面 |
| POST | `/api/v1/video/series/{id}/refresh-season-covers` | 刷新单个系列的季资源 |
| POST | `/api/v1/video/series/refresh-all-season-covers` | 批量刷新所有系列的季资源 |
| PUT | `/api/v1/video/series/{id}/favorite` | 设置系列收藏状态 |
| PUT | `/api/v1/video/series/{id}/metadata` | 编辑系列元数据 |
| GET | `/api/v1/video/series/{id}/actors` | 获取系列演员列表 |
| POST | `/api/v1/video/series/{id}/frames/select` | 设置系列背景图 |
| GET | `/api/v1/video/series/calendar` | 追更日历 |
| GET | `/api/v1/video/series/favorites` | 获取收藏系列列表 |

### 音乐接口 / Music Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/music/home` | 首页聚合数据 |
| GET | `/api/v1/music/songs` | 单曲列表 |
| GET | `/api/v1/music/songs/{id}` | 单曲详情 |
| GET | `/api/v1/music/songs/{id}/stream` | 单曲流播放 |
| GET | `/api/v1/music/songs/{id}/cover` | 单曲封面 |
| GET | `/api/v1/music/songs/{id}/lyrics` | 单曲歌词 |
| GET | `/api/v1/music/albums` | 专辑列表 |
| GET | `/api/v1/music/albums/{id}` | 专辑详情 |
| GET | `/api/v1/music/albums/{id}/songs` | 专辑歌曲列表 |
| GET | `/api/v1/music/albums/{id}/cover` | 专辑封面 |
| GET | `/api/v1/music/artists` | 歌手列表 |
| GET | `/api/v1/music/artists/{id}` | 歌手详情 |
| GET | `/api/v1/music/artists/{id}/cover` | 歌手封面 |
| GET | `/api/v1/music/genres` | 流派列表 |
| GET/POST | `/api/v1/music/playlists` | 播放列表列表/创建 |
| GET/PUT/DELETE | `/api/v1/music/playlists/{id}` | 播放列表详情/更新/删除 |
| GET/POST/DELETE | `/api/v1/music/bookmarks[/{songId}]` | 书签管理 |
| GET/PUT | `/api/v1/music/play-queue` | 播放队列获取/更新 |
| PUT | `/api/v1/music/{type}/{id}/star` | 设置星级收藏 |
| PUT | `/api/v1/music/{type}/{id}/rating` | 设置评分 |
| POST | `/api/v1/music/scrobble` | 上报播放记录 |
| POST | `/api/v1/music/scan` | 扫描音乐资源库 |
| POST | `/api/v1/music/organize` | 整理音乐目录 |
| * | `/rest` | Subsonic 兼容 API |

### 系统设置接口 / Settings Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/settings` | 获取所有设置 |
| GET | `/api/v1/settings/{key}` | 获取单个设置 |
| PUT | `/api/v1/settings/{key}` | 更新设置 |

### 日志接口 / Log Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/logs` | 列出可用的日志文件 |
| GET | `/api/v1/logs/{fileName}` | 导出日志文件 |

### 响应格式 / Response Format

```json
{
  "success": true,
  "message": "optional message",
  "data": { ... }
}
```

## 配置说明 / Configuration

### 环境变量 / Environment Variables

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SERVER_PORT` | `20058` | 服务端口 |
| `DB_HOST` | `localhost` | PostgreSQL 主机 |
| `DB_PORT` | `5432` | PostgreSQL 端口 |
| `DB_NAME` | `fryfroghub` | 数据库名称 |
| `DB_USERNAME` | - | 数据库用户名 |
| `DB_PASSWORD` | - | 数据库密码 |
| `DB_POOL_SIZE` | `10` | 数据库连接池大小 |
| `AUTH_ENABLED` | `true` | 启用/禁用认证 |
| `AUTH_PASSWORD` | - | 初始 admin 密码（留空则自动生成随机密码并打印日志） |
| `AUTH_TOKEN_TTL` | `604800` | Token 有效期（秒），默认 7 天 |
| `AUTH_LOGIN_MAX_FAILURES` | `5` | 连续登录失败锁定阈值 |
| `AUTH_LOGIN_LOCK_MINUTES` | `15` | 锁定时长（分钟） |
| `SUBSONIC_ENCRYPT_KEY` | - | Subsonic 密码加密密钥（Base64 编码的 32 字节，留空则明文存储旧数据兼容） |
| `VIDEO_ROOT_PATHS` | - | 视频文件目录 |
| `VIDEO_BASE_URL` | - | M3U 等对外 URL 基地址覆盖（反向代理/NAT 场景） |
| `TMDB_API_KEY` | - | TMDB API Key（视频刮削用） |
| `TMDB_LANGUAGE` | `zh-CN` | TMDB 语言 |
| `TMDB_IMAGE_SIZE` | `original` | TMDB 图片尺寸 |
| `TMDB_INCLUDE_ADULT` | `true` | TMDB 是否包含成人内容 |
| `WATCHER_PERIODIC_SCAN` | `true` | 启用定时扫描 |
| `PERIODIC_SCAN_INTERVAL` | `30` | 定时扫描间隔（分钟） |
| `FFMPEG_PATH` | - | FFmpeg 路径（可选，不配置则使用系统 PATH） |
| `LOG_LEVEL` | `INFO` | 日志级别 |

## 支持的格式 / Supported Formats

| 类型 | 格式 | 说明 |
|------|------|------|
| **视频** | MP4, MKV, AVI, MOV, FLV, WMV, WebM, M4V | 支持 Range 请求，断点续播 |
| **音乐** | MP3, FLAC, WAV, M4A, AAC, OGG, OPUS, WMA, APE, MPC, DSF 等 | 通过 ffprobe 读取标签建库 |

## 开发指南 / Development Guide

### 运行测试 / Running Tests

```bash
# 运行所有测试
mvn test

# 运行 video 模块测试
mvn test -pl video

# 运行单个测试类
mvn test -pl video -Dtest=VideoControllerTest
```

### 代码规范 / Code Conventions

- 包名：`com.fryfrog.hub.{module}.{layer}`
- REST 端点：`/api/v1/{resource}`
- 响应格式：统一使用 `ApiResponse<T>`
- 实体继承 `BaseEntity`（包含 id、createdAt、updatedAt）
- 认证：自定义 Bearer Token 认证（非 Spring Security）
- 异常处理：`@RestControllerAdvice` 全局异常处理器

## License

MIT
