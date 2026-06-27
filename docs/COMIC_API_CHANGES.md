# 漫画模块 API 变更记录

## 文件夹结构

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

## 新增接口

### 获取角色图片
- **GET** `/api/v1/comic/character/{id}/image`
- 优先返回本地文件，无则302重定向到远程URL

## 模型变更

### Comic
| 字段 | 类型 | 说明 |
|------|------|------|
| seriesSummary | TEXT | 系列简介，从Bangumi主题详情提取 |
| serializationStart | String | 连载开始日期，从infobox提取 |
| releaseDate | String | 单行本发售日期（语义明确） |

### ComicCharacter
| 字段 | 类型 | 说明 |
|------|------|------|
| imagePath | String | 角色图片本地存储路径 |

### ComicSeries DTO
| 字段 | 类型 | 说明 |
|------|------|------|
| seriesSummary | String | 系列简介，外层展示 |
| serializationStart | String | 连载开始日期，外层展示 |

## 行为变更

### 新增漫画自动刮削
文件监控检测到新漫画文件后自动触发刮削（已绑定的跳过）。

### 封面存储位置
封面存储在漫画文件同目录，命名 `{文件名}_cover.jpg`。

### 角色数据刮削
绑定Bangumi/AniList时自动下载角色图片到 `characters/` 目录。

### Bangumi角色中文名
1. API添加 `lang=zh` 参数
2. `name_cn`为空时调用角色详情API从infobox提取"简体中文名"
3. `InfoboxEntry.value`改为Object类型兼容数组格式

### Bangumi搜索排序
按投票数降序排列，系列条目通常投票多会排在前面。

### 系列条目角色查找
绑定单行本时，若当前主题无角色数据，自动查找关联的系列条目获取角色。

### 每卷独立评分
从Bangumi获取每卷详情时提取该卷评分，不再用系列评分覆盖所有单行本。

### 系列简介
绑定时提取主题详情的summary作为seriesSummary，同步到同系列所有卷。

## 字段语义

| 字段 | 来源 | 含义 |
|------|------|------|
| summary | 每卷详情 | 单行本自己的简介 |
| seriesSummary | 主题详情 | 系列总览简介 |
| rating | 每卷详情 | 单行本独立评分 |
| releaseDate | infobox"发售日" | 该卷发售时间 |
| serializationStart | infobox"连载开始" | 系列连载起始日期 |

## 修改文件

| 文件 | 改动要点 |
|------|----------|
| Comic.java | 新增seriesSummary、serializationStart |
| ComicCharacter.java | 新增imagePath |
| ComicSeries.java | 新增seriesSummary、serializationStart |
| BangumiService.java | 新增getCharacterDetail、CharacterDetail、lang=zh、value改为Object |
| MangaScrapeService.java | 角色图片下载、系列封面、每卷评分、系列简介、连载开始提取 |
| ComicWatcherService.java | 新增自动刮削触发 |
| ComicController.java | 新增角色图片接口 |
| ComicMetadataService.java | 系列列表填充seriesSummary和serializationStart |
