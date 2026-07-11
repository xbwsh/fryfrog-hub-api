# Fryfrog Hub API 接口文档

> 基础地址: `http://localhost:20058/api/v1`

## 统一响应格式

所有接口返回统一的 `ApiResponse` 格式：

```json
{
  "success": true,
  "message": "可选的提示消息",
  "data": "响应数据"
}
```

---

## 1. 认证接口 (`/auth`)

### 1.1 登录
- **POST** `/auth/login`
- **请求体**:
```json
{
  "password": "1234"
}
```
- **响应**:
```json
{
  "success": true,
  "token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

### 1.2 登出
- **POST** `/auth/logout`
- **Header**: `Authorization: Bearer <token>`
- **响应**:
```json
{
  "success": true
}
```

### 1.3 认证状态
- **GET** `/auth/status`
- **响应**:
```json
{
  "enabled": true
}
```

---

## 2. 媒体资源库管理 (`/media-libraries`)

### 2.1 获取所有资源库
- **GET** `/media-libraries`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "电影库",
      "type": "VIDEO",
      "path": "/data/media/video",
      "enabled": true,
      "createdAt": "2024-01-01T00:00:00",
      "updatedAt": "2024-01-01T00:00:00"
    }
  ]
}
```

### 2.2 获取资源库详情
- **GET** `/media-libraries/{id}`
- **响应**: 同上单个对象

### 2.3 创建资源库
- **POST** `/media-libraries`
- **请求体**:
```json
{
  "name": "音乐库",
  "type": "MUSIC",
  "path": "/data/media/music"
}
```

### 2.4 更新资源库
- **PUT** `/media-libraries/{id}`

### 2.5 删除资源库
- **DELETE** `/media-libraries/{id}`
- **响应**:
```json
{
  "success": true,
  "data": { "deleted": 1 }
}
```

### 2.6 启用/禁用资源库
- **PUT** `/media-libraries/{id}/toggle`

### 2.7 扫描所有资源库
- **POST** `/media-libraries/scan`
- **响应**:
```json
{
  "success": true,
  "data": {
    "scan": {
      "video:1": "ok",
      "music:2": "ok"
    },
    "organize": { ... },
    "elapsedMs": 5432
  }
}
```

### 2.8 扫描指定资源库
- **POST** `/media-libraries/{id}/scan`

### 2.9 浏览服务器目录
- **GET** `/media-libraries/browse?path=/data/media`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "name": "music",
      "path": "/data/media/music",
      "writable": true
    }
  ]
}
```

---

## 3. 音乐管理 (`/music`)

### 3.1 获取所有曲目
- **GET** `/music`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "title": "夜曲",
      "artist": "周杰伦",
      "album": "十一月的萧邦",
      "albumArtist": "周杰伦",
      "genre": "Pop",
      "year": 2005,
      "trackNumber": 1,
      "duration": 225,
      "filePath": "/data/media/music/周杰伦/十一月的萧邦/01-夜曲.mp3",
      "coverArtPath": "/data/media/music/周杰伦/十一月的萧邦/cover.jpg",
      "favorite": false,
      "playCount": 0,
      "lastPlayed": null,
      "hasExternalLyrics": true
    }
  ]
}
```

### 3.2 获取曲目详情
- **GET** `/music/{id}`

### 3.3 按标题搜索
- **GET** `/music/search/title?q=夜曲`

### 3.4 按艺术家搜索
- **GET** `/music/search/artist?q=周杰伦`

### 3.5 获取收藏列表
- **GET** `/music/favorites`

### 3.6 切换收藏状态
- **PUT** `/music/{id}/favorite`
- **请求体** (可选):
```json
{
  "status": true
}
```
> 不传 body 则自动切换

### 3.7 最近播放
- **GET** `/music/recently-played`

### 3.8 最常播放
- **GET** `/music/most-played`

### 3.9 最近添加
- **GET** `/music/recently-added`

### 3.10 记录播放
- **POST** `/music/{id}/play`

### 3.11 推荐歌单
- **GET** `/music/recommendations`
- **响应**:
```json
{
  "success": true,
  "data": {
    "最近常听": [ ... ],
    "随机推荐": [ ... ],
    "年代经典": [ ... ]
  }
}
```

