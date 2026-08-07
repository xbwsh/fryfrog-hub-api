# 视频字幕烧录 — 前端对接文档

> 更新日期：2026-08-07
> 适用后端版本：fryfrog-hub-api（本次字幕烧录功能修改后）

---

## 一、背景

浏览器无法渲染 **PGS（.sup）** 与 **VobSub（.sub/.idx）** 图形字幕，ASS/SSA 在部分浏览器（Safari/iOS）上不生效或丢失特效。

因此后端在**转码流播放**接口中新增了 `subtitle` 参数：当传入浏览器不支持的字幕文件时，FFmpeg 会将该字幕**烧录（burn-in）到视频画面**中返回，确保任何播放器都能看到字幕。

文本字幕（SRT/VTT）浏览器原生支持，**不需要烧录**，继续走原有字幕加载方式。

---

## 二、相关接口

### 1. 获取字幕列表（原有，未变更）

```
GET /api/v1/video/{id}/subtitles
```

返回：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "filename": "Movie.zh.ass",
      "language": "zh",
      "url": "/api/v1/video/123/subtitles/Movie.zh.ass"
    },
    {
      "filename": "Movie.en.srt",
      "language": "en",
      "url": "/api/v1/video/123/subtitles/Movie.en.srt"
    }
  ]
}
```

| 字段 | 说明 |
|------|------|
| `filename` | 字幕文件名（转码烧录时作为 `subtitle` 参数传入） |
| `language` | 语言代码，无法识别时为 `und` |
| `url` | 原始字幕文件下载地址（浏览器原生支持的格式直接使用） |

### 2. 获取字幕原始文件（原有，未变更）

```
GET /api/v1/video/{id}/subtitles/{filename}
```

返回字幕文件原始内容。用于 SRT/VTT 等浏览器原生支持的格式。

### 3. 视频转码流播放（本次修改：新增 `subtitle` 参数）

```
GET /api/v1/video/{id}/stream/transcode?quality=1080p&maxBitrate=8M&subtitle=Movie.zh.ass
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | path | 是 | 视频 ID |
| `quality` | query | 否 | 转码质量：`1080p` / `720p` / `480p` / `360p`，默认 `1080p` |
| `maxBitrate` | query | 否 | 最大码率，如 `8M`，默认按质量自动选择 |
| `subtitle` | query | 否 | **需要烧录的字幕文件名（URL 编码）**，不传则行为与之前完全一致 |

行为说明：

- **不传 `subtitle`**：与旧版完全一致，仅转码不烧录。
- **传 `subtitle`**：FFmpeg 在转码时将该字幕渲染进视频画面，返回 `video/mp4` 分片流。
- 字幕文件必须存在于视频同目录下，否则返回 `400 Bad Request`。
- 文件名作为参数时**必须 URL 编码**（中文、空格、特殊字符）。

---

## 三、字幕格式兼容性对照

| 格式 | 浏览器原生支持 | 推荐处理方式 |
|------|----------------|--------------|
| `.srt` | ✅ 支持 | 使用字幕列表返回的 `url`，走原生字幕轨道 |
| `.vtt` | ✅ 支持 | 同上 |
| `.ass` / `.ssa` | ⚠️ 部分（特效丢失） | **烧录**：转码流 + `subtitle` 参数 |
| `.sup`（PGS） | ❌ 不支持 | **烧录**：转码流 + `subtitle` 参数 |
| `.sub` / `.idx`（VobSub） | ❌ 不支持 | **烧录**：转码流 + `subtitle` 参数 |

> 说明：`.ass/.ssa` 若只是普通字幕（无特效），也可选择烧录以获得跨端一致的显示效果；代价是无法再通过播放器 UI 关闭/切换样式。

---

## 四、前端对接流程

### 步骤 1：加载字幕列表

```js
const res = await fetch(`/api/v1/video/${videoId}/subtitles`);
const { data: subtitles } = await res.json();
// 渲染字幕选择菜单，记录每个字幕的 filename
```

### 步骤 2：选择字幕时判断格式

```js
// 浏览器原生支持的字幕格式
const NATIVE_SUBTITLE_EXTS = ['.srt', '.vtt'];

function needBurnIn(filename) {
  const lower = filename.toLowerCase();
  return !NATIVE_SUBTITLE_EXTS.some(ext => lower.endsWith(ext));
}
```

### 步骤 3：设置播放源

```js
// 情况 A：文本字幕（SRT/VTT）— 原生加载，不烧录
// 视频源保持普通流或转码流（不带 subtitle 参数）
video.src = `/api/v1/video/${videoId}/stream/transcode?quality=${quality}`;
// 字幕轨道
const track = document.createElement('track');
track.kind = 'subtitles';
track.src = subtitle.url; // 字幕列表返回的 url
track.label = subtitle.language;
video.appendChild(track);

// 情况 B：不支持的字幕（ASS/PGS/VobSub）— 烧录进视频流
const subtitleParam = encodeURIComponent(subtitle.filename);
video.src = `/api/v1/video/${videoId}/stream/transcode?quality=${quality}&subtitle=${subtitleParam}`;
// 此时不要添加 <track>，字幕已在画面中
```

### 步骤 4：切换/关闭字幕

- **切换字幕**：重新设置 `video.src`，带上新字幕文件名即可（浏览器会重新请求并播放）。
- **关闭字幕**：重新设置 `video.src` 为不带 `subtitle` 参数的转码流地址。
- **切换画质**：保留当前 `subtitle` 参数，避免画质切换后字幕丢失。

```js
function playWithSubtitle(videoId, quality, filename) {
  let url = `/api/v1/video/${videoId}/stream/transcode?quality=${quality}`;
  if (filename) {
    url += `&subtitle=${encodeURIComponent(filename)}`;
  }
  video.src = url;
  video.play();
}
```

---

## 五、注意事项

1. **烧录是硬编码**：字幕渲染进画面后，无法通过播放器 UI 开关字幕或修改样式；关闭字幕必须重新请求不带 `subtitle` 的流。
2. **需要 FFmpeg**：转码接口仅在后端 FFmpeg 可用时有效。若转码接口返回 `503`，说明 FFmpeg 不可用，此时图形字幕（PGS/VobSub）无法显示，建议提示用户。
3. **资源开销**：烧录 = 重新编码视频，CPU/GPU 占用高、启动有延迟。仅对不支持的字幕格式启用，SRT/VTT 永远走原生轨道。
4. **拖动进度**：转码流为实时生成的分片 MP4，播放器只能拖动到已转码部分，无法直接跳到片尾——这是转码播放的固有行为，与字幕无关。
5. **文件名编码**：`subtitle` 参数使用 `encodeURIComponent` 编码；路径安全由后端校验，字幕必须与视频同目录，否则返回 `400`。
6. **建议 UI 提示**：在选择 ASS/图形字幕时可标注"已内嵌"或"烧录"字样，避免用户误以为可以像文本字幕一样关闭。
