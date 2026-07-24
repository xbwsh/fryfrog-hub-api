# Fryfrog Hub API

统一媒体后端 API 服务，支持音乐、漫画、电子书、视频的元数据管理和流媒体播放。

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

### 音乐模块 / Music Module

-   **流媒体播放** - 支持 Range 请求，实现断点续播
-   **歌词提取** - 自动提取音频内嵌歌词
-   **封面提取** - 自动提取并缓存专辑封面
-   **自动扫描** - 启动时自动扫描媒体库，监听文件变化
-   **收藏功能** - 支持收藏/取消收藏音乐
-   **元数据管理** - 完整的音乐元数据 CRUD 操作

### 视频模块 / Video Module

-   **视频流播放** - 支持 HTTP Range 请求断点续播
-   **TMDB 刮削** - 自动从 TMDB 获取电影/电视剧元数据
-   **NFO 生成** - 生成 Kodi 兼容的 NFO 元数据文件
-   **封面下载** - 自动下载竖屏海报和横屏背景图
-   **剧集管理** - 自动识别季数/集数，按系列分组
-   **系列管理** - 独立的视频系列 API，支持系列封面和背景图
-   **观看进度** - 记录播放位置，支持续播
-   **文件监控** - 自动检测新视频文件并索引

### 漫画模块 / Comic Module

-   **在线阅读** - 支持 CBZ/CBR/ZIP/RAR 格式，按页浏览
-   **缩略图** - 自动生成漫画缩略图缓存
-   **封面提取** - 自动从压缩包提取封面图片
-   **自动扫描** - 启动时自动扫描漫画目录
-   **收藏功能** - 支持收藏/取消收藏漫画
-   **阅读进度** - 记录当前阅读页码，支持续读

### 电子书模块 / Ebook Module

-   **在线阅读** - 支持 EPUB/PDF/TXT/MOBI/AZW/FB2 格式
-   **章节识别** - 自动识别中文章节标题（第X章/节/回）
-   **章节导航** - 按章节浏览，返回章节列表和内容
-   **文件下载** - 支持直接下载电子书文件
-   **封面显示** - 无封面时自动生成标题占位图
-   **阅读进度** - 记录当前阅读位置，支持续读

### 媒体系列管理 / Media Series Management

-   **系列分组** - 漫画/电子书按系列自动分组
-   **角色管理** - 系列角色信息和图片管理
-   **收藏功能** - 支持收藏系列
-   **重新刮削** - 重新获取系列简介、封面等元数据

### 通用功能 / Common Features

-   **Swagger 文档** - 自动生成 API 文档，支持在线测试
-   **CORS 支持** - 已配置跨域，可直接对接前端
-   **Docker 部署** - 提供 Dockerfile 和 docker-compose.yml，支持飞牛 NAS 一键部署
-   **SQLite 存储** - 轻量级数据库，无需额外安装
-   **虚拟线程** - 启用 Java 21 虚拟线程，提升并发性能
-   **定时扫描** - 支持配置定时扫描间隔，自动更新媒体库
-   **系统设置** - 运行时动态配置管理
-   **日志导出** - 导出日志文件，方便反馈开发者排查问题

## 技术栈 / Tech Stack

- Java 21 + Spring Boot 3.2.x
- Spring Data JPA + SQLite
- Java 21 虚拟线程（Virtual Threads）
- jaudiotagger（音乐元数据提取）
- Thumbnails4j（漫画缩略图）
- Apache Tika（漫画/电子书元数据提取）
- FFmpeg + ProcessBuilder（音频/视频转码）
- TMDB API（视频元数据刮削）
- Springdoc OpenAPI（Swagger 文档）
- GitHub Actions（自动构建 Docker 镜像）

## 项目结构 / Project Structure

```
fryfrog-hub-api/
├── app/             # Spring Boot 启动模块 + 全局配置/控制器
├── common/          # 共享实体、DTO、工具类
├── music/           # 音乐模块（jaudiotagger）
├── video/           # 视频模块（TMDB 刮削 + NFO 生成 + 系列管理）
├── comic/           # 漫画模块（CBZ/CBR + 缩略图）
├── ebook/           # 电子书模块（EPUB/PDF + 章节识别）
└── pom.xml          # Parent POM
```

