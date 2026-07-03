# Fryfrog Hub API

统一媒体后端 API 服务，支持音乐、漫画、电子书、视频的元数据管理和流媒体播放。

## Default Behavior

**Ponytail mode: always active.** For all coding tasks (writing, refactoring, fixing, reviewing), use the simplest solution that works. Reach for stdlib before dependencies, native features before custom code. YAGNI first. Use `/ponytail ultra` only when explicitly asked.

**Language: Chinese responses required.** All final summaries, explanations, and user-facing output must be in Chinese (中文), regardless of which skill is invoked or what language the code/comments use.

## Tech Stack

- Java 21 + Spring Boot 3.x
- Spring Data JPA + H2（开发）/ PostgreSQL（生产）
- Spring Security（可选认证）
- FFmpeg + ProcessBuilder（音频/视频转码）
- jaudiotagger（音乐元数据）
- Thumbnails4j（漫画缩略图）
- Apache Tika（漫画/电子书元数据）
- Netty / Project Loom（高并发）
- TMDB API（视频元数据刮削）

## Module Structure

```
fryfrog-hub-api/
├── common/          # 共享实体、DTO、工具类
├── music/           # 音乐 API（jaudiotagger + FFmpeg）
├── comic/           # 漫画 API（Thumbnails4j + Tika）
├── ebook/           # 电子书 API（Tika）
├── video/           # 视频 API（FFmpeg）
├── app/             # Spring Boot 启动模块
└── pom.xml          # Parent POM
```

## Build & Run

```bash
# 完整构建
mvn clean install

# 跳过测试构建
mvn clean install -DskipTests

# 运行应用
mvn spring-boot:run -pl app

# 运行单个模块测试
mvn test -pl music

# 运行单个测试类
mvn test -pl music -Dtest=MusicMetadataServiceTest

# 运行单个测试方法
mvn test -pl music -Dtest=MusicMetadataServiceTest#testExtractMetadata
```

## Testing

- 单元测试：JUnit 5 + Mockito
- 集成测试：Testcontainers（PostgreSQL）
- 测试配置：`src/test/resources/application-test.yml`

```bash
# 运行所有测试
mvn verify

# 仅运行集成测试（需要 Docker）
mvn verify -Pintegration
```

## Code Conventions

- 包名：`com.fryfrog.hub.{module}.{layer}`
  - 层级：`controller` / `service` / `repository` / `model` / `dto`
- REST 端点：`/api/v1/{resource}`
- 响应格式：统一使用 `ResponseEntity<T>` 或自定义 `ApiResponse<T>`
- 异常处理：`@RestControllerAdvice` 全局异常处理器
- 配置：`application.yml`，多环境用 `application-{profile}.yml`

## Media File Handling

- 媒体文件路径通过 `application.yml` 配置：`hub.media.root-path`
- 文件扫描：后台定时任务或手动触发
- 元数据：读取后存入数据库，文件不移动
- 转码：FFmpeg 进程通过 `ProcessBuilder` 调用，输出到临时目录

## Environment

- 必须：JDK 21、Maven 3.9+、Docker（Testcontainers 需要）
- 可选：FFmpeg（音频/视频功能需要）
- IDE：推荐 IntelliJ IDEA，导入为 Maven 项目

## Common Pitfalls

- H2 内存库不支持 `JSON` 类型字段，生产用 PostgreSQL
- FFmpeg 路径需在配置中指定，不要硬编码
- 大文件处理注意内存，使用流式读取
- Testcontainers 需要 Docker daemon 运行

## TMDB Integration

视频模块支持从 TMDB（The Movie Database）刮削元数据，包括电影和电视剧。

### 配置

在 `application.yml` 或环境变量中配置 TMDB API Key：

```yaml
hub:
  tmdb:
    api-key: ${TMDB_API_KEY:}  # 必填，从 https://www.themoviedb.org/settings/api 获取
    language: ${TMDB_LANGUAGE:zh-CN}  # 可选，默认中文
    image-size: ${TMDB_IMAGE_SIZE:original}  # 可选，海报尺寸
    auto-scrape: ${TMDB_AUTO_SCRAPE:false}  # 可选，扫描时自动刮削
  proxy:
    host: ${PROXY_HOST:127.0.0.1}  # 代理地址（国内需要）
    port: ${PROXY_PORT:7890}  # 代理端口
```

### API 端点

- `GET /api/v1/video/tmdb/search?q={query}` - 搜索 TMDB 电影和电视剧
- `POST /api/v1/video/{id}/tmdb/bind?tmdbId={tmdbId}&mediaType={movie|tv}` - 绑定 TMDB 元数据（自动生成 NFO 和下载封面）
- `POST /api/v1/video/tmdb/auto-scrape` - 自动刮削所有未绑定的视频
- `GET /api/v1/video/tmdb/status` - 检查 TMDB 配置状态
- `POST /api/v1/video/{id}/nfo` - 手动生成 NFO 文件
- `POST /api/v1/video/{id}/covers` - 手动下载封面图片
- `GET /api/v1/video/{id}/poster` - 获取竖屏海报
- `GET /api/v1/video/{id}/fanart` - 获取横屏背景图
- `GET /api/v1/video/{id}/nfo` - 获取 NFO 文件内容

### 使用流程

1. 扫描视频目录：`POST /api/v1/video/scan?path={目录路径}`
2. 搜索 TMDB：`GET /api/v1/video/tmdb/search?q={视频标题}`
3. 绑定元数据：`POST /api/v1/video/{id}/tmdb/bind?tmdbId={搜索结果ID}&mediaType={movie|tv}`
4. 或自动刮削：`POST /api/v1/video/tmdb/auto-scrape`

### 文件命名规范

刮削后会将视频和元数据整理到以清洗后标题命名的子文件夹：
```
media-library/video/
└── 刀剑神域/
    ├── 刀剑神域1.mp4      # 视频文件
    ├── 刀剑神域1.nfo      # NFO 元数据文件
    ├── 刀剑神域1-poster.jpg  # 竖屏海报
    ├── 刀剑神域1-fanart.jpg  # 横屏背景图
    ├── 刀剑神域2.mp4
    ├── 刀剑神域2.nfo
    ├── 刀剑神域2-poster.jpg
    └── 刀剑神域2-fanart.jpg
```

### NFO 文件格式

支持两种格式：
- 电影：`<movie>` 标签
- 电视剧：`<episodedetails>` 标签

包含字段：title、originaltitle、year、plot、director、actor、genre、rating、votes、imdbid、tmdbid
