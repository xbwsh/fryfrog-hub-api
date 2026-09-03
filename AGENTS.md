# Fryfrog Hub API

视频媒体后端 API 服务，支持视频元数据管理和流媒体播放。

## Default Behavior

**Ponytail mode: always active.** For all coding tasks (writing, refactoring, fixing, reviewing), use the simplest solution that works. Reach for stdlib before dependencies, native features before custom code. YAGNI first. Use `/ponytail ultra` only when explicitly asked.

**Language: Chinese responses required.** All final summaries, explanations, and user-facing output must be in Chinese (中文), regardless of which skill is invoked or what language the code/comments use.

## Tech Stack

- Java 21 + Spring Boot 3.2.5
- Spring Data JPA + **PostgreSQL**
- 虚拟线程已启用：`spring.threads.virtual.enabled: true`
- FFmpeg + ProcessBuilder（视频转码）
- TMDB API（视频元数据刮削）
- Springdoc OpenAPI（Swagger 文档）

## Module Structure

```
fryfrog-hub-api/
├── common/          # 共享实体（BaseEntity）、DTO（ApiResponse）、工具类
├── media-core/      # 媒体基础设施（FFmpegRuntime + MediaProbeService + FFmpegBinaryExtractor，bytedeco 二进制在此模块）
├── video/           # 视频 API（TMDB 刮削 + 转码）
├── music/           # 音乐模块（扫描建库 + 播放 + Subsonic 兼容 API）
├── audiobook/       # 有声书模块（目录聚合扫描 + M4B 章节解析 + 播放进度）
├── app/             # Spring Boot 启动模块 + 全局配置/控制器
└── pom.xml          # Parent POM
```

`app/` 模块包含启动类 `FryfrogHubApplication`、全局配置（WebConfig、OpenApiConfig）和通用控制器（AuthController、SettingController、LogController）。`media-core/` 承载 ffmpeg/ffprobe 通用能力，video/music/audiobook 均只依赖 common + media-core，互不依赖。

## Build & Run

```bash
# 完整构建
mvn clean install

# 跳过测试构建
mvn clean install -DskipTests

# 运行应用（端口 20058）
mvn spring-boot:run -pl app

# 运行单个模块测试
mvn test -pl video

# 运行单个测试类
mvn test -pl video -Dtest=VideoControllerTest

# 运行单个测试方法
mvn test -pl video -Dtest=VideoControllerTest#testMethod
```

## Testing

- 单元测试：JUnit 5 + Mockito
- 测试必须标注 `@ActiveProfiles("test")`
- 测试配置：`src/test/resources/application-test.yml`

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
- 认证：自定义 Bearer Token 认证（非 Spring Security），多用户 + BCrypt 密码哈希，通过 `AuthManager`（app/config）与 `UserService`（common/service）管理
- 用户管理：`User` 实体（common/model），角色 `ADMIN`/`USER`，管理端点 `/api/v1/users`；首次启动自动创建初始 `admin`（密码取 `AUTH_PASSWORD`，未配置则生成随机密码并打印日志）
- 媒体库授权：`UserLibrary`（common/model）记录用户可访问的媒体库，`MediaLibraryService.getAllowedLibraryIds(userId)` 计算可见库（ADMIN/匿名/后台=全部启用库，普通用户=分配库∩启用库），内容查询走 `getAllowableLibraryIds()` 按当前请求用户过滤
- 异常处理：`@RestControllerAdvice` 全局异常处理器
- 配置：`application.yml`，多环境用 `application-{profile}.yml`
- Lombok：`lombok.config` 启用 `@Qualifier` 注解拷贝

## Key Configuration

- 端口：`20058`（`SERVER_PORT` 环境变量可覆盖）
- 数据库：PostgreSQL，通过环境变量配置（开发用 `.env`，Docker 用环境变量）
- 认证：`AUTH_ENABLED` 默认开启，`AUTH_PASSWORD` 默认留空（启动时若未配置则生成随机 admin 密码），登录失败默认 5 次后锁定 15 分钟；登录令牌存 `auth_tokens` 表（`AuthToken`/`AuthTokenRepository`）DB 持久化，重启不失效，`TokenCleanupScheduler` 每日 4:20 清理过期令牌
- 媒体路径：`VIDEO_ROOT_PATHS`
- 数据隔离：观看进度（`(user, video)` 唯一）与收藏（`favorites` 表）按用户隔离，认证关闭时用匿名 ID（`UserContext.ANONYMOUS_ID`）全局共享
- 媒体签名：封面/流/字幕等 URL 由 `MediaUrlSigner`（common/util）签名（exp+sig，HMAC-SHA256），`AuthInterceptor` 校验；`LegacyDataMigrator` 启动时把旧全局数据迁移给 admin
- HTTP 客户端：默认 `restTemplate` 使用标准证书校验；`scraperRestTemplate`（TMDB 刮削）默认同样严格，如需信任自签名/内网证书，设 `SCRAPER_BYPASS_SSL=true`（通过 Apache HttpClient 局部配置，不改 JVM 全局 SSL）
- 用户偏好：主题/隐私/播放器等「我的」页设置存 `user_preferences` 表（`UserPreference`/`UserPreferenceService`），按账号隔离，接口 `GET|PUT /api/v1/users/me/preferences`（普通用户可写，已加入 `USER_OWNED_MUTATIONS` 白名单）；PUT 为全量替换，value 空串表示删除该键。认证关闭时不提供（返回 401）
- `.env` 为开发环境配置，已加入 `.gitignore`
- `.env.example` 为配置模板，已提交到仓库

## Common Pitfalls

- PostgreSQL 必填环境变量：`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`
- `.env` 已加入 `.gitignore`，新建开发环境需从 `.env.example` 复制并填写
- 虚拟线程已启用，不要在代码中创建平台线程池
- FFmpeg 路径需在配置中指定，不要硬编码
- Docker 部署使用 `docker compose up -d`，内置 PostgreSQL
- Docker 镜像已内置 FFmpeg
- 测试用 H2 内存库，无需 PostgreSQL

## Environment

- 必须：JDK 21、Maven 3.9+
- 可选：FFmpeg（视频功能需要，Docker 镜像已内置）
- IDE：推荐 IntelliJ IDEA，导入为 Maven 项目

## CI/CD

- GitHub Actions：`docker.yml` 在 master 分支推送时构建 Docker 镜像
- 镜像推送到 GHCR（`ghcr.io`）和 DockerHub
- 构建命令：`mvn clean package -DskipTests`

## Docker 部署

```bash
# 复制环境变量模板并配置
cp .env.example .env
# 编辑 .env 填写数据库密码等配置

# 启动服务
docker compose up -d
```

Docker Compose 会同时启动 PostgreSQL 和 API 服务，数据持久化到 Docker volume。