### 3.12 获取所有播放列表
- **GET** `/music/playlists`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "我喜欢的音乐",
      "description": "",
      "createdAt": "2024-01-01T00:00:00"
    }
  ]
}
```

### 3.13 创建播放列表
- **POST** `/music/playlists`
- **请求体**:
```json
{
  "name": "新歌单",
  "description": "描述"
}
```

### 3.14 更新播放列表
- **PUT** `/music/playlists/{id}`

### 3.15 删除播放列表
- **DELETE** `/music/playlists/{id}`

### 3.16 获取播放列表曲目
- **GET** `/music/playlists/{id}/tracks`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "playlistId": 1,
      "trackId": 5,
      "track": { ... },
      "addedAt": "2024-01-01T00:00:00"
    }
  ]
}
```

### 3.17 添加曲目到播放列表
- **POST** `/music/playlists/{id}/tracks`
- **请求体**:
```json
{
  "trackId": 5
}
```

### 3.18 从播放列表移除曲目
- **DELETE** `/music/playlists/{playlistId}/tracks/{trackId}`

### 3.19 获取歌词
- **GET** `/music/{id}/lyrics`
- **响应**: 返回 `.lrc` 歌词文件内容，无歌词返回 `null`

### 3.20 获取封面图片
- **GET** `/music/{id}/cover`
- **响应**: 返回图片文件，无封面返回占位图

### 3.21 播放音乐（流式）
- **GET** `/music/{id}/stream`
- **Header**: `Range: bytes=0-1023` (可选)
- **响应**: 音频文件流

### 3.22 获取歌手图片
- **GET** `/music/{id}/artist/image`

### 3.23 刮削歌手图片
- **POST** `/music/{id}/artist/image/scrape`

---

## 4. 视频管理 (`/video`)

### 4.1 获取所有视频
- **GET** `/video`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "title": "流浪地球2",
      "originalTitle": "The Wandering Earth II",
      "director": "郭帆",
      "actors": "吴京,刘德华",
      "genre": "科幻",
      "year": 2023,
      "durationMinutes": 173,
      "overview": "...",
      "fileName": "流浪地球2.mkv",
      "fileSize": 10737418240,
      "videoCodec": "H.265",
      "videoProfile": "Main 10",
      "pixFmt": "yuv420p10le",
      "displayAspectRatio": "16:9",
      "audioCodec": "AAC",
      "audioChannelLayout": "5.1",
      "resolution": "3840x2160",
      "frameRate": 24.0,
      "bitrateKbps": 15000,
      "format": "MKV",
      "favorite": false,
      "tmdbId": 545611,
      "mediaType": "movie",
      "imdbId": "tt1454468",
      "rating": 8.3,
      "voteCount": 12345,
      "status": "Released",
      "metadataSource": "tmdb",
      "hasNfo": true,
      "hasPoster": true,
      "hasFanart": true,
      "hasMetadataDir": true,
      "scraped": true,
      "isSeries": false,
      "libraryId": 1,
      "seasonNumber": null,
      "episodeNumber": null,
      "watchPosition": 3600.0,
      "watchProgressPercent": 34.8,
      "watched": false,
      "audioIncompatible": false,
      "coverUrl": "/api/v1/video/1/cover",
      "fanartUrl": "/api/v1/video/1/fanart",
      "streamUrl": "/api/v1/video/1/stream"
    }
  ]
}
```

### 4.2 获取视频详情
- **GET** `/video/{id}`

### 4.3 按标题搜索
- **GET** `/video/search/title?q=流浪`

### 4.4 按导演搜索
- **GET** `/video/search/director?q=郭帆`

### 4.5 获取收藏列表
- **GET** `/video/favorites`

### 4.6 设置收藏状态
- **PUT** `/video/{id}/favorite?status=true`

### 4.7 获取视频演员列表
- **GET** `/video/{id}/actors`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "videoId": 1,
      "name": "吴京",
      "role": "刘培强",
      "imageUrl": "...",
      "imagePath": "/data/metadata/video/.../actor.jpg"
    }
  ]
}
```

