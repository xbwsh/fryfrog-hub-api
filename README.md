# Fryfrog Hub API

统一媒体后端 API 服务，支持音乐、漫画、电子书、视频的元数据管理和流媒体播放。

A unified media backend API service supporting metadata management and streaming for music, comics, ebooks, and video.

## 功能特性 / Features

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

### 通用功能 / Common Features

-   **Swagger 文档** - 自动生成 API 文档，支持在线测试
-   **CORS 支持** - 已配置跨域，可直接对接前端
-   **Docker 部署** - 提供 Dockerfile 和 docker-compose.yml
-   **H2 开发库** - 开发环境使用 H2 内存数据库
-   **MySQL 支持** - 生产环境使用 MySQL 数据库

## 技术栈 / Tech Stack

- Java 21 + Spring Boot 3.2.x
- Spring Data JPA + H2（开发）/ MySQL（生产）
- jaudiotagger（音乐元数据提取）
- Thumbnails4j（漫画缩略图）
- Apache Tika（漫画/电子书元数据提取）
- Springdoc OpenAPI（Swagger 文档）

## 项目结构 / Project Structure

```
fryfrog-hub-api/
├── app/             # Spring Boot 启动模块
├── common/          # 共享实体、DTO、工具类
├── music/           # 音乐模块（jaudiotagger）
├── video/           # 视频模块（TMDB 刮削 + NFO 生成）
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

# 启动应用（开发环境，使用 H2 内存数据库）
mvn spring-boot:run -pl app

# 或者打包后运行
mvn clean package -DskipTests
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar
```

### Docker 部署 / Docker Deployment

```bash
docker-compose up -d
```

### 生产部署 / Production Deployment

```bash
# 1. 初始化数据库
mysql -u root -p < docs/sql/init.sql

# 2. 设置环境变量
export DB_USERNAME=your_username
export DB_PASSWORD=your_password
export MUSIC_ROOT_PATH=/path/to/your/music
export VIDEO_ROOT_PATH=/path/to/your/video
export COMIC_ROOT_PATH=/path/to/your/comic
export EBOOK_ROOT_PATH=/path/to/your/ebook

# 3. 启动应用
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

## API 文档 / API Documentation

启动应用后访问 Swagger UI：

http://localhost:20058/swagger-ui.html

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
  port: 20058

hub:
  music:
    root-path: ${MUSIC_ROOT_PATH:./media-library/music}
    supported-formats: mp3,flac,ogg,wav,aac,m4a
  video:
    root-path: ${VIDEO_ROOT_PATH:./media-library/video}
    supported-formats: mp4,mkv,avi,mov,flv,wmv,webm,m4v
  comic:
    root-path: ${COMIC_ROOT_PATH:./media-library/comic}
    supported-formats: cbz,cbr,zip,rar
  ebook:
    root-path: ${EBOOK_ROOT_PATH:./media-library/ebook}
    supported-formats: epub,pdf,mobi,azw,azw3,fb2,txt
```

### 环境变量 / Environment Variables

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MUSIC_ROOT_PATH` | `./media-library/music` | 音乐文件目录 |
| `VIDEO_ROOT_PATH` | `./media-library/video` | 视频文件目录 |
| `COMIC_ROOT_PATH` | `./media-library/comic` | 漫画文件目录 |
| `EBOOK_ROOT_PATH` | `./media-library/ebook` | 电子书文件目录 |
| `DB_USERNAME` | `root` | 数据库用户名 |
| `DB_PASSWORD` | `root` | 数据库密码 |

## 支持的格式 / Supported Formats

| 类型 | 支持格式 |
|------|----------|
| 音频 | MP3, FLAC, OGG, WAV, AAC, M4A |
| 视频 | MP4, MKV, AVI, MOV, FLV, WMV, WebM, M4V |
| 漫画 | CBZ, CBR, ZIP, RAR |
| 电子书 | EPUB, PDF, MOBI, AZW, AZW3, FB2, TXT |

## 开发指南 / Development Guide

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

## License

MIT
