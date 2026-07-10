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
      "value": "***已配置***",
      "description": null
    },
    {
      "id": 2,
      "key": "tmdb.auto-scrape",
      "value": "true",
      "description": null
    },
    {
      "id": 3,
      "key": "scrape.auto-scrape",
      "value": "true",
      "description": null
    },
    {
      "id": 4,
      "key": "watcher.periodic-scan",
      "value": "true",
      "description": null
    },
    {
      "id": 5,
      "key": "watcher.periodic-scan-interval",
      "value": "60",
      "description": null
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
    "configured": true
  }
}
```

**字段说明**:
| 字段 | 类型 | 说明 |
|------|------|------|
| configured | Boolean | 是否已配置API密钥 |

---

---

## 设置项说明

### TMDB 视频刮削

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| tmdb.api-key | String | 空 | TMDB API密钥 |
| tmdb.language | String | zh-CN | 语言（zh-CN/en-US等） |
| tmdb.image-size | String | original | 图片尺寸 |
| tmdb.include-adult | Boolean | true | 包含成人内容 |

### 全局开关

| 键名 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| watcher.periodic-scan | Boolean | true | 启用定期扫描（发现新文件） |
| watcher.periodic-scan-interval | int | 30 | 扫描间隔（秒） |
| scrape.auto-scrape | Boolean | true | 启用自动刮削元数据 |

---

## 前端对接示例

### 加载所有设置

```javascript
const response = await fetch('/api/v1/settings');
const { data: settings } = await response.json();

// 按类别分组显示
const tmdbSettings = settings.filter(s => s.key.startsWith('tmdb.'));
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
    </section>

    <!-- 全局开关 -->
    <section>
      <h2>全局开关</h2>
      <setting-item key="watcher.periodic-scan" label="定期扫描" type="switch" />
      <setting-item key="watcher.periodic-scan-interval" label="扫描间隔(秒)" type="number" />
      <setting-item key="scrape.auto-scrape" label="自动刮削" type="switch" />
    </section>
  </div>
</template>
```

---

## 环境变量配置

以下设置通过环境变量配置（修改后需重启）：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| TMDB_API_KEY | 空 | TMDB API密钥 |
| TMDB_LANGUAGE | zh-CN | TMDB语言 |
| TMDB_IMAGE_SIZE | original | TMDB图片尺寸 |
| TMDB_INCLUDE_ADULT | true | 包含成人内容 |

---

## 错误响应

```json
{
  "code": 404,
  "message": "Setting not found: tmdb.api-key",
  "data": null
}
```