### 4.8 获取演员头像
- **GET** `/video/actor/{actorId}/image`

### 4.9 获取封面图片
- **GET** `/video/{id}/cover`

### 4.10 获取横屏背景图
- **GET** `/video/{id}/fanart`

### 4.11 搜索 TMDB
- **GET** `/video/tmdb/search?q=流浪地球`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 545611,
      "title": "流浪地球2",
      "originalTitle": "The Wandering Earth II",
      "mediaType": "movie",
      "releaseDate": "2023-01-22",
      "overview": "...",
      "posterPath": "/poster.jpg",
      "backdropPath": "/backdrop.jpg"
    }
  ]
}
```

### 4.12 绑定 TMDB 元数据
- **POST** `/video/{id}/tmdb/bind`
- **请求体**:
```json
{
  "tmdbId": 545611,
  "mediaType": "movie"
}
```

### 4.13 解绑 TMDB 元数据
- **POST** `/video/{id}/tmdb/unbind`
- **响应**:
```json
{
  "success": true,
  "data": {
    "tmdbId": 545611,
    "unbound": 5
  }
}
```

### 4.14 刷新 TMDB 元数据
- **POST** `/video/{id}/tmdb/refresh`
- **响应**:
```json
{
  "success": true,
  "data": {
    "total": 5,
    "videos": [ ... ]
  }
}
```

### 4.15 按资源库重新刮削
- **POST** `/video/tmdb/rescrape-library/{libraryId}`

### 4.16 刮削进度
- **GET** `/video/scrape/progress`
- **响应**:
```json
{
  "success": true,
  "data": {
    "total": 100,
    "completed": 50,
    "failed": 2,
    "running": true,
    "currentItem": "正在处理: xxx.mkv"
  }
}
```

### 4.17 绑定 Hanime 元数据
- **POST** `/video/{id}/hanime/bind?hanimeId=xxx`

### 4.18 刮削 Hanime 元数据
- **GET** `/video/hanime/scrape?hanimeId=xxx`

### 4.19 生成 NFO 文件
- **POST** `/video/{id}/nfo`

### 4.20 下载封面图片
- **POST** `/video/{id}/covers`

### 4.21 获取 NFO 内容
- **GET** `/video/{id}/nfo`
- **响应**: 返回 NFO XML 字符串

### 4.22 获取观看进度
- **GET** `/video/{id}/progress`
- **响应**:
```json
{
  "success": true,
  "data": {
    "videoId": 1,
    "positionSeconds": 3600.0,
    "durationSeconds": 10380.0,
    "completed": false,
    "updatedAt": "2024-01-01T00:00:00"
  }
}
```

### 4.23 保存观看进度
- **PUT** `/video/{id}/progress`
- **请求体**:
```json
{
  "position": 3600.0,
  "duration": 10380.0
}
```

### 4.24 设置已观看状态
- **PUT** `/video/{id}/watched`
- **请求体**:
```json
{
  "completed": true
}
```

### 4.25 视频流播放
- **GET** `/video/{id}/stream`
- **Header**: `Range: bytes=0-1023` (可选)
- **响应**: 视频文件流

### 4.26 获取媒体技术信息
- **GET** `/video/{id}/media-info`
- **响应**:
```json
{
  "success": true,
  "data": {
    "videoCodec": "hevc",
    "videoCodecLong": "H.265 / HEVC",
    "audioCodec": "aac",
    "audioCodecLong": "AAC (Advanced Audio Coding)",
    "audioChannels": 6,
    "audioSampleRate": "48000",
    "resolution": "3840x2160",
    "frameRate": 24.0,
    "bitrateKbps": 15000,
    "durationSeconds": 10380,
    "durationMinutes": 173,
    "format": "matroska,webm"
  }
}
```

### 4.27 获取内嵌字幕列表
- **GET** `/video/{id}/subtitles`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "index": 0,
      "language": "chi",
      "title": "中文字幕",
      "codec": "subrip"
    }
  ]
}
```

