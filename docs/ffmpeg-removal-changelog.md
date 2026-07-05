# FFmpeg 移除 - 前端对接文档

## 变更概览

后端已完全移除 FFmpeg 依赖，视频技术信息分析和字幕转换功能不再可用。前端需自行处理视频解码和字幕渲染。

---

## 一、移除的接口（3 个）

| 接口 | 方法 | 说明 |
|------|------|------|
| `GET /api/v1/video/{id}/media-info` | GET | ~~使用 ffprobe 分析视频编码、分辨率、帧率等技术信息~~ 已删除 |
| `GET /api/v1/video/{id}/subtitles` | GET | ~~列出视频中所有内嵌字幕轨道~~ 已删除 |
| `GET /api/v1/video/{id}/subtitle/vtt` | GET | ~~将内嵌/外挂字幕转换为 WebVTT 返回~~ 已删除 |

## 二、保留的接口（1 个）

| 接口 | 方法 | 说明 |
|------|------|------|
| `GET /api/v1/video/{id}/subtitle/external` | GET | 列出视频同目录下的外挂字幕文件 |

**外挂字幕接口返回格式不变：**
```json
[
  {
    "fileName": "流浪地球2.zh.srt",
    "ext": ".srt",
    "path": "/media/movies/流浪地球2.zh.srt",
    "language": "zh"
  }
]
```

> 注意：后端只返回外挂字幕文件列表，不再负责格式转换。前端需自行将 SRT/ASS 等格式转换为 WebVTT 后传给 `<track>` 元素。

## 三、VideoDTO 字段变化

### 移除的字段

VideoDTO 中由 ffprobe 填充的技术参数字段现在全部为 `null`（不再自动分析填充）：

| 字段 | 说明 | 现状 |
|------|------|------|
| `videoCodec` | 视频编码 | 不再自动填充，保留字段供 NFO 手动配置 |
| `videoProfile` | 编码等级 | 同上 |
| `pixFmt` | 像素格式 | 同上 |
| `displayAspectRatio` | 显示比例 | 同上 |
| `resolution` | 分辨率 | 同上 |
| `frameRate` | 帧率 | 同上 |
| `bitrateKbps` | 比特率 | 同上 |
| `durationMinutes` | 时长（分钟） | 同上 |
| `durationSeconds` | 时长（秒） | 同上 |
| `audioCodec` | 音频编码 | 同上 |
| `audioChannelLayout` | 声道布局 | 同上 |
| `audioIncompatible` | 音频是否不兼容 | 同上（基于 audioCodec 判断） |

> **注意：** 如果视频有 NFO 元数据文件，这些字段会从 NFO 中读取填充。只有通过文件扫描新建的视频记录，这些字段才会为 null。

### 保留的字段

以下字段不受影响：
- `id`, `title`, `fileName`, `fileSize`, `format`（文件扩展名）
- `coverUrl`, `fanartUrl`, `streamUrl`（图片和流媒体 API 路径）
- `tmdbId`, `mediaType`, `rating`, `overview` 等 TMDB 元数据
- `favorite`, `isSeries`, `seriesId`, `seriesTitle` 等业务字段

## 四、前端适配建议

### 视频技术信息

旧方式：调用 `GET /api/v1/video/{id}/media-info` 获取编码信息。

新方式：使用前端 MediaSource API 或 `HTMLVideoElement` 获取视频信息：

```js
const video = document.querySelector('video');
video.addEventListener('loadedmetadata', () => {
  console.log('duration:', video.duration);
  console.log('videoWidth:', video.videoWidth);
  console.log('videoHeight:', video.videoHeight);
});
```

### 字幕处理

旧方式：调用 `GET /api/v1/video/{id}/subtitle/vtt` 获取 WebVTT。

新方式：
1. 调用 `GET /api/v1/video/{id}/subtitle/external` 获取外挂字幕文件列表
2. 前端自行读取并转换字幕格式（可用 [libass](https://github.com/nickoala/libass.js) 或 [jassub](https://github.com/nickoala/jassub) 渲染 ASS）
3. 内嵌字幕：使用 FFmpeg 在前端解码（如 [ffmpeg.wasm](https://github.com/nickoala/ffmpeg.wasm)）或直接由浏览器播放器处理

```js
// 外挂字幕示例
const response = await fetch(`/api/v1/video/${id}/subtitle/external`);
const subtitles = await response.json();
// subtitles: [{ fileName: "xxx.zh.srt", ext: ".srt", ... }]

// 将字幕文件加载为 <track>
const track = video.addTextTrack('subtitles', 'Chinese', 'zh');
// 需自行解析 SRT/ASS 格式并添加 cue
```

### 视频播放

`GET /api/v1/video/{id}/stream` 接口不受影响，前端仍可通过 `<video src="...">` 直接播放。

## 五、配置项移除

以下配置项已不再使用，可从 `application.yml` 中删除：

```yaml
# 已移除
hub:
  video:
    ffprobe-path: ffprobe
    ffmpeg-path: ffmpeg
```

## 六、后端改动文件清单

| 文件 | 操作 |
|------|------|
| `video/util/FfmpegBinaryExtractor.java` | 整个删除 |
| `video/service/MediaInfoService.java` | 移除所有 ffprobe/ffmpeg 方法，仅保留 `getExternalSubtitles()` |
| `video/controller/VideoController.java` | 移除 3 个端点（media-info、subtitles、subtitle/vtt） |
| `video/service/VideoService.java` | 移除 `updateVideoMediaInfo()` 调用 |
| `video/pom.xml` | 移除 `org.bytedeco:ffmpeg-platform` 依赖 |
| `app/config/AuthInterceptor.java` | 移除 `/subtitle/vtt` 免认证规则 |
