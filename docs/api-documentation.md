# Fryfrog Hub API 接口文档

**基础地址**: `http://192.168.31.127:20058`
**API 版本**: v1
**描述**: 统一媒体后端 API — 支持音乐、漫画、电子书、视频的元数据管理和流媒体播放

---

## 认证 (Auth)

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/login` | 密码登录，返回 Token |
| POST | `/api/v1/auth/logout` | 注销当前 Token |
| GET | `/api/v1/auth/status` | 判断前端是否需要登录 |

---

## 音乐管理 (Music)

### 曲目管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/music` | 按专辑分组获取曲目（分页） |
| GET | `/api/v1/music/list` | 获取所有曲目扁平列表（分页） |
| GET | `/api/v1/music/{id}` | 获取曲目详情 |
| GET | `/api/v1/music/{id}/stream` | 流式播放音频（支持 Range 断点续播） |
| GET | `/api/v1/music/{id}/cover` | 获取封面图片 |
| GET | `/api/v1/music/{id}/lyrics` | 获取 .lrc 歌词 |
| PUT | `/api/v1/music/{id}/favorite` | 切换收藏状态 |
| POST | `/api/v1/music/{id}/play` | 记录播放次数 |

### 搜索与发现

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/music/search/title` | 按标题搜索 |
| GET | `/api/v1/music/search/artist` | 按艺术家搜索 |
| GET | `/api/v1/music/favorites` | 收藏列表 |
| GET | `/api/v1/music/recently-added` | 最近添加 |
| GET | `/api/v1/music/recently-played` | 最近播放 |
| GET | `/api/v1/music/most-played` | 最常播放 |
| GET | `/api/v1/music/recommendations` | 推荐歌单 |

### 播放列表 (Playlist)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/music/playlists` | 获取所有播放列表 |
| POST | `/api/v1/music/playlists` | 创建播放列表 |
| PUT | `/api/v1/music/playlists/{id}` | 更新播放列表 |
| DELETE | `/api/v1/music/playlists/{id}` | 删除播放列表 |
| GET | `/api/v1/music/playlists/{id}/tracks` | 获取播放列表曲目 |
| POST | `/api/v1/music/playlists/{id}/tracks` | 添加曲目到播放列表 |
| DELETE | `/api/v1/music/playlists/{playlistId}/tracks/{trackId}` | 从播放列表移除曲目 |

---

## 漫画管理 (Comic)

### 漫画浏览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/comic/{id}` | 获取漫画详情 |
| GET | `/api/v1/comic/{id}/cover` | 获取封面图片 |
| GET | `/api/v1/comic/{id}/pages` | 获取页面列表 |
| GET | `/api/v1/comic/{id}/pages/{pageNum}` | 获取页面图片 |
| GET | `/api/v1/comic/{id}/characters` | 获取漫画角色列表 |
| GET | `/api/v1/comic/character/{id}/image` | 获取角色图片 |

### 进度与收藏

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/comic/{id}/progress` | 获取阅读进度 |
| PUT | `/api/v1/comic/{id}/progress` | 保存阅读进度 |
| DELETE | `/api/v1/comic/{id}/progress` | 删除阅读进度 |
| PUT | `/api/v1/comic/{id}/favorite` | 设置收藏状态 |
| GET | `/api/v1/comic/favorites` | 收藏列表 |

### 搜索

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/comic/search/title` | 按标题搜索 |
| GET | `/api/v1/comic/search/author` | 按作者搜索 |
| GET | `/api/v1/comic/bangumi/search` | 搜索 Bangumi 漫画 |

### 系列管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/comic/series` | 按系列分组获取漫画（分页） |
| GET | `/api/v1/comic/series/cover` | 获取系列封面 |
| POST | `/api/v1/comic/series/rescrape` | 按系列名重新刮削 |

### 元数据绑定

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/comic/{id}/bangumi/bind` | 绑定 Bangumi 元数据 |

### 刮削进度

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/comic/scrape/progress` | 刮削进度 |

---

## 电子书管理 (Ebook)

### 电子书浏览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/ebook/{id}` | 获取电子书详情 |
| GET | `/api/v1/ebook/{id}/cover` | 获取封面图片 |
| GET | `/api/v1/ebook/{id}/chapters` | 获取章节列表 |
| GET | `/api/v1/ebook/{id}/chapters/{chapterNum}` | 获取章节内容 |
| GET | `/api/v1/ebook/{id}/read` | 在线阅读（整书或指定章节） |
| GET | `/api/v1/ebook/{id}/download` | 下载电子书文件 |
| GET | `/api/v1/ebook/{id}/image` | 获取 epub 内嵌图片 |
| GET | `/api/v1/ebook/{id}/characters` | 获取电子书角色列表 |
| GET | `/api/v1/ebook/character/{id}/image` | 获取角色图片 |

### 进度与收藏

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/ebook/{id}/progress` | 获取阅读进度 |
| PUT | `/api/v1/ebook/{id}/progress` | 保存阅读进度 |
| DELETE | `/api/v1/ebook/{id}/progress` | 删除阅读进度 |
| PUT | `/api/v1/ebook/{id}/favorite` | 设置收藏状态 |
| GET | `/api/v1/ebook/favorites` | 收藏列表 |

### 搜索

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/ebook/search/title` | 按书名搜索 |
| GET | `/api/v1/ebook/search/author` | 按作者搜索 |
| GET | `/api/v1/ebook/openlibrary/search` | 搜索 Open Library |

### 系列管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/ebook/series` | 按系列分组获取电子书（分页） |
| GET | `/api/v1/ebook/series/cover` | 获取系列封面 |
| POST | `/api/v1/ebook/series/rescrape` | 按系列名重新刮削 |