### 4.28 获取外挂字幕列表
- **GET** `/video/{id}/subtitle/external`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "name": "movie.srt",
      "language": "chi",
      "size": 12345
    }
  ]
}
```

### 4.29 获取字幕 WebVTT
- **GET** `/video/{id}/subtitle/vtt?index=0` 或 `?file=movie.srt`
- **响应**: WebVTT 格式字幕文本

### 4.30 音频转码
- **POST** `/video/{id}/audio/transcode`
- **响应**:
```json
{
  "success": true,
  "data": {
    "originalCodec": "eac3",
    "transcodedPath": "/data/media/video/movie.aac",
    "message": "转码完成"
  }
}
```

### 4.31 生成系列播放列表
- **GET** `/video/{id}/playlist.m3u`
- **响应**: M3U 播放列表文件

---

## 5. 视频系列管理 (`/video/series`)

### 5.1 获取所有系列
- **GET** `/video/series`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "title": "权力的游戏",
      "posterUrl": "https://image.tmdb.org/...",
      "overview": "...",
      "episodes": [ ... ]
    },
    {
      "id": 100,
      "title": "流浪地球2",
      "isStandalone": true,
      "episodes": [ ... ]
    }
  ]
}
```

### 5.2 获取系列详情
- **GET** `/video/series/{id}`

### 5.3 获取系列封面
- **GET** `/video/series/{id}/cover`

### 5.4 获取系列背景图
- **GET** `/video/series/{id}/fanart`

---

## 6. 漫画管理 (`/comic`)

### 6.1 获取所有漫画
- **GET** `/comic`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "title": "进击的巨人",
      "volume": "Vol.01",
      "seriesRef": {
        "id": 1,
        "title": "进击的巨人"
      },
      "filePath": "/data/media/comic/进击的巨人/Vol.01.cbz",
      "fileSize": 52428800,
      "totalPages": 200,
      "coverArtPath": "/data/media/comic/进击的巨人/cover.jpg",
      "favorite": false,
      "hasCover": true,
      "overview": "...",
      "rating": 9.0,
      "bangumiId": 12345,
      "coverUrl": "/api/v1/comic/1/cover"
    }
  ]
}
```

### 6.2 按系列分组获取漫画
- **GET** `/comic/series`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "name": "进击的巨人",
      "coverArtPath": "...",
      "comics": [ ... ]
    }
  ]
}
```

### 6.3 获取漫画详情
- **GET** `/comic/{id}`

### 6.4 按标题搜索
- **GET** `/comic/search/title?q=巨人`

### 6.5 按作者搜索
- **GET** `/comic/search/author?q=谏山创`

### 6.6 获取收藏列表
- **GET** `/comic/favorites`

### 6.7 设置收藏状态
- **PUT** `/comic/{id}/favorite?status=true`

### 6.8 获取页面列表
- **GET** `/comic/{id}/pages`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "pageNum": 1,
      "fileName": "001.jpg",
      "width": 1200,
      "height": 1800
    }
  ]
}
```

### 6.9 获取页面图片
- **GET** `/comic/{id}/pages/{pageNum}`
- **响应**: 图片文件

### 6.10 获取封面图片
- **GET** `/comic/{id}/cover`

### 6.11 获取系列封面
- **GET** `/comic/series/cover?series=进击的巨人`

### 6.12 获取漫画角色列表
- **GET** `/comic/{id}/characters`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "seriesId": 1,
      "name": "艾伦·耶格尔",
      "description": "...",
      "imagePath": "...",
      "imageUrl": "https://..."
    }
  ]
}
```

### 6.13 获取角色图片
- **GET** `/comic/character/{id}/image`

### 6.14 获取阅读进度
- **GET** `/comic/{id}/progress`
- **响应**:
```json
{
  "success": true,
  "data": {
    "comicId": 1,
    "currentPage": 50,
    "totalPages": 200,
    "percentage": 25.0,
    "updatedAt": "2024-01-01T00:00:00"
  }
}
```

### 6.15 保存阅读进度
- **PUT** `/comic/{id}/progress`
- **请求体**:
```json
{
  "currentPage": 50,
  "totalPages": 200
}
```

### 6.16 删除阅读进度
- **DELETE** `/comic/{id}/progress`

