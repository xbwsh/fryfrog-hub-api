# Fryfrog Hub API

统一媒体后端 API 服务，支持音乐、漫画、电子书、视频的元数据管理和流媒体播放。

## Tech Stack

- Java 21 + Spring Boot 3.x
- Spring Data JPA + H2（开发）/ PostgreSQL（生产）
- Spring Security（可选认证）
- FFmpeg + ProcessBuilder（音频/视频转码）
- jaudiotagger（音乐元数据）
- Thumbnails4j（漫画缩略图）
- Apache Tika（漫画/电子书元数据）
- Netty / Project Loom（高并发）

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
- 缩略图：按需生成，缓存到 `{root-path}/.cache/thumbnails/`
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
