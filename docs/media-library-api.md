# 媒体资源库 API 文档

> Base URL: `/api/v1/media-libraries`
>
> Content-Type: `application/json`

## 概述

媒体资源库（Media Library）是系统中管理媒体文件目录的核心模块。每个资源库代表一个文件系统目录，并关联一个类型，用于指导 TMDB 刮削时的搜索范围。

### 资源库类型

| 类型 | 值 | 说明 | 刮削行为 |
|---|---|---|---|
| 电影 | `MOVIE` | 仅存放电影文件 | 仅搜索 TMDB 电影 |
| 电视剧 | `TV` | 仅存放电视剧文件 | 仅搜索 TMDB 电视剧 |
| 混合 | `MIXED` | 电影和电视剧混合存放 | 同时搜索电影和电视剧，按评分排序 |

### 核心逻辑

- 扫描资源库时，每个视频文件会绑定所属资源库 ID
- 刮削时根据资源库类型决定搜索范围
- **示例**："堀与宫村"电影版放在 `MOVIE` 类型库中，只会匹配 TMDB 电影结果；电视剧版放在 `TV` 类型库中，只会匹配 TMDB 电视剧结果

---

## 接口列表

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| 1 | GET | `/api/v1/media-libraries` | 获取所有资源库 |
| 2 | GET | `/api/v1/media-libraries/{id}` | 获取资源库详情 |
| 3 | POST | `/api/v1/media-libraries` | 创建资源库 |
| 4 | PUT | `/api/v1/media-libraries/{id}` | 更新资源库 |
| 5 | DELETE | `/api/v1/media-libraries/{id}` | 删除资源库 |
| 6 | PUT | `/api/v1/media-libraries/{id}/toggle` | 启用/禁用 |
| 7 | GET | `/api/v1/media-libraries/browse` | 浏览服务器目录 |
| 8 | POST | `/api/v1/video/tmdb/rescrape-library/{libraryId}` | 按资源库重新刮削 |

---

### 1. 获取所有资源库

```
GET /api/v1/media-libraries
```

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "name": "电影库",
      "path": "/media/movies",
      "type": "MOVIE",
      "enabled": true,
      "sortOrder": 0,
      "description": "存放所有电影",
      "createdAt": "2026-06-29T16:00:00",
      "updatedAt": "2026-06-29T16:00:00"
    },
    {
      "id": 2,
      "name": "日剧库",
      "path": "/media/japanese-drama",
      "type": "TV",
      "enabled": true,
      "sortOrder": 1,
      "description": "日剧资源",
      "createdAt": "2026-06-29T16:01:00",
      "updatedAt": "2026-06-29T16:01:00"
    }
  ]
}
```

---

### 2. 获取单个资源库

```
GET /api/v1/media-libraries/{id}
```

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| id | Long | 资源库 ID |

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "name": "电影库",
    "path": "/media/movies",
    "type": "MOVIE",
    "enabled": true,
    "sortOrder": 0,
    "description": "存放所有电影",
    "createdAt": "2026-06-29T16:00:00",
    "updatedAt": "2026-06-29T16:00:00"
  }
}
```

---

### 3. 创建资源库

```
POST /api/v1/media-libraries
```

**请求体**：

```json
{
  "name": "日剧库",
  "path": "/media/japanese-drama",
  "type": "TV",
  "enabled": true,
  "sortOrder": 1,
  "description": "存放日剧资源"
}
```