### 元数据绑定

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/ebook/{id}/bangumi/bind` | 绑定 Bangumi 元数据 |
| POST | `/api/v1/ebook/{id}/openlibrary/bind` | 绑定 Open Library 元数据 |
| GET | `/api/v1/ebook/{id}/bangumi/search` | 搜索 Bangumi 轻小说 |
| PUT | `/api/v1/ebook/{id}/metadata` | 手动更新元数据 |

### 其他

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/ebook/recently-added` | 最近添加 |
| GET | `/api/v1/ebook/recently-read` | 最近阅读 |
| GET | `/api/v1/ebook/stats` | 阅读统计 |
| GET | `/api/v1/ebook/scrape/progress` | 刮削进度 |
| POST | `/api/v1/ebook/auto-scrape` | 批量自动刮削 |
| GET | `/api/v1/ebook/image-proxy` | 代理远程图片 |

---

## 视频管理 (Video)

### 视频浏览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/{id}` | 获取视频详情 |
| GET | `/api/v1/video/{id}/stream` | 视频流播放（支持 Range） |
| GET | `/api/v1/video/{id}/stream/transcode` | 视频转码流播放（1080p/720p/480p） |
| GET | `/api/v1/video/{id}/cover` | 获取封面图片 |
| GET | `/api/v1/video/{id}/fanart` | 获取横屏背景图 |
| POST | `/api/v1/video/{id}/covers` | 下载封面图片 |
| GET | `/api/v1/video/{id}/actors` | 获取演员列表 |
| GET | `/api/v1/video/actor/{actorId}/image` | 获取演员头像 |
| GET | `/api/v1/video/{id}/nfo` | 获取 NFO 内容 |
| POST | `/api/v1/video/{id}/nfo` | 生成 NFO 文件 |
| GET | `/api/v1/video/{id}/playlist.m3u` | 生成系列 M3U 播放列表 |

### 进度与收藏

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/{id}/progress` | 获取观看进度 |
| PUT | `/api/v1/video/{id}/progress` | 保存观看进度 |
| PUT | `/api/v1/video/{id}/favorite` | 设置收藏状态 |
| PUT | `/api/v1/video/{id}/watched` | 设置已观看状态 |
| GET | `/api/v1/video/favorites` | 收藏列表 |

### 搜索

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/search/title` | 按标题搜索 |
| GET | `/api/v1/video/search/director` | 按导演搜索 |

### TMDB 元数据

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/video/{id}/tmdb/bind` | 绑定 TMDB 元数据 |
| POST | `/api/v1/video/{id}/tmdb/refresh` | 刷新 TMDB 元数据 |
| POST | `/api/v1/video/{id}/tmdb/unbind` | 解绑 TMDB 元数据 |
| GET | `/api/v1/video/tmdb/search` | 搜索 TMDB |
| POST | `/api/v1/video/tmdb/rescrape-library/{libraryId}` | 按资源库重新刮削 |

### 刮削进度

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/scrape/progress` | 刮削进度 |

### 视频系列管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/video/series` | 获取所有系列（分页） |
| GET | `/api/v1/video/series/{id}` | 获取系列详情 |
| GET | `/api/v1/video/series/{id}/cover` | 获取系列封面 |
| GET | `/api/v1/video/series/{id}/fanart` | 获取系列背景图 |

---

## 媒体系列 (Media Series)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/media/series` | 获取系列列表（可按类型筛选） |
| GET | `/api/v1/media/series/{id}` | 获取系列详情 |
| GET | `/api/v1/media/series/{id}/cover` | 获取系列封面 |
| GET | `/api/v1/media/series/{id}/characters` | 获取系列角色 |
| GET | `/api/v1/media/series/character/{id}/image` | 获取角色图片 |
| PUT | `/api/v1/media/series/{id}/favorite` | 切换系列收藏 |
| POST | `/api/v1/media/series/{id}/rescrape` | 重新刮削系列 |

---

## 媒体资源库管理 (Media Library)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/media-libraries` | 获取所有资源库 |
| POST | `/api/v1/media-libraries` | 创建资源库 |
| GET | `/api/v1/media-libraries/{id}` | 获取资源库详情 |
| PUT | `/api/v1/media-libraries/{id}` | 更新资源库 |
| DELETE | `/api/v1/media-libraries/{id}` | 删除资源库 |
| PUT | `/api/v1/media-libraries/{id}/toggle` | 启用/禁用资源库 |
| POST | `/api/v1/media-libraries/{id}/scan` | 扫描指定资源库 |
| POST | `/api/v1/media-libraries/scan` | 扫描所有启用的资源库 |
| GET | `/api/v1/media-libraries/browse` | 浏览服务器目录 |

---

## 系统设置 (Settings)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/settings` | 获取所有设置 |
| GET | `/api/v1/settings/{key}` | 获取单个设置 |
| PUT | `/api/v1/settings/{key}` | 更新设置 |

---

## 日志导出 (Logs)

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/logs` | 列出可用日志文件 |
| GET | `/api/v1/logs/{fileName}` | 下载指定日志文件 |

---

## 统一响应格式

所有接口返回统一的 `ApiResponse<T>` 格式：

```json
{
  "success": true,
  "message": "请求成功",
  "data": {}
}
```

## 分页响应格式

分页接口返回 `PageResponse<T>` 格式：

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 100,
  "totalPages": 5
}
```

## 分页参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| page | int | 0 | 页码（0-based） |
| size | int | 20 | 每页大小 |

## 统计

- 共 **9 个模块**
- **100+ 个接口**
- 覆盖音乐、漫画、电子书、视频四大媒体类型
- 支持元数据管理、流媒体播放、收藏、阅读/观看进度、刮削、搜索等功能
