# 电子书模块 API 文档

Base URL: `http://localhost:20058/api/v1/ebook`

---

## 数据模型

### Ebook 电子书对象

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| id | Long | 唯一ID | 1 |
| title | String | 书名 | 三体 |
| author | String | 作者 | 刘慈欣 |
| series | String | 系列名称 | 三体 |
| volume | Integer | 卷号 | 1 |
| publisher | String | 出版社 | 重庆出版社 |
| isbn | String | ISBN | 9787536692930 |
| year | Integer | 出版年份 | 2008 |
| genre | String | 类型 | 科幻 |
| description | String | 简介 | 文化大革命... |
| fileName | String | 文件名 | 三体.epub |
| fileSize | Long | 文件大小（字节） | 1048576 |
| format | String | 格式 | EPUB |
| pageCount | Integer | 总页数/章节数 | 350 |
| language | String | 语言 | zh-CN |
| favorite | Boolean | 是否收藏 | false |
| coverUrl | String | 封面图片URL | /api/v1/ebook/1/cover |
| downloadUrl | String | 下载URL | /api/v1/ebook/1/download |
| readUrl | String | 阅读URL | /api/v1/ebook/1/read |
| createdAt | DateTime | 创建时间 | 2024-01-01T00:00:00 |
| updatedAt | DateTime | 更新时间 | 2024-01-01T00:00:00 |

### EbookReadingProgressDTO 阅读进度

| 字段 | 类型 | 说明 |
|------|------|------|
| ebookId | Long | 电子书ID |
| currentPage | Integer | 当前页码 |
| totalPages | Integer | 总页数 |
| completed | Boolean | 是否已读完（进度>=90%） |
| progressPercent | Double | 阅读进度百分比 |
| updatedAt | DateTime | 最后更新时间 |

### ChapterInfo 章节信息

| 字段 | 类型 | 说明 |
|------|------|------|
| number | Integer | 章节序号 |
| title | String | 章节标题 |

### EbookSeries 系列信息

| 字段 | 类型 | 说明 |
|------|------|------|
| name | String | 系列名称 |
| author | String | 作者 |
| volumeCount | Integer | 卷数 |
| coverArtPath | String | 封面路径 |
| books | List\<Ebook\> | 该系列的电子书列表 |

---

## 接口列表

### 1. 基础查询

#### GET `/` 获取所有电子书

```bash
curl http://localhost:20058/api/v1/ebook
```

**Response**: `ApiResponse<List<Ebook>>`

---

#### GET `/{id}` 获取电子书详情

```bash
curl http://localhost:20058/api/v1/ebook/1
```

**Response**: `ApiResponse<Ebook>`

---

#### GET `/series` 按系列分组获取

```bash
curl http://localhost:20058/api/v1/ebook/series
```

**Response**: `ApiResponse<List<EbookSeries>>`

---

#### GET `/search/title?q=关键词` 按书名搜索

```bash
curl "http://localhost:20058/api/v1/ebook/search/title?q=三体"
```

**Response**: `ApiResponse<List<Ebook>>`

---

#### GET `/search/author?q=关键词` 按作者搜索

```bash
curl "http://localhost:20058/api/v1/ebook/search/author?q=刘慈欣"
```

**Response**: `ApiResponse<List<Ebook>>`

---

### 2. 收藏与推荐

#### GET `/favorites` 获取收藏列表

```bash
curl http://localhost:20058/api/v1/ebook/favorites
```

**Response**: `ApiResponse<List<Ebook>>`

---

#### PUT `/{id}/favorite?status=true` 设置收藏状态

```bash
curl -X PUT "http://localhost:20058/api/v1/ebook/1/favorite?status=true"
```

**参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | boolean | 是 | 收藏状态 |

**Response**: `ApiResponse<Ebook>`

---

#### GET `/recently-added` 最近添加

```bash
curl http://localhost:20058/api/v1/ebook/recently-added
```

**Response**: `ApiResponse<List<Ebook>>`，按添加时间降序

---

#### GET `/recently-read` 最近阅读

```bash
curl http://localhost:20058/api/v1/ebook/recently-read
```

**Response**: `ApiResponse<List<EbookReadingProgressDTO>>`，包含阅读进度

---

#### GET `/stats` 阅读统计

```bash
curl http://localhost:20058/api/v1/ebook/stats
```

**Response 示例**:
```json
{
  "code": 200,
  "data": {
    "totalBooks": 100,
    "totalFavorites": 25,
    "totalReading": 15,
    "totalCompleted": 30
  }
}
```

---

### 3. 阅读功能

#### GET `/{id}/read` 在线阅读

- epub格式：返回HTML内容
- 其他格式（txt等）：返回纯文本

```bash
curl http://localhost:20058/api/v1/ebook/1/read
```

**注意**: HTML内容中引用的图片需要通过 `/image` 接口获取

---

#### GET `/{id}/chapters` 获取章节列表

```bash
curl http://localhost:20058/api/v1/ebook/1/chapters
```

**Response 示例**:
```json
{
  "code": 200,
  "data": [
    {"number": 1, "title": "第一章 疯狂年代"},
    {"number": 2, "title": "第二章 寂静的春天"}
  ]
}
```

---

#### GET `/{id}/chapters/{chapterNum}` 获取章节内容

