# Fryfrog Hub API — 统一媒体后端服务

## 项目简介

自研的个人媒体库管理后端，提供音乐、视频、漫画、电子书四类媒体的元数据管理、在线流媒体播放、智能刮削和 Docker 一键部署能力。项目采用多模块 Maven 架构，支持 NAS 本地部署和容器化部署。

**技术栈**: Java 21 + Spring Boot 3.2.5 + Spring Data JPA + SQLite + FFmpeg + Docker

---

## 核心技术亮点

### 1. 多模块 Maven 架构设计

采用 Parent POM + 6 个子模块（common / music / video / comic / ebook / app）的分层架构：

- `common` — 共享实体（BaseEntity）、统一响应（ApiResponse\<T\>）、全局异常处理器、媒体库管理、刮削进度追踪
- `music` — 音乐元数据提取（jaudiotagger）+ 流媒体播放
- `video` — 视频 TMDB 刮削 + NFO 生成 + 实时转码
- `comic` — 漫画缩略图生成（Thumbnails4j）+ 在线阅读
- `ebook` — 电子书章节识别（Apache Tika + EPUB 解析）
- `app` — Spring Boot 启动模块 + 认证拦截器 + 全局配置

每个模块独立 pom.xml，依赖通过 Parent POM 统一版本管理，支持按模块独立构建和测试。

### 2. 视频实时转码流播放

基于 FFmpeg + ProcessBuilder 实现的实时视频转码服务：

- 支持 1080p / 720p / 480p / 360p 多档位画质切换
- 使用 `libopenh264` 编码 + 分片 MP4 输出（`frag_keyframe+empty_moov`），支持流式播放
- ffprobe 自动探测视频时长，精确控制输出
- 虚拟线程（Java 21 Virtual Threads）处理 FFmpeg stderr 日志，避免阻塞主线程
- 跨平台适配：自动识别 Windows/macOS/Linux，设置正确的动态库路径

```java
// 虚拟线程处理 FFmpeg 错误流
Thread.startVirtualThread(() -> {
    try (var err = process.getErrorStream();
         var reader = new BufferedReader(new InputStreamReader(err))) {
        String line;
        while ((line = reader.readLine()) != null) {
            log.warn("FFmpeg stderr: {}", line);
        }
    } catch (IOException ignored) {}
});
```

### 3. TMDB 元数据智能刮削系统

视频模块集成了完整的 TMDB 刮削流水线：

- **TMDB 搜索 + 自动匹配**: 根据文件名自动搜索 TMDB，支持电影/电视剧/动漫多类型匹配
- **NFO 文件生成**: 生成 Kodi 兼容的 NFO 元数据文件，包含剧集信息、演员表、评分等
- **封面自动下载**: 竖屏海报 + 横屏背景图自动下载和缓存
- **剧集自动识别**: 根据文件名中的 Season/Episode 信息自动识别季数和集数
- **系列分组管理**: 同一 TMDB ID 的视频自动归为同一系列
- **批量刮削**: 支持按资源库批量重新刮削，带进度追踪
- **异步执行 + 写锁保护**: TMDB API 调用在写锁外执行，DB 写入时短暂持有写锁，避免长时间锁表

### 4. 音乐流媒体服务

- jaudiotagger 提取音频元数据（标题、艺术家、专辑、封面、内嵌歌词）
- HTTP Range 请求支持断点续播，多格式 Content-Type 自动识别（MP3/FLAC/OGG/WAV/AAC/M4A）
- 外部 .lrc 歌词文件自动关联
- 播放列表管理（CRUD + 曲目增删）
- 播放统计（最近播放、最常播放、播放次数追踪）
- 基于听歌习惯的智能推荐歌单

### 5. 漫画 & 电子书在线阅读

- **漫画**: CBZ/CBR/ZIP/RAR 压缩包按页浏览，Thumbnails4j 自动生成缩略图，Tika 提取元数据
- **电子书**: EPUB/PDF/TXT/MOBI/AZW/FB2 多格式支持，中文章节标题智能识别（第X章/节/回），按章节导航
- 两者均支持阅读进度记录和续读

### 6. Java 21 虚拟线程深度应用

项目全面启用虚拟线程（`spring.threads.virtual.enabled: true`），并在以下场景主动使用：

- 视频转码 FFmpeg 进程 stderr 日志处理
- 批量刮削任务的并行执行（`Executors.newVirtualThreadPerTaskExecutor()`）
- 异步文件扫描和元数据提取

### 7. 数据库设计与 SQLite 优化

- SQLite 作为生产数据库（轻量部署，NAS 友好），启用 WAL 模式提升并发读性能
- 自定义 `DatabaseWriteLock` 工具类解决 SQLite 写锁竞争问题
- JPA Auditing 自动维护 `createdAt` / `updatedAt` 时间戳
- 多媒体库（MediaLibrary）实体支持多路径配置和类型迁移

### 8. 认证与安全

- 自定义 Bearer Token 认证（轻量实现，无需 Spring Security）
- `AuthManager` 管理 Token 生命周期
- `AuthInterceptor` 全局拦截器，支持路径排除配置
- 统一异常处理器（`@RestControllerAdvice`）+ `ApiResponse<T>` 标准化响应格式

### 9. Docker 容器化部署

- 多阶段构建 Dockerfile：Maven 构建 → JRE 运行时 + FFmpeg
- docker-compose 一键部署，支持 NAS（飞牛等）环境
- GitHub Actions CI/CD：master 分支自动构建镜像，推送至 GHCR + DockerHub
- 镜像使用 GHA 构建缓存加速

```dockerfile
# 多阶段构建
FROM maven:3.9-eclipse-temurin-21 AS build
# ... 构建阶段
FROM eclipse-temurin:21-jre
RUN apt-get install -y ffmpeg  # 运行时内置 FFmpeg
```

---

## 项目数据

| 指标 | 数据 |
|------|------|
| Java 源文件数 | ~100+ |
| 代码模块 | 6 个 Maven 子模块 |
| REST API 端点 | 50+ |
| 支持媒体格式 | 音频 6 种 / 视频 8 种 / 漫画 4 种 / 电子书 7 种 |
| 外部 API 集成 | TMDB / Bangumi / OpenLibrary |
| 测试框架 | JUnit 5 + Mockito |

---

## 技术关键词

`Java 21` `Spring Boot 3.2` `Spring Data JPA` `SQLite` `FFmpeg` `虚拟线程` `TMDB API` `NFO` `Docker` `GitHub Actions` `RESTful API` `流媒体` `视频转码` `元数据刮削` `Maven 多模块` `ProcessBuilder` `jaudiotagger` `Thumbnails4j` `Apache Tika`
