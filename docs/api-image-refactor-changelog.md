# 图片接口重构 - 前端对接文档

## 变更概览

本次重构统一了所有模块的图片接口命名、DTO 字段结构，并修复了数据泄漏问题。

---

## 一、Breaking Changes（必须修改）

### 1. API 路径变更

| 模块 | 旧路径 | 新路径 | 说明 |
|------|--------|--------|------|
| 音乐 | `GET /api/v1/music/{id}/artist-image` | `GET /api/v1/music/{id}/artist/image` | 歌手图片 |
| 音乐 | `POST /api/v1/music/{id}/artist-image/scrape` | `POST /api/v1/music/{id}/artist/image/scrape` | 刮削歌手图片 |
| 电子书 | `GET /api/v1/ebook/{id}/epub-image?file=xxx` | `GET /api/v1/ebook/{id}/image?file=xxx` | epub 内嵌图片 |
| 视频 | `GET /api/v1/video/{id}/poster` | **已删除** | 用 `/cover` 替代 |
| 视频系列 | `GET /api/v1/video/series/{id}/poster` | `GET /api/v1/video/series/{id}/cover` | 系列封面 |

### 2. DTO 字段名变更

#### 音乐模块 (`MusicTrack`)

```diff
- "artistImageUrl": "/api/v1/music/1/artist-image"
+ "imageUrl": "/api/v1/music/1/artist/image"
```

#### 视频模块 (`VideoDTO`)

```diff
- "posterApiUrl": "/api/v1/video/1/poster"
+ "coverUrl": "/api/v1/video/1/cover"

- "fanartApiUrl": "/api/v1/video/1/fanart"
+ "fanartUrl": "/api/v1/video/1/fanart"

- "streamApiUrl": "/api/v1/video/1/stream"
+ "streamUrl": "/api/v1/video/1/stream"
```

#### 视频系列 (`SeriesDTO`)

```diff
+ "coverUrl": "/api/v1/video/series/1/cover"
+ "fanartUrl": "/api/v1/video/series/1/fanart"
```

### 3. 漫画模块返回结构变更

漫画接口现在返回 `ComicDTO` 而不是 `Comic` 实体。

**新增字段：**
```json
{
  "hasCover": true,
  "coverUrl": "/api/v1/comic/1/cover"
}
```

**移除字段：**
```diff
- "coverArtPath": "/path/to/cover.jpg"    // 本地路径，已移除
- "thumbnailDirPath": "/path/to/thumbs"   // 本地路径，已移除
- "posterUrl": "https://..."              // 远程URL，已移除
- "filePath": "/path/to/file.cbz"         // 本地路径，已移除
```

**漫画系列 `ComicSeries.comics` 类型变更：**
```diff
- "comics": [{ ...Comic实体... }]
+ "comics": [{ ...ComicDTO... }]
```

### 4. 远程 URL 字段全部隐藏

以下字段已添加 `@JsonIgnore`，不再出现在 JSON 响应中：

| 模块 | 字段 | 说明 |
|------|------|------|
| 视频 | `VideoDTO.posterUrl` | TMDB 海报 URL |
| 视频 | `VideoDTO.backdropUrl` | TMDB 背景图 URL |
| 视频 | `VideoActor.imageUrl` | TMDB 演员头像 URL |
| 视频系列 | `SeriesDTO.posterUrl` | TMDB 海报 URL |
| 视频系列 | `SeriesDTO.backdropUrl` | TMDB 背景图 URL |
| 视频系列 | `VideoSeries.posterUrl` | TMDB 海报 URL |
| 视频系列 | `VideoSeries.backdropUrl` | TMDB 背景图 URL |
| 漫画 | `Comic.posterUrl` | AniList/Bangumi URL |

**前端应通过 API 端点获取图片，而非直接使用远程 URL。**

---

## 二、新增功能

### 1. 所有封面端点支持占位图降级

当图片文件不存在时，不再返回 404，而是返回一张带标题的占位图。