**字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| name | String | ✅ | 资源库名称，如"电影库"、"日剧库" |
| path | String | ✅ | 目录绝对路径，如 `/media/movies` |
| type | String | ✅ | `MOVIE` / `TV` / `MIXED` |
| enabled | Boolean | 否 | 是否启用，默认 `true` |
| sortOrder | Integer | 否 | 排序序号，默认按当前数量自增 |
| description | String | 否 | 备注说明 |

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 3,
    "name": "日剧库",
    "path": "/media/japanese-drama",
    "type": "TV",
    "enabled": true,
    "sortOrder": 1,
    "description": "存放日剧资源",
    "createdAt": "2026-06-29T16:05:00",
    "updatedAt": "2026-06-29T16:05:00"
  }
}
```

---

### 4. 更新资源库

```
PUT /api/v1/media-libraries/{id}
```

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| id | Long | 资源库 ID |

**请求体**（所有字段均可选，传哪个更新哪个）：

```json
{
  "name": "日剧库（已改名）",
  "path": "/media/japanese-drama-v2",
  "type": "TV",
  "enabled": false,
  "sortOrder": 2,
  "description": "更新后的备注"
}
```

**响应**：同创建接口，返回更新后的完整对象。

---

### 5. 删除资源库

```
DELETE /api/v1/media-libraries/{id}
```

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| id | Long | 资源库 ID |

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "deleted": 3
  }
}
```

**注意**：删除资源库不会删除其中的视频文件，只会解除数据库中的关联关系。已扫描入库的视频的 `libraryId` 会变为 `null`。

---

### 6. 启用/禁用资源库

```
PUT /api/v1/media-libraries/{id}/toggle
```

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| id | Long | 资源库 ID |

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "name": "电影库",
    "path": "/media/movies",
    "type": "MOVIE",
    "enabled": false,
    "sortOrder": 0,
    "description": "存放所有电影",
    "createdAt": "2026-06-29T16:00:00",
    "updatedAt": "2026-06-29T16:10:00"
  }
}
```

**说明**：禁用的资源库在扫描时会被跳过，其中已有的视频不受影响。

---

### 7. 浏览服务器目录

```
GET /api/v1/media-libraries/browse?path=/media
```

用于前端目录选择器，列出指定路径下的子目录。

**查询参数**：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| path | String | 否 | 目录路径，不传则从系统根目录开始 |

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "name": "movies",
      "path": "/media/movies",
      "writable": true
    },
    {
      "name": "music",
      "path": "/media/music",
      "writable": true
    },
    {
      "name": "system",
      "path": "/media/system",
      "writable": false
    }
  ]
}
```

**字段说明**：

| 字段 | 类型 | 说明 |
|---|---|---|
| name | String | 目录名 |
| path | String | 完整绝对路径 |
| writable | Boolean | 当前用户是否有写入权限 |

**前端交互流程**：

```
1. 用户点击"浏览"按钮
2. 调用 GET /api/v1/media-libraries/browse（不传 path）→ 返回根目录列表
3. 用户点击某个目录 → 调用 GET /api/v1/media-libraries/browse?path=/media/movies
4. 逐级深入，直到用户选择目标目录
5. 将最终选择的 path 填入表单的"路径"字段
```

**注意**：此接口仅返回目录（不含文件），已过滤隐藏目录。

---

## 与扫描/刮削的关联

### 触发扫描

```
POST /api/v1/video/rescan
```

该接口会：
1. 遍历所有**已启用**的资源库
2. 按资源库 ID 扫描每个目录下的视频文件
3. 为每个视频记录其所属资源库
4. 整理文件夹
5. 自动刮削未绑定的视频（根据资源库类型过滤搜索范围）

### 按资源库重新刮削

```
POST /api/v1/video/tmdb/rescrape-library/{libraryId}
```

当资源库类型设置错误导致刮削结果不准确时，使用此接口重新刮削。

**场景**：用户先以 MIXED 类型扫描了资源库，后来改为 TV 类型，需要重新刮削以修正结果。

**路径参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| libraryId | Long | 资源库 ID |

**响应**：

```json
{
  "code": 200,
  "message": "success",
  "data": "Rescrape started for library 2"
}
```

**执行流程**：
1. 解绑该资源库中所有已绑定 TMDB 的视频
2. 重新扫描并根据资源库类型过滤搜索范围
3. 为每个视频重新匹配最佳 TMDB 结果

---

### 视频实体新增字段

