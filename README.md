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
├── video/           # 视频模块（TMDB 刮削 + NFO 生成 + 系列管理）
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

### 视频接口 / Video Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video` | 获取所有视频 |
| GET | `/api/v1/video/{id}` | 获取视频详情 |
| GET | `/api/v1/video/{id}/stream` | 播放视频 |
| GET | `/api/v1/video/{id}/cover` | 获取封面图片 |
| GET | `/api/v1/video/{id}/fanart` | 获取背景图/集封面 |
| PUT | `/api/v1/video/{id}/favorite` | 切换收藏状态 |
| GET | `/api/v1/video/{id}/progress` | 获取观看进度 |
| PUT | `/api/v1/video/{id}/progress` | 保存观看进度 |
| GET | `/api/v1/video/tmdb/search?q=xxx` | 搜索 TMDB |
| POST | `/api/v1/video/{id}/tmdb/bind` | 绑定 TMDB 元数据 |
| POST | `/api/v1/video/{id}/tmdb/refresh` | 刷新单个视频 TMDB 元数据 |
| POST | `/api/v1/video/tmdb/auto-scrape` | 自动刮削所有视频 |
| POST | `/api/v1/video/scan?path=xxx` | 扫描视频目录 |
| POST | `/api/v1/video/refresh-all-movie-actors` | 批量刷新所有电影演员 |

### 视频系列接口 / Video Series Endpoints

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/series` | 获取所有系列（含独立电影） |
| GET | `/api/v1/video/series/grouped-by-library` | 按资源库分组获取系列 |
| GET | `/api/v1/video/series/{id}` | 获取系列详情（含季封面 URL） |
| GET | `/api/v1/video/series/{id}/cover` | 获取系列封面 |
| GET | `/api/v1/video/series/{id}/fanart` | 获取系列背景图 |
| GET | `/api/v1/video/series/{id}/season/{seasonNumber}/cover` | 获取季封面 |
| POST | `/api/v1/video/series/{id}/refresh-season-covers` | 刷新单个系列的季资源 |
| POST | `/api/v1/video/series/refresh-all-season-covers` | 批量刷新所有系列的季资源 |
| PUT | `/api/v1/video/series/{id}/favorite` | 设置系列收藏状态 |
| PUT | `/api/v1/video/series/{id}/metadata` | 编辑系列元数据 |
| POST | `/api/v1/video/series/{id}/frames/select` | 设置系列背景图 |
| GET | `/api/v1/video/series/calendar` | 追更日历 |
| GET | `/api/v1/video/series/favorites` | 获取收藏系列列表 |

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
| `AUTH_ENABLED` | `true` | 启用/禁用认证 |
| `AUTH_PASSWORD` | `1234` | 登录密码 |
| `VIDEO_ROOT_PATHS` | - | 视频文件目录 |
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