| 模块 | 端点 | 占位图尺寸 |
|------|------|-----------|
| 音乐 | `GET /api/v1/music/{id}/cover` | 300x300 |
| 漫画 | `GET /api/v1/comic/{id}/cover` | 300x400 |
| 电子书 | `GET /api/v1/ebook/{id}/cover` | 300x400（已有） |
| 视频 | `GET /api/v1/video/{id}/cover` | 300x450（已有） |
| 视频系列 | `GET /api/v1/video/series/{id}/cover` | 300x450 |

### 2. 视频系列新增 cover 端点

```
GET /api/v1/video/series/{id}/cover   → 竖屏封面
GET /api/v1/video/series/{id}/fanart  → 横屏背景图
```

### 3. 统一的图片端点命名规则

| 路径模式 | 用途 |
|----------|------|
| `/{id}/cover` | 竖屏封面/海报 |
| `/{id}/fanart` | 横屏背景图 |
| `/actor/{id}/image` | 演员头像 |
| `/character/{id}/image` | 角色图片 |
| `/artist/image` | 歌手图片 |

---

## 三、前端代码修改清单

### 搜索替换清单

```
# 音乐模块
/artist-image → /artist/image

# 电子书模块
/epub-image → /image

# 视频模块
/poster → /cover（仅封面用途）

# DTO 字段名
artistImageUrl → imageUrl
posterApiUrl → coverUrl
fanartApiUrl → fanartUrl
streamApiUrl → streamUrl
```

### 图片 URL 获取方式

**旧方式（不再可用）：**
```js
// 直接使用远程 URL
<img src={video.posterUrl} />

// 使用旧的 API 路径
<img src="/api/v1/video/1/poster" />
```

**新方式：**
```js
// 通过统一的 coverUrl 字段
<img src={video.coverUrl} />        // 视频封面
<img src={video.fanartUrl} />       // 视频背景图
<img src={series.coverUrl} />       // 系列封面
<img src={comic.coverUrl} />        // 漫画封面
<img src={track.imageUrl} />        // 歌手图片
<img src={actor.imageUrl} />        // 演员头像
<img src={character.imageUrl} />    // 角色图片
```

### 漫画模块适配

```js
// 旧：漫画详情有 coverArtPath
const path = comic.coverArtPath;

// 新：使用 hasCover + coverUrl
if (comic.hasCover) {
  <img src={comic.coverUrl} />
}
```

---

## 四、完整端点清单

### 音乐 `/api/v1/music`
| 端点 | 方法 | 返回 | 说明 |
|------|------|------|------|
| `/{id}/cover` | GET | Resource | 封面（含占位图降级） |
| `/{id}/artist/image` | GET | Resource | 歌手图片 |
| `/{id}/artist/image/scrape` | POST | ApiResponse | 刮削歌手图片 |
| `/{id}/stream` | GET | Resource | 音频流 |

### 漫画 `/api/v1/comic`
| 端点 | 方法 | 返回 | 说明 |
|------|------|------|------|
| `/{id}/cover` | GET | Resource | 封面（含占位图降级） |
| `/series/cover?series=xxx` | GET | Resource | 系列封面 |
| `/{id}/pages/{pageNum}` | GET | Resource | 页面图片 |
| `/character/{id}/image` | GET | Resource | 角色图片 |

### 电子书 `/api/v1/ebook`
| 端点 | 方法 | 返回 | 说明 |
|------|------|------|------|
| `/{id}/cover` | GET | Resource | 封面（含占位图降级） |
| `/{id}/image?file=xxx` | GET | Resource | epub 内嵌图片 |

### 视频 `/api/v1/video`
| 端点 | 方法 | 返回 | 说明 |
|------|------|------|------|
| `/{id}/cover` | GET | Resource | 封面（含占位图降级） |
| `/{id}/fanart` | GET | Resource | 横屏背景图 |
| `/actor/{id}/image` | GET | Resource | 演员头像 |

### 视频系列 `/api/v1/video/series`
| 端点 | 方法 | 返回 | 说明 |
|------|------|------|------|
| `/{id}/cover` | GET | Resource | 系列封面（含占位图降级） |
| `/{id}/fanart` | GET | Resource | 系列背景图 |
