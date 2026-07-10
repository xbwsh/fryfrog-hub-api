# 后端 API 更新文档（与前端对接用）

> 更新日期：2026-06-26

---

## 一、核心规范

**所有 POST/PUT 请求统一使用 JSON 格式**，不再支持 form-urlencoded。

---

## 二、新增接口

### 1. 一键刷新资源库（四个模块统一）

| 模块 | 路径 | 流程 |
|------|------|------|
| 漫画 | `POST /api/v1/comic/rescan` | 扫描 → 整理 → 刮削 |
| 音乐 | `POST /api/v1/music/rescan` | 扫描 → 刮削 |
| 视频 | `POST /api/v1/video/rescan` | 扫描 → 整理 → 刮削 |
| 电子书 | `POST /api/v1/ebook/rescan` | 扫描 |

### 2. 刮削进度查询

| 模块 | 路径 |
|------|------|
| 漫画 | `GET /api/v1/comic/scrape/progress` |
| 音乐 | `GET /api/v1/music/scrape/progress` |
| 视频 | `GET /api/v1/video/scrape/progress` |

返回格式：
```json
{
  "module": "comic",
  "running": true,
  "total": 10,
  "completed": 3,
  "failed": 1,
  "skipped": 2,
  "currentItem": "间谍过家家 Vol.02",
  "percent": 30.0,
  "items": [
    { "name": "间谍过家家 Vol.01", "status": "completed" },
    { "name": "间谍过家家 Vol.02", "status": "processing" },
    { "name": "偷星九月天 Vol.01", "status": "failed", "error": "no results" }
  ]
}
```

状态值：`pending` / `processing` / `completed` / `failed` / `skipped`

### 3. 获取角色/演员图片

| 模块 | 路径 | 说明 |
|------|------|------|
| 漫画 | `GET /api/v1/comic/character/{id}/image` | 优先本地，无则302到远程URL |
| 视频 | `GET /api/v1/video/{id}/actors` | 获取演员列表（含imagePath） |

### 3. 视频演员列表

```
GET /api/v1/video/{id}/actors
```
返回：
```json
[
  {
    "name": "吴京",
    "character": "刘培强",
    "imagePath": "/path/to/actors/吴京.jpg",
    "imageUrl": "https://image.tmdb.org/..."
  }
]
```

---

## 三、接口变更

### 绑定接口改为 JSON body

**漫画 Bangumi 绑定**：
```json
POST /api/v1/comic/{id}/bangumi/bind
Content-Type: application/json

{
  "bangumiId": 279379,
  "bindSeries": true
}
```

**漫画 AniList 绑定**：
```json
POST /api/v1/comic/{id}/anilist/bind
Content-Type: application/json

{
  "anilistId": 12345,
  "bindSeries": true
}
```

**视频 TMDB 绑定**：
```json
POST /api/v1/video/{id}/tmdb/bind
Content-Type: application/json

{
  "tmdbId": 545611,
  "mediaType": "movie"
}
```

### 视频进度保存改为 JSON

```json
PUT /api/v1/video/{id}/progress
Content-Type: application/json

{
  "position": 120.5,
  "duration": 300.0
}
```

### 漫画批量刮削合并 artist 参数

```json
POST /api/v1/music/scrape/all?artist=周杰伦
```
不传 artist 则刮削全部。

### 废弃接口

| 旧接口 | 替代方案 |
|--------|----------|
| `POST /api/v1/video/scan-all` | `POST /api/v1/video/rescan` |
| `POST /api/v1/video/{id}/tmdb/rescrape` | `POST /api/v1/video/{id}/tmdb/refresh` |
| `POST /api/v1/music/scrape/artist` | `POST /api/v1/music/scrape/all?artist=xx` |

---

## 四、系统设置项（前端设置页）

通过 `PUT /api/v1/settings/{key}` 修改，`GET /api/v1/settings` 获取所有。

### 视频
| key | 类型 | 默认 | 说明 |
|-----|------|------|------|
| tmdb.api-key | String | 空 | TMDB API密钥 |
| tmdb.language | String | zh-CN | 语言 |
| tmdb.image-size | String | original | 图片尺寸 |
| tmdb.include-adult | Boolean | true | 成人内容 |

### 全局开关
| key | 类型 | 默认 | 说明 |
|-----|------|------|------|
| watcher.periodic-scan | Boolean | true | 启用定期扫描（发现新文件） |
| watcher.periodic-scan-interval | Integer | 30 | 扫描间隔（秒） |
| scrape.auto-scrape | Boolean | true | 启用自动刮削元数据 |

---

## 五、数据模型变更

### Comic 新增字段
| 字段 | 类型 | 说明 |
|------|------|------|
| seriesSummary | String | 系列总览简介 |
| serializationStart | String | 连载开始日期 |

### ComicCharacter 新增字段
| 字段 | 类型 | 说明 |
|------|------|------|
| imagePath | String | 角色图片本地路径 |

### ComicSeries DTO 新增字段
| 字段 | 类型 | 说明 |
|------|------|------|
| seriesSummary | String | 系列简介 |
| serializationStart | String | 连载开始日期 |

### VideoActor 实体（新增）
| 字段 | 类型 | 说明 |
|------|------|------|
| videoId | Long | 所属视频ID |
| name | String | 演员姓名 |
| character | String | 角色名称 |
| imagePath | String | 头像本地路径 |
| imageUrl | String | 头像远程URL |
| sourceActorId | Long | TMDB演员ID |

---

## 六、接口响应变更

### GET /api/v1/comic/series（系列列表）

```json
{
  "name": "间谍过家家",
  "seriesSummary": "系列简介...",
  "serializationStart": "2019-03-25",
  "coverArtPath": "bangumi_279379_cover.jpg 的路径",
  "comics": [
    {
      "summary": "单行本1简介",
      "rating": 7.4,
      "releaseDate": "2019-07-04"
    }
  ]
}
```

### GET /api/v1/comic/{id}/characters

```json
[
  {
    "name": "劳埃德·福杰",
    "originalName": "ロイド・フォージャー",
    "imagePath": "/path/to/characters/劳埃德·福杰.jpg",
    "imageUrl": "https://lain.bgm.tv/..."
  }
]
```

---

## 七、前端对接要点

1. **新增资源后**：调 `POST /{module}/rescan`，显示 loading，完成后刷新列表
2. **视频进度**：改为发 JSON body
3. **漫画绑定**：改为发 JSON body（bangumiId + bindSeries）
4. **视频绑定**：改为发 JSON body（tmdbId + mediaType）
5. **角色/演员图片**：优先用 imagePath 本地路径
6. **系列详情页**：从 series 外层取 seriesSummary、serializationStart
7. **单行本详情页**：从 comic 内部取 summary、rating、releaseDate
8. **设置页**：支持所有配置项的读取和修改
9. **macOS 用户**：建议开启 watcher.periodic-scan

---

## 八、文件夹结构

### 漫画
```
{系列名}/
├── {卷}.epub
├── bangumi_{id}_cover.jpg
└── characters/
    └── {角色名}.jpg
```

### 视频
```
{系列名}/
├── {集}.mkv
├── {集}.nfo
├── {集}-poster.jpg
├── {集}-fanart.jpg
└── actors/
    └── {演员名}.jpg
```