### 6.17 搜索 Bangumi 漫画
- **GET** `/comic/bangumi/search?q=进击的巨人`

### 6.18 绑定 Bangumi 元数据
- **POST** `/comic/{id}/bangumi/bind`
- **请求体**:
```json
{
  "bangumiId": 12345
}
```

### 6.19 按系列名重新刮削
- **POST** `/comic/series/rescrape?series=进击的巨人`

### 6.20 刮削进度
- **GET** `/comic/scrape/progress`

---

## 7. 电子书管理 (`/ebook`)

### 7.1 获取所有电子书
- **GET** `/ebook`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "title": "三体",
      "author": "刘慈欣",
      "seriesName": "三体三部曲",
      "volume": "Vol.01",
      "filePath": "/data/media/ebook/三体.epub",
      "fileSize": 1048576,
      "coverArtPath": "...",
      "favorite": false,
      "hasCover": true,
      "overview": "...",
      "rating": 9.5,
      "publishDate": "2008-01-01",
      "publisher": "重庆出版社",
      "isbn": "9787536692930",
      "coverUrl": "/api/v1/ebook/1/cover"
    }
  ]
}
```

### 7.2 按系列分组获取电子书
- **GET** `/ebook/series`

### 7.3 获取系列封面
- **GET** `/ebook/series/cover?series=三体三部曲`

### 7.4 获取电子书详情
- **GET** `/ebook/{id}`

### 7.5 按书名搜索
- **GET** `/ebook/search/title?q=三体`

### 7.6 按作者搜索
- **GET** `/ebook/search/author?q=刘慈欣`

### 7.7 获取收藏列表
- **GET** `/ebook/favorites`

### 7.8 最近添加
- **GET** `/ebook/recently-added`

### 7.9 最近阅读
- **GET** `/ebook/recently-read`

### 7.10 阅读统计
- **GET** `/ebook/stats`
- **响应**:
```json
{
  "success": true,
  "data": {
    "totalBooks": 100,
    "totalRead": 50,
    "readPercentage": 50.0
  }
}
```

### 7.11 设置收藏状态
- **PUT** `/ebook/{id}/favorite?status=true`

### 7.12 获取封面图片
- **GET** `/ebook/{id}/cover`

### 7.13 获取电子书角色列表
- **GET** `/ebook/{id}/characters`

### 7.14 获取角色图片
- **GET** `/ebook/character/{id}/image`

### 7.15 在线阅读
- **GET** `/ebook/{id}/read?chapter=1`
- **响应**: HTML 或纯文本内容

### 7.16 获取 epub 内嵌图片
- **GET** `/ebook/{id}/image?file=images/cover.jpg`

### 7.17 获取章节列表
- **GET** `/ebook/{id}/chapters`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "chapterNum": 1,
      "title": "第一章 疯狂年代",
      "href": "chapter001.xhtml"
    }
  ]
}
```

### 7.18 获取章节内容
- **GET** `/ebook/{id}/chapters/{chapterNum}`

### 7.19 下载电子书
- **GET** `/ebook/{id}/download`

### 7.20 获取阅读进度
- **GET** `/ebook/{id}/progress`

### 7.21 保存阅读进度
- **PUT** `/ebook/{id}/progress`
- **请求体**:
```json
{
  "currentPage": 50,
  "totalPages": 200
}
```

### 7.22 删除阅读进度
- **DELETE** `/ebook/{id}/progress`

### 7.23 批量自动刮削
- **POST** `/ebook/auto-scrape`

### 7.24 搜索 Open Library 书籍
- **GET** `/ebook/openlibrary/search?q=三体`

### 7.25 绑定 Open Library 元数据
- **POST** `/ebook/{id}/openlibrary/bind?olid=OL123456M`

### 7.26 搜索 Bangumi 轻小说
- **GET** `/ebook/{id}/bangumi/search?q=三体&subType=novel`

### 7.27 绑定 Bangumi 元数据
- **POST** `/ebook/{id}/bangumi/bind?bangumiId=12345`

### 7.28 按系列名重新刮削
- **POST** `/ebook/series/rescrape?series=三体三部曲`