```bash
curl http://localhost:20058/api/v1/ebook/1/chapters/1
```

**参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| chapterNum | int | 是 | 章节序号，从1开始 |

**Response**: HTML或纯文本内容

---

#### GET `/{id}/image?file=图片路径` 获取epub内嵌图片

```bash
curl "http://localhost:20058/api/v1/ebook/1/image?file=images/cover.jpg"
```

**参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | String | 是 | 图片在epub内的路径 |

**Response**: 图片二进制数据

---

#### GET `/{id}/cover` 获取封面图片

无封面时返回标题占位图。

```bash
curl http://localhost:20058/api/v1/ebook/1/cover
```

**Response**: 图片二进制数据

---

#### GET `/{id}/download` 下载电子书

```bash
curl -O -J "http://localhost:20058/api/v1/ebook/1/download"
```

**Response**: 文件流，带Content-Disposition头

---

### 4. 阅读进度

#### GET `/{id}/progress` 获取阅读进度

```bash
curl http://localhost:20058/api/v1/ebook/1/progress
```

**Response**: `ApiResponse<EbookReadingProgressDTO>`，无进度时返回 `data: null`

---

#### PUT `/{id}/progress` 保存阅读进度

```bash
curl -X PUT http://localhost:20058/api/v1/ebook/1/progress \
  -H "Content-Type: application/json" \
  -d '{"currentPage": 50, "totalPages": 350}'
```

**Request Body**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| currentPage | int | 是 | 当前页码 |
| totalPages | int | 是 | 总页数 |

**Response**: `ApiResponse<EbookReadingProgressDTO>`

---

#### DELETE `/{id}/progress` 删除阅读进度

```bash
curl -X DELETE http://localhost:20058/api/v1/ebook/1/progress
```

**Response**: `ApiResponse<Void>`

---

### 5. 元数据刮削（Bangumi）

#### GET `/bangumi/search?q=关键词` 搜索书籍

```bash
curl "http://localhost:20058/api/v1/ebook/bangumi/search?q=三体"
```

**Response 示例**:
```json
{
  "code": 200,
  "data": [
    {
      "id": 12345,
      "name": "三体",
      "nameOriginal": "三体",
      "summary": "文化大革命如火如荼进行的同时...",
      "date": "2008-01-01",
      "score": 9.0,
      "rank": 1,
      "coverUrl": "https://lain.bgm.tv/pic/cover/...",
      "tags": ["科幻", "小说", "刘慈欣"]
    }
  ]
}
```

---

#### POST `/{id}/bangumi/bind?bangumiId=条目ID` 绑定元数据

从Bangumi获取元数据并绑定到指定电子书。

```bash
curl -X POST "http://localhost:20058/api/v1/ebook/1/bangumi/bind?bangumiId=12345"
```

**参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| bangumiId | int | 是 | Bangumi条目ID |

**自动绑定字段**: 标题、作者、出版社、年份、ISBN、简介、封面

**Response**: `ApiResponse<Ebook>`

---

#### PUT `/{id}/metadata` 手动更新元数据

```bash
curl -X PUT http://localhost:20058/api/v1/ebook/1/metadata \
  -H "Content-Type: application/json" \
  -d '{"title":"三体","author":"刘慈欣","publisher":"重庆出版社","year":2008}'
```

**Request Body**（所有字段可选）:
| 字段 | 类型 | 说明 |
|------|------|------|
| title | String | 书名 |
| author | String | 作者 |
| publisher | String | 出版社 |
| year | int | 出版年份 |
| isbn | String | ISBN |
| genre | String | 类型 |
| description | String | 简介 |

**Response**: `ApiResponse<Ebook>`

---

## 前端对接示例

### 首页数据加载

```javascript
// 并行加载多个列表
const [recentlyRead, recentlyAdded, stats] = await Promise.all([
  fetch('/api/v1/ebook/recently-read').then(r => r.json()),
  fetch('/api/v1/ebook/recently-added').then(r => r.json()),
  fetch('/api/v1/ebook/stats').then(r => r.json())
]);
```

### 阅读器集成

```javascript
// 1. 获取章节列表
const chapters = await fetch(`/api/v1/ebook/${id}/chapters`).then(r => r.json());

// 2. 加载指定章节内容
const content = await fetch(`/api/v1/ebook/${id}/chapters/1`).then(r => r.text());

// 3. 定期保存进度
await fetch(`/api/v1/ebook/${id}/progress`, {
  method: 'PUT',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({currentPage: 50, totalPages: 350})
});
```

### epub内嵌图片处理

```javascript
// 替换 epub HTML 中的图片路径
const html = content.replace(
  /src="([^"]+)"/g,
  (match, path) => `src="http://localhost:20058/api/v1/ebook/${id}/image?file=${encodeURIComponent(path)}"`
);
```

### 元数据刮削流程

```javascript
// 1. 搜索
const results = await fetch(`/api/v1/ebook/bangumi/search?q=${keyword}`).then(r => r.json());

// 2. 选择并绑定
await fetch(`/api/v1/ebook/${ebookId}/bangumi/bind?bangumiId=${results.data[0].id}`, {
  method: 'POST'
});
```

---

## 错误响应格式

```json
{
  "code": 404,
  "message": "Ebook not found with id: 1",
  "data": null
}
```