```json
{
  "id": 1,
  "title": "堀与宫村",
  "libraryId": 2,
  "mediaType": "tv",
  "tmdbId": 123456,
  ...
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| libraryId | Long | 所属资源库 ID，可为 null（未归属任何库） |
| mediaType | String | TMDB 媒体类型：`movie` 或 `tv` |

---

## 前端页面设计建议

### 资源库管理页面

```
┌─────────────────────────────────────────────────────┐
│  媒体资源库管理                        [+ 添加资源库] │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌──────┬────────┬──────────────┬──────┬──────┬───┐ │
│  │ 排序 │ 名称   │ 路径         │ 类型 │ 状态 │操作│ │
│  ├──────┼────────┼──────────────┼──────┼──────┼───┤ │
│  │  ☰   │ 电影库 │ /media/movie │ MOVIE│  ✅  │ ✏🗑│ │
│  │  ☰   │ 日剧库 │ /media/drama │ TV   │  ✅  │ ✏🗑│ │
│  │  ☰   │ 综合库 │ /media/misc  │ MIXED│  ❌  │ ✏🗑│ │
│  └──────┴────────┴──────────────┴──────┴──────┴───┘ │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 添加/编辑弹窗

```
┌─────────────────────────────────────┐
│         添加资源库                    │
├─────────────────────────────────────┤
│                                     │
│  名称：[ 日剧库                   ]  │
│                                     │
│  路径：[ /media/japanese-drama    ]  │
│        [浏览...]                    │
│                                     │
│  类型：[ TV           ▼         ]   │
│        ● MOVIE - 电影               │
│        ● TV    - 电视剧             │
│        ● MIXED - 混合影片            │
│                                     │
│  备注：[ 存放日剧资源              ]  │
│                                     │
│  ☑ 启用此资源库                      │
│                                     │
│              [取消]  [确定]          │
└─────────────────────────────────────┘
```

### 目录选择器（点击"浏览..."后弹出）

```
┌─────────────────────────────────────┐
│  选择目录                     [关闭] │
├─────────────────────────────────────┤
│  当前路径：/media                    │
├─────────────────────────────────────┤
│  📁 movies                          │
│  📁 music                           │
│  📁 japanese-drama                  │
│  📁 ebooks                          │
│  📁 comics                          │
├─────────────────────────────────────┤
│  [↑ 返回上级]            [选择此目录] │
└─────────────────────────────────────┘
```

**交互逻辑**：
1. 点击"浏览..." → 弹出目录选择器，调用 `GET /api/v1/media-libraries/browse`
2. 点击某个文件夹 → 调用 `GET /api/v1/media-libraries/browse?path=/media/movies`，进入子目录
3. 点击"返回上级" → 回到上一层
4. 点击"选择此目录" → 将当前路径填入表单，关闭选择器

### 类型选择说明（可在 UI 中展示）

| 类型 | 适用场景 | 刮削行为 |
|---|---|---|
| **MOVIE** | 专门存放电影的目录 | 仅搜索 TMDB 电影，避免误匹配电视剧 |
| **TV** | 专门存放电视剧的目录 | 仅搜索 TMDB 电视剧，避免误匹配电影 |
| **MIXED** | 电影和电视剧混合存放 | 同时搜索两者，按评分排序选择最佳匹配 |

### 扫描状态展示

扫描时可展示每个资源库的进度：

```
扫描中...
├── 电影库 (25/30) ✅
├── 日剧库 (12/12) ✅
└── 综合库 (8/15)  ⏳ 进行中...
```

---

## 错误响应

```json
{
  "code": 404,
  "message": "Resource not found: MediaLibrary with id=999",
  "data": null
}
```

```json
{
  "code": 400,
  "message": "Not a directory: /invalid/path",
  "data": null
}
```

---

## 向后兼容

- 首次启动时，系统会自动读取 `application.yml` 中的 `hub.video.root-paths` 配置，创建一条默认的 `MIXED` 类型资源库
- 如果数据库中已有资源库数据，则不再迁移
- 旧的 `hub.video.root-paths` 配置仍然作为 fallback 生效