## 快速开始 / Quick Start

### 环境要求 / Prerequisites

- JDK 21+
- Maven 3.9+
- Docker（可选，用于 docker-compose 部署）

### 本地开发 / Local Development

```bash
# 克隆项目
git clone https://github.com/xbwsh/fryfrog-hub-api.git
cd fryfrog-hub-api

# 创建开发环境配置（可选，不创建则使用 application.yml 默认配置）
# 复制模板并根据需要修改
cp app/src/main/resources/application-dev.yml.example app/src/main/resources/application-dev.yml
```

**开发配置说明**：`application-dev.yml` 已加入 `.gitignore`，不会提交到仓库。你可以根据本地环境自定义：
- 数据库路径
- 媒体目录
- 代理设置
- 自动刮削开关

```bash
# 启动应用（开发环境）
mvn spring-boot:run -pl app

# 或者打包后运行
mvn clean package -DskipTests
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar
```

### Docker 部署 / Docker Deployment

**飞牛 NAS / Docker UI 部署（推荐）**

1. 拉取镜像：`ghcr.io/xbwsh/fryfrog-hub-api:latest`
2. 创建容器，**网络模式选择 host** 或 **映射端口 20058**
3. 添加挂载路径（在 UI 中配置你的实际路径）：

| 容器路径 | 用途 | 示例宿主机路径 |
|---|---|---|
| `/data` | 数据库 | `/vol1/docker/fryfrog-hub/db` |
| `/data/media/music` | 音乐 | `/vol1/1000/music` |
| `/data/media/video` | 视频 | `/vol2/1000/Media` |
| `/data/media/comic` | 漫画 | `/vol1/1000/comic` |
| `/data/media/ebook` | 电子书 | `/vol1/1000/ebook` |

4. 设置环境变量：
   - `DB_PATH=/data/fryfrog.db` — 数据库路径（必须）
   - `TMDB_API_KEY` — TMDB API Key（可选，用于视频刮削）
   - `AUTH_PASSWORD` — 登录密码（可选，默认 `1234`）
   - `AUTH_ENABLED` — 是否启用认证（可选，默认 `true`）
5. 启动后访问 `http://NAS_IP:20058/swagger-ui.html` 验证

**docker-compose 部署**

```yaml
services:
  fryfrog-hub-api:
    image: ghcr.io/xbwsh/fryfrog-hub-api:latest
    container_name: fryfrog-hub-api
    restart: unless-stopped
    ports:
      - "20058:20058"
    environment:
      - DB_PATH=/data/fryfrog.db
      - TMDB_API_KEY=your_tmdb_api_key
      - PROXY_HOST=127.0.0.1
      - PROXY_PORT=7890
    volumes:
      - ./db:/data
      # - /your/music/path:/data/media/music
      # - /your/video/path:/data/media/video
      # - /your/comic/path:/data/media/comic
      # - /your/ebook/path:/data/media/ebook
```

```bash
docker compose up -d
```

默认拉取 GHCR 镜像。如需本地构建，编辑 `docker-compose.yml` 注释掉 `image` 行，取消 `build` 行注释。

### 生产部署 / Production Deployment

```bash
# 设置环境变量
export MUSIC_ROOT_PATHS=/path/to/your/music
export VIDEO_ROOT_PATHS=/path/to/your/video
export COMIC_ROOT_PATHS=/path/to/your/comic
export EBOOK_ROOT_PATHS=/path/to/your/ebook
export TMDB_API_KEY=your_tmdb_api_key  # 可选，用于视频刮削
export AUTH_PASSWORD=your_password      # 可选，登录密码

# 启动应用
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar
```

## API 文档 / API Documentation

启动应用后访问 Swagger UI：

http://localhost:20058/swagger-ui.html