### 7.29 代理远程图片
- **GET** `/ebook/image-proxy?url=https://...`

### 7.30 刮削进度
- **GET** `/ebook/scrape/progress`

### 7.31 手动更新元数据
- **PUT** `/ebook/{id}/metadata`
- **请求体**:
```json
{
  "title": "三体",
  "author": "刘慈欣",
  "overview": "..."
}
```

---

## 8. 媒体系列管理 (`/media/series`)

### 8.1 获取系列列表
- **GET** `/media/series?type=comic`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "title": "进击的巨人",
      "mediaType": "comic",
      "coverArtPath": "...",
      "overview": "...",
      "favorite": false,
      "createdAt": "2024-01-01T00:00:00"
    }
  ]
}
```

### 8.2 获取系列详情
- **GET** `/media/series/{id}`
- **响应**:
```json
{
  "success": true,
  "data": {
    "series": { ... },
    "comics": [ ... ],
    "ebooks": [ ... ],
    "characters": [ ... ]
  }
}
```

### 8.3 获取系列封面
- **GET** `/media/series/{id}/cover`

### 8.4 获取系列角色
- **GET** `/media/series/{id}/characters`

### 8.5 获取角色图片
- **GET** `/media/series/character/{id}/image`

### 8.6 切换系列收藏
- **PUT** `/media/series/{id}/favorite?status=true`

### 8.7 重新刮削系列
- **POST** `/media/series/{id}/rescrape`
- **响应**:
```json
{
  "success": true,
  "data": {
    "seriesId": 1,
    "seriesName": "进击的巨人",
    "updated": 5
  }
}
```

---

## 9. 系统设置 (`/settings`)

### 9.1 获取所有设置
- **GET** `/settings`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "key": "tmdb.api-key",
      "value": "***已配置***",
      "description": "TMDB API Key"
    },
    {
      "id": 2,
      "key": "watcher.periodic-scan",
      "value": "true",
      "description": "启用定时扫描"
    }
  ]
}
```

### 9.2 获取单个设置
- **GET** `/settings/{key}`

### 9.3 更新设置
- **PUT** `/settings/{key}`
- **请求体**:
```json
{
  "value": "新值"
}
```

### 9.4 检查 TMDB 配置状态
- **GET** `/settings/tmdb/status`
- **响应**:
```json
{
  "success": true,
  "data": {
    "configured": true
  }
}
```

### 9.5 获取性能相关设置
- **GET** `/settings/performance`
- **响应**:
```json
{
  "success": true,
  "data": {
    "watcher.periodic-scan": true,
    "watcher.periodic-scan-interval": 300
  }
}
```

---

## 10. 日志管理 (`/logs`)

### 10.1 列出可用日志文件
- **GET** `/logs`
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "name": "app.log",
      "size": 1048576,
      "lastModified": 1704067200000
    }
  ]
}
```

### 10.2 导出日志文件
- **GET** `/logs/{fileName}`
- **响应**: 日志文件下载

---

## 通用说明

### 认证
所有接口需要在 Header 中携带 Token：
```
Authorization: Bearer <token>
```

### 图片接口
以下接口直接返回图片文件（非 JSON）：
- `GET /music/{id}/cover`
- `GET /music/{id}/artist/image`
- `GET /video/{id}/cover`
- `GET /video/{id}/fanart`
- `GET /video/actor/{actorId}/image`
- `GET /video/series/{id}/cover`
- `GET /video/series/{id}/fanart`
- `GET /comic/{id}/cover`
- `GET /comic/{id}/pages/{pageNum}`
- `GET /comic/series/cover?series=xxx`
- `GET /comic/character/{id}/image`
- `GET /ebook/{id}/cover`
- `GET /ebook/series/cover?series=xxx`
- `GET /ebook/character/{id}/image`
- `GET /media/series/{id}/cover`
- `GET /media/series/character/{id}/image`

### 流媒体接口
以下接口支持 Range 请求（断点续播）：
- `GET /music/{id}/stream`
- `GET /video/{id}/stream`

### Swagger 文档
访问 `http://localhost:20058/swagger-ui.html` 查看完整的 Swagger 交互式文档。
