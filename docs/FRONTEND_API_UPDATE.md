# 后端 API 更新文档（与前端对接用）

> 更新日期：2026-06-25

---

## 一、核心规范变更

**所有 POST/PUT 请求统一使用 JSON 格式**，不再支持 form-urlencoded。

---

## 二、新增接口

### 1. 一键刷新资源库（四个模块统一）

| 模块 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 漫画 | POST | `/api/v1/comic/rescan` | 扫描所有根路径 → 整理文件夹 → 自动刮削 |
| 音乐 | POST | `/api/v1/music/rescan` | 扫描所有根路径 → 刮削歌词/封面 |
| 视频 | POST | `/api/v1/video/rescan` | 扫描所有根路径 → 整理文件夹 → 自动刮削 |
| 电子书 | POST | `/api/v1/ebook/rescan` | 扫描所有根路径 |

- 无需传参，直接 POST 即可
- 后端自动遍历配置的所有根路径
- 异步执行，立即返回 "Rescan started" 提示
- 前端可轮询列表接口查看更新结果

### 2. 获取角色图片

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/comic/character/{id}/image` | 优先返回本地图片，无则302重定向到远程URL |

---

## 三、接口变更

### 视频保存观看进度（格式变更）

**旧方式**（URL参数）：
```
PUT /api/v1/video/{id}/progress?position=120&duration=300
```

**新方式**（JSON body）：
```
PUT /api/v1/video/{id}/progress
Content-Type: application/json

{
  "position": 120.5,
  "duration": 300.0
}
```

### 漫画保存阅读进度（无变化，确认格式）

```
PUT /api/v1/comic/{id}/progress
Content-Type: application/json

{
  "currentPage": 17,
  "totalPages": 230
}
```

---

## 四、数据模型变更

### Comic 实体新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| seriesSummary | String (TEXT) | 系列总览简介 |
| serializationStart | String | 连载开始日期，如 "2019-03-25" |

### ComicCharacter 实体新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| imagePath | String | 角色图片本地存储路径 |

---

## 五、接口响应变更

### GET /api/v1/comic/series（系列列表）

系列外层新增两个字段：

```json
{
  "name": "间谍过家家",
  "author": "Tatsuya Endou",
  "coverArtPath": "...",
  "volumeCount": 2,
  "seriesSummary": "代号名〈黄昏〉的男子是西国能力最强的间谍...",
  "serializationStart": "2019-03-25",
  "comics": [
    {
      "title": "间谍过家家",
      "volume": 1,
      "summary": "单行本1自己的简介",
      "rating": 7.4,
      "releaseDate": "2019-07-04",
      "seriesSummary": "系列简介（同上，冗余但可用）"
    },
    {
      "title": "间谍过家家",
      "volume": 2,
      "summary": "单行本2自己的简介",
      "rating": 7.3,
      "releaseDate": null,
      "seriesSummary": "系列简介"
    }
  ]
}
```

**字段分工**：
| 字段 | 所在层级 | 含义 |
|------|----------|------|
| seriesSummary | 系列外层 | 系列总览简介 |
| serializationStart | 系列外层 | 连载开始日期 |
| summary | 每卷内部 | 单行本自己的简介 |
| rating | 每卷内部 | 每卷独立评分 |
| releaseDate | 每卷内部 | 单行本发售日期 |

### GET /api/v1/comic/{id}/characters（角色列表）

每个角色新增 `imagePath` 字段：

```json
{
  "id": 83,
  "comicId": 3,
  "name": "劳埃德·福杰",
  "originalName": "ロイド・フォージャー",
  "imageUrl": "https://lain.bgm.tv/...",
  "imagePath": "/path/to/characters/劳埃德·福杰.jpg",
  "description": "〈黄昏〉的同僚...",
  "role": "主角",
  "source": "bangumi"
}
```

---

## 六、前端对接要点

1. **新增资源后**：调用对应模块的 `POST /rescan` 接口，前端显示 loading，完成后刷新列表
2. **视频进度保存**：改为发 JSON body，不再用 URL 参数
3. **角色图片**：优先用 `imagePath`（本地路径），通过 `/api/v1/comic/character/{id}/image` 获取
4. **系列详情页**：从 series 外层取 `seriesSummary` 和 `serializationStart` 展示
5. **单行本详情页**：从 comic 内部取 `summary`、`rating`、`releaseDate` 展示

---

## 七、文件夹结构（漫画面板展示参考）

```
media-library/comic/
└── {系列名}/
    ├── {卷1}.cbz
    ├── {卷1}_cover.jpg
    ├── series_cover.jpg
    └── characters/
        ├── 角色A.jpg
        └── 角色B.jpg
```

- `coverArtPath` → 漫画封面图片（单行本级别）
- `series_cover.jpg` → 系列封面
- `characters/` → 角色图片目录