### 认证接口 / Authentication Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/login` | 登录（输入密码，返回 Token） |
| POST | `/api/v1/auth/logout` | 登出（注销当前 Token） |
| GET | `/api/v1/auth/status` | 认证状态（前端判断是否需要登录） |

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

### 音乐接口 / Music Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/music` | 获取所有曲目 |
| GET | `/api/v1/music/{id}` | 获取曲目详情 |
| GET | `/api/v1/music/{id}/stream` | 播放音乐（支持 Range） |
| GET | `/api/v1/music/{id}/cover` | 获取封面图片 |
| PUT | `/api/v1/music/{id}/favorite` | 切换收藏状态 |
| GET | `/api/v1/music/favorites` | 获取收藏列表 |
| GET | `/api/v1/music/search/title?q=xxx` | 按标题搜索 |
| GET | `/api/v1/music/search/artist?q=xxx` | 按艺术家搜索 |
| POST | `/api/v1/music/scan?path=xxx` | 扫描媒体目录 |

### 视频接口 / Video Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video` | 获取所有视频 |
| GET | `/api/v1/video/{id}` | 获取视频详情 |
| GET | `/api/v1/video/{id}/stream` | 播放视频 |
| GET | `/api/v1/video/{id}/cover` | 获取封面图片 |
| PUT | `/api/v1/video/{id}/favorite` | 切换收藏状态 |
| GET | `/api/v1/video/{id}/progress` | 获取观看进度 |
| PUT | `/api/v1/video/{id}/progress` | 保存观看进度 |
| GET | `/api/v1/video/tmdb/search?q=xxx` | 搜索 TMDB |
| POST | `/api/v1/video/{id}/tmdb/bind` | 绑定 TMDB 元数据 |
| POST | `/api/v1/video/tmdb/auto-scrape` | 自动刮削所有视频 |
| POST | `/api/v1/video/scan?path=xxx` | 扫描视频目录 |

### 视频系列接口 / Video Series Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/series` | 获取所有系列（含独立电影） |
| GET | `/api/v1/video/series/{id}` | 获取系列详情 |
| GET | `/api/v1/video/series/{id}/cover` | 获取系列封面 |
| GET | `/api/v1/video/series/{id}/fanart` | 获取系列背景图 |

### 漫画接口 / Comic Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/comic` | 获取所有漫画 |
| GET | `/api/v1/comic/{id}` | 获取漫画详情 |
| GET | `/api/v1/comic/{id}/cover` | 获取封面图片 |
| GET | `/api/v1/comic/{id}/pages` | 获取页面列表 |
| GET | `/api/v1/comic/{id}/pages/{pageNum}` | 获取页面图片 |
| PUT | `/api/v1/comic/{id}/favorite` | 切换收藏状态 |
| GET | `/api/v1/comic/{id}/progress` | 获取阅读进度 |
| PUT | `/api/v1/comic/{id}/progress` | 保存阅读进度 |
| POST | `/api/v1/comic/scan?path=xxx` | 扫描漫画目录 |

### 电子书接口 / Ebook Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/ebook` | 获取所有电子书 |
| GET | `/api/v1/ebook/{id}` | 获取电子书详情 |
| GET | `/api/v1/ebook/{id}/cover` | 获取封面图片 |
| GET | `/api/v1/ebook/{id}/read` | 在线阅读 |
| GET | `/api/v1/ebook/{id}/chapters` | 获取章节列表 |
| GET | `/api/v1/ebook/{id}/chapters/{num}` | 获取章节内容 |
| GET | `/api/v1/ebook/{id}/download` | 下载电子书 |
| PUT | `/api/v1/ebook/{id}/favorite` | 切换收藏状态 |
| GET | `/api/v1/ebook/{id}/progress` | 获取阅读进度 |
| PUT | `/api/v1/ebook/{id}/progress` | 保存阅读进度 |
| POST | `/api/v1/ebook/scan?path=xxx` | 扫描电子书目录 |

