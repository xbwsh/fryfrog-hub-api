# 系统设置 API 文档

Base URL: `http://localhost:20058/api/v1/settings`

---

## 数据模型

### SystemSetting 系统设置

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| id | Long | 唯一ID | 1 |
| key | String | 设置键名 | tmdb.api-key |
| value | String | 设置值 | abc123 |
| description | String | 描述说明 | TMDB API密钥 |
| createdAt | DateTime | 创建时间 | 2024-01-01T00:00:00 |
| updatedAt | DateTime | 更新时间 | 2024-01-01T00:00:00 |

### SettingUpdateRequest 更新请求

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| value | String | 是 | 新的设置值 |

---

## 接口列表

| 接口 | 方法 | 说明 |
|------|------|------|
| `/` | GET | 获取所有设置 |
| `/{key}` | GET | 获取单个设置 |
| `/{key}` | PUT | 更新设置 |
| `/tmdb/status` | GET | 检查TMDB配置状态 |
| `/performance` | GET | 获取性能相关设置 |

---

## 接口详情

### GET `/` 获取所有设置

返回所有系统设置列表。

```bash
curl http://localhost:20058/api/v1/settings
```

**Response 示例**:
```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "key": "tmdb.api-key",
      "value": "your_api_key",
      "description": "TMDB API密钥"
    },
    {
      "id": 2,
      "key": "tmdb.language",
      "value": "zh-CN",
      "description": "TMDB语言设置"
    }
  ]
}
```

---

### GET `/{key}` 获取单个设置

```bash
curl http://localhost:20058/api/v1/settings/tmdb.api-key
```

**参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| key | String | 是 | 设置键名 |

**Response 示例**:
```json
{
  "code": 200,
  "data": {
    "id": 1,
    "key": "tmdb.api-key",
    "value": "your_api_key",
    "description": "TMDB API密钥"
  }
}
```

---

### PUT `/{key}` 更新设置

```bash
curl -X PUT http://localhost:20058/api/v1/settings/tmdb.api-key \
  -H "Content-Type: application/json" \
  -d '{"value": "new_api_key"}'
```

**参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| key | String | 是 | 设置键名（路径参数） |

**Request Body**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| value | String | 是 | 新的设置值 |

**Response 示例**:
```json
{
  "code": 200,
  "message": "设置已更新",
  "data": {
    "id": 1,
    "key": "tmdb.api-key",
    "value": "new_api_key",
    "description": null
  }
}
```

---

### GET `/tmdb/status` 检查TMDB配置状态

返回TMDB相关配置的当前状态。

```bash
curl http://localhost:20058/api/v1/settings/tmdb/status
```

**Response 示例**:
```json
{
  "code": 200,
  "data": {
    "configured": true,
    "language": "zh-CN",
    "image-size": "original",
    "auto-scrape": false,
    "include-adult": true
  }
}
```

**字段说明**:
| 字段 | 类型 | 说明 |
|------|------|------|
| configured | Boolean | 是否已配置API密钥 |
| language | String | 语言设置 |
| image-size | String | 图片尺寸 |
| auto-scrape | Boolean | 是否自动刮削 |
| include-adult | Boolean | 是否包含成人内容 |

---

### GET `/performance` 获取性能相关设置

```bash
curl http://localhost:20058/api/v1/settings/performance
```

**Response 示例**:
```json
{
  "code": 200,
  "data": {
    "watcher.periodic-scan": true,
    "watcher.periodic-scan-interval": 300
  }
}
```

**字段说明**:
| 字段 | 类型 | 说明 |
|------|------|------|
| watcher.periodic-scan | Boolean | 是否启用定期扫描 |
| watcher.periodic-scan-interval | Integer | 扫描间隔（秒） |

---

## 设置项说明

### TMDB 视频刮削

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| tmdb.api-key | String | 空 | TMDB API密钥 |
| tmdb.language | String | zh-CN | 语言（zh-CN/en-US等） |
| tmdb.image-size | String | original | 图片尺寸 |
| tmdb.auto-scrape | Boolean | false | 自动刮削新视频 |
| tmdb.min-score | Double | 0.0 | 最低评分过滤 |
| tmdb.include-adult | Boolean | true | 包含成人内容 |

### 漫画刮削

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| comic.auto-scrape | Boolean | false | 自动刮削新漫画 |
| comic.min-score | Double | 0.0 | 最低评分过滤 |

### 音乐设置

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| music.auto-scrape | Boolean | true | 自动刮削元数据 |
| music.auto-writeback | Boolean | true | 自动回写元数据到文件 |
| music.use-folder-structure | Boolean | true | 使用文件夹结构 |
| music.default-artist | String | 空 | 默认艺术家名称 |
| music.scrape.enabled | Boolean | true | 启用刮削 |
| music.scrape.lyrics-fallback | Boolean | true | 歌词回退 |
| music.scrape.cover-fallback | Boolean | true | 封面回退 |

### 文件监控

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| watcher.periodic-scan | Boolean | true | 启用定期扫描 |
| watcher.periodic-scan-interval | int | 300 | 扫描间隔（秒） |

---

## 前端对接示例

### 加载所有设置

```javascript
const response = await fetch('/api/v1/settings');
const { data: settings } = await response.json();

// 按类别分组显示
const tmdbSettings = settings.filter(s => s.key.startsWith('tmdb.'));
const musicSettings = settings.filter(s => s.key.startsWith('music.'));
```

### 更新设置

```javascript
// 更新TMDB API密钥
await fetch('/api/v1/settings/tmdb.api-key', {
  method: 'PUT',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({value: 'new_api_key'})
});
```

### 检查TMDB状态

```javascript
const { data: status } = await fetch('/api/v1/settings/tmdb/status').then(r => r.json());

if (!status.configured) {
  // 提示用户配置TMDB API密钥
  showNotification('请先配置TMDB API密钥');
}
```

### 设置面板布局建议

```vue
<template>
  <div class="settings-panel">
    <!-- TMDB 设置 -->
    <section>
      <h2>视频刮削</h2>
      <setting-item key="tmdb.api-key" label="API密钥" type="password" />
      <setting-item key="tmdb.language" label="语言" type="select" :options="['zh-CN', 'en-US']" />
      <setting-item key="tmdb.auto-scrape" label="自动刮削" type="switch" />
    </section>

    <!-- 音乐设置 -->
    <section>
      <h2>音乐设置</h2>
      <setting-item key="music.auto-scrape" label="自动刮削" type="switch" />
      <setting-item key="music.use-folder-structure" label="使用文件夹结构" type="switch" />
    </section>
  </div>
</template>
```

---

## 环境变量配置

以下设置通过环境变量配置（修改后需重启）：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| PROXY_HOST | 空 | HTTP 代理主机 |
| PROXY_PORT | 0 | HTTP 代理端口 |

---

## 错误响应

```json
{
  "code": 404,
  "message": "Setting not found: tmdb.api-key",
  "data": null
}
```
