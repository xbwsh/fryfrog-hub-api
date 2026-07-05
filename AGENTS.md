# Fryfrog Hub API

统一媒体后端 API 服务，支持音乐、漫画、电子书、视频的元数据管理和流媒体播放。

## Default Behavior

**Ponytail mode: always active.** For all coding tasks (writing, refactoring, fixing, reviewing), use the simplest solution that works. Reach for stdlib before dependencies, native features before custom code. YAGNI first. Use `/ponytail ultra` only when explicitly asked.

**Language: Chinese responses required.** All final summaries, explanations, and user-facing output must be in Chinese (中文), regardless of which skill is invoked or what language the code/comments use.

## Tech Stack

- Java 21 + Spring Boot 3.2.5
- Spring Data JPA + **SQLite**（非 H2/PostgreSQL，生产也用 SQLite）
- 虚拟线程已启用：`spring.threads.virtual.enabled: true`
- FFmpeg + ProcessBuilder（音频/视频转码）
- jaudiotagger（音乐元数据）
- Thumbnails4j（漫画缩略图）
- Apache Tika（漫画/电子书元数据）
- TMDB API（视频元数据刮削）
- Springdoc OpenAPI（Swagger 文档）

## Module Structure

```
fryfrog-hub-api/
├── common/          # 共享实体（BaseEntity）、DTO（ApiResponse）、工具类
├── music/           # 音乐 API（jaudiotagger + FFmpeg）
├── comic/           # 漫画 API（Thumbnails4j + Tika）
├── ebook/           # 电子书 API（Tika）
├── video/           # 视频 API（FFmpeg + TMDB 刮削）
├── app/             # Spring Boot 启动模块 + 全局配置/控制器
└── pom.xml          # Parent POM
```

`app/` 模块包含启动类 `FryfrogHubApplication`、全局配置（WebConfig、OpenApiConfig）和通用控制器（AuthController、SettingController、LogController）。

## Build & Run

```bash
# 完整构建
mvn clean install

# 跳过测试构建
mvn clean install -DskipTests

# 运行应用（端口 20058）
mvn spring-boot:run -pl app

# 运行单个模块测试
mvn test -pl music

# 运行单个测试类
mvn test -pl music -Dtest=MusicControllerStreamingTest

# 运行单个测试方法
mvn test -pl music -Dtest=MusicControllerStreamingTest#streamTrack_returnsAudioContent
```

## Testing

- 单元测试：JUnit 5 + Mockito
- 测试必须标注 `@ActiveProfiles("test")`
- 测试配置：`src/test/resources/application-test.yml`
- 测试很少：目前只有 music 模块有 1 个测试类

```bash
# 运行所有测试
mvn verify
```

## Code Conventions

- 包名：`com.fryfrog.hub.{module}.{layer}`
  - 层级：`controller` / `service` / `repository` / `model` / `dto`
- REST 端点：`/api/v1/{resource}`
- 响应格式：统一使用 `ApiResponse<T>`（`com.fryfrog.hub.common.dto.ApiResponse`）
- 实体继承 `BaseEntity`（包含 id、createdAt、updatedAt）
- 认证：自定义 Bearer Token 认证（非 Spring Security），通过 `AuthManager` 管理
- 异常处理：`@RestControllerAdvice` 全局异常处理器
- 配置：`application.yml`，多环境用 `application-{profile}.yml`
- Lombok：`lombok.config` 启用 `@Qualifier` 注解拷贝

## Key Configuration

- 端口：`20058`（`SERVER_PORT` 环境变量可覆盖）
- 数据库：SQLite，路径 `./data/fryfrog.db`，启用 WAL 模式
- 认证：`hub.auth.enabled` 默认开启，密码 `hub.auth.password` 默认 `1234`
- 媒体路径：`hub.{music|video|comic|ebook}.root-paths`，支持逗号分隔多路径
- `application-dev.yml` 已加入 `.gitignore`，开发时从模板复制
- 开发配置模板：`app/src/main/resources/application-dev.yml.example`

## Common Pitfalls

- SQLite 不支持并发写入，高并发场景需注意 `busy_timeout=5000` 配置
- `application-dev.yml` 不在仓库中，新建开发环境需从 `.example` 复制
- 虚拟线程已启用，不要在代码中创建平台线程池
- FFmpeg 路径需在配置中指定，不要硬编码
- Docker 部署必须使用 `network_mode: host`，否则无法访问服务
- Docker 镜像已内置 FFmpeg
- Testcontainers 不在项目中使用，测试用 H2 内存库

## Environment

- 必须：JDK 21、Maven 3.9+
- 可选：FFmpeg（音频/视频功能需要，Docker 镜像已内置）
- IDE：推荐 IntelliJ IDEA，导入为 Maven 项目

## CI/CD

- GitHub Actions：`docker.yml` 在 master 分支推送时构建 Docker 镜像
- 镜像推送到 GHCR（`ghcr.io`）和 DockerHub
- 构建命令：`mvn clean package -DskipTests`

## Docker 部署

```bash
docker compose up -d
# 或
docker run -d --network host -e DB_PATH=/data/fryfrog.db \
  -v ./db:/data -v /your/media:/data/media \
  ghcr.io/xbwsh/fryfrog-hub-api:latest
```