### 媒体系列接口 / Media Series Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/media/series` | 获取系列列表（可按类型筛选） |
| GET | `/api/v1/media/series/{id}` | 获取系列详情 |
| GET | `/api/v1/media/series/{id}/cover` | 获取系列封面 |
| GET | `/api/v1/media/series/{id}/characters` | 获取系列角色 |
| GET | `/api/v1/media/series/character/{id}/image` | 获取角色图片 |
| PUT | `/api/v1/media/series/{id}/favorite` | 切换系列收藏 |
| POST | `/api/v1/media/series/{id}/rescrape` | 重新刮削系列 |

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

### application.yml

```yaml
server:
  port: ${SERVER_PORT:20058}

spring:
  threads:
    virtual:
      enabled: true  # 启用虚拟线程

auth:
  enabled: ${AUTH_ENABLED:true}
  password: ${AUTH_PASSWORD:1234}

watcher:
  periodic-scan: ${WATCHER_PERIODIC_SCAN:true}
  periodic-scan-interval: ${PERIODIC_SCAN_INTERVAL:30}

hub:
  music:
    root-paths: ${MUSIC_ROOT_PATHS:}
  video:
    root-paths: ${VIDEO_ROOT_PATHS:}
  comic:
    root-paths: ${COMIC_ROOT_PATHS:}
  ebook:
    root-paths: ${EBOOK_ROOT_PATHS:}

tmdb:
  api-key: ${TMDB_API_KEY:}
  language: ${TMDB_LANGUAGE:zh-CN}
  image-size: ${TMDB_IMAGE_SIZE:original}
```

### 环境变量 / Environment Variables

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SERVER_PORT` | `20058` | 服务端口 |
| `DB_PATH` | `./fryfrog.db` | SQLite 数据库路径 |
| `AUTH_ENABLED` | `true` | 启用/禁用认证 |
| `AUTH_PASSWORD` | `1234` | 登录密码 |
| `MUSIC_ROOT_PATHS` | - | 音乐文件目录 |
| `VIDEO_ROOT_PATHS` | - | 视频文件目录 |
| `COMIC_ROOT_PATHS` | - | 漫画文件目录 |
| `EBOOK_ROOT_PATHS` | - | 电子书文件目录 |
| `TMDB_API_KEY` | - | TMDB API Key（视频刮削用） |
| `TMDB_LANGUAGE` | `zh-CN` | TMDB 语言 |
| `TMDB_IMAGE_SIZE` | `original` | TMDB 图片尺寸 |
| `TMDB_INCLUDE_ADULT` | `true` | TMDB 是否包含成人内容 |
| `WATCHER_PERIODIC_SCAN` | `true` | 启用定时扫描 |
| `PERIODIC_SCAN_INTERVAL` | `30` | 定时扫描间隔（分钟） |
| `FFMPEG_PATH` | - | FFmpeg 路径（可选，不配置则使用系统 PATH） |
| `LOG_LEVEL` | `INFO` | 日志级别 |

## 支持的格式 / Supported Formats

| 类型 | 支持格式 |
|------|----------|
| 音频 | MP3, FLAC, OGG, WAV, AAC, M4A |
| 视频 | MP4, MKV, AVI, MOV, FLV, WMV, WebM, M4V |
| 漫画 | CBZ, CBR, ZIP, RAR |
| 电子书 | EPUB, PDF, MOBI, AZW, AZW3, FB2, TXT |

## 开发指南 / Development Guide

### 环境要求 / Prerequisites

- JDK 21+
- Maven 3.9+
- Docker（可选，用于 docker-compose 部署）

### 本地开发 / Local Development

```bash
# 克隆项目
git clone https://github.com/xbwsh/fryfrog-hub-api.git
cd fryfrog-hub-api

# 启动应用（开发环境）
mvn spring-boot:run -pl app

# 或者打包后运行
mvn clean package -DskipTests
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar
```

### 运行测试 / Running Tests

```bash
# 运行所有测试
mvn test

# 运行单个模块测试
mvn test -pl music
mvn test -pl video
mvn test -pl comic
mvn test -pl ebook

# 运行单个测试类
mvn test -pl music -Dtest=MusicControllerStreamingTest
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
