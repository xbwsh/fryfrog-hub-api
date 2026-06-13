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

### 通用功能 / Common Features

-   **Swagger 文档** - 自动生成 API 文档，支持在线测试
-   **CORS 支持** - 已配置跨域，可直接对接前端
-   **MySQL 支持** - 生产环境使用 MySQL 数据库
-   **H2 开发库** - 开发环境使用 H2 内存数据库

## 技术栈 / Tech Stack

- Java 21 + Spring Boot 3.2.x
- Spring Data JPA + H2（开发）/ MySQL（生产）
- jaudiotagger（音乐元数据提取）
- Springdoc OpenAPI（Swagger 文档）

## 项目结构 / Project Structure

```
fryfrog-hub-api/
├── app/             # Spring Boot 启动模块
├── common/          # 共享实体、DTO、工具类
├── music/           # 音乐模块
├── docs/sql/        # 数据库初始化脚本
└── pom.xml          # Parent POM
```

## 快速开始 / Quick Start

### 环境要求 / Prerequisites

- JDK 21+
- Maven 3.9+
- MySQL 8.0+（生产环境）

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

### 生产部署 / Production Deployment

```bash
# 1. 初始化数据库
mysql -u root -p < docs/sql/init.sql

# 2. 设置环境变量
export DB_USERNAME=your_username
export DB_PASSWORD=your_password
export MUSIC_ROOT_PATH=/path/to/your/music

# 3. 启动应用
java -jar app/target/fryfrog-hub-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

## API 文档 / API Documentation

启动应用后访问 Swagger UI：

http://localhost:20058/swagger-ui.html

### 核心接口 / Core Endpoints

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
```

### 环境变量 / Environment Variables

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MUSIC_ROOT_PATH` | `./media-library/music` | 音乐文件目录 |
| `DB_USERNAME` | `root` | 数据库用户名 |
| `DB_PASSWORD` | `root` | 数据库密码 |

## 支持的音频格式 / Supported Audio Formats

- MP3
- FLAC
- OGG
- WAV
- AAC
- M4A

## 开发指南 / Development Guide

### 运行测试 / Running Tests

```bash
# 运行所有测试
mvn test

# 运行单个模块测试
mvn test -pl music

# 运行单个测试类
mvn test -pl music -Dtest=MusicControllerStreamingTest
```

### 代码规范 / Code Conventions

- 包名：`com.fryfrog.hub.{module}.{layer}`
- REST 端点：`/api/v1/{resource}`
- 响应格式：统一使用 `ApiResponse<T>`

## License

MIT
