# FryfrogHub 日志规范

> 适用后端全部模块（common / video / music / app 及后续新模块）。
> 新增功能代码的日志**必须**遵守本规范；Code Review 时按此检查。

## 1. 基础设施

| 组件 | 说明 |
|---|---|
| `logback-spring.xml` | 控制台 + 异步滚动文件（`logs/fryfrog-hub.log`，按天+50MB 切割，保留 14 天/1GB，gzip 归档） |
| `TraceIdFilter` | 每请求生成 8 位 traceId 写入 MDC；响应头 `X-Trace-Id` 返回；输出统一访问日志 |
| MDC 键 | `traceId`（请求追踪）、`userId`（AuthInterceptor 认证通过后写入） |
| 级别覆盖 | 环境变量 `LOGGING_LEVEL_ROOT` / `LOGGING_LEVEL_APP`（默认 INFO） |

日志行格式：
```
2026-08-21 14:18:04.812 INFO  [a1b2c3d4] [scan-thread-1] c.f.h.m.service.MusicScanService - [MusicScan] Start scanning: ... (libraryId=4)
```

## 2. 消息格式规范（强制）

```
[模块Tag] 动作描述: key1={}, key2={}
```

- **模块 Tag**：方括号大写开头，一个服务类固定一个主 Tag（见 §5 注册表）
- **键值对**：可变参数用 `{}` 占位符，禁止字符串拼接
- **语言**：面向运维的消息用英文（便于 grep），业务语义词可中文（如歌名/路径原样输出）

```java
// ✅ 正确
log.info("[MusicScan] Done: {} songs in {}ms (dir={})", saved, cost, dir);
log.warn("[Auth] Login failed: username={}, ip={}", username, ip);

// ❌ 禁止
log.info("扫描完成，共" + saved + "首");          // 拼接
log.info("done");                                // 无 Tag 无上下文
log.debug("user " + username + " did " + action); // 拼接 + 敏感信息无脱敏意识
```

## 3. 级别使用准则

| 级别 | 场景 | 示例 |
|---|---|---|
| `ERROR` | 需要人介入的故障：未预期异常、外部系统不可用且影响功能、5xx | `Unhandled exception`（带堆栈）、访问日志 status≥500 |
| `WARN` | 可自动恢复但需关注：登录失败/锁定、慢请求、重试后成功、数据跳过 | `[MusicScan] Failed to process X` |
| `INFO` | 关键业务节点：启动/完成、扫描开始结束、登录成功、任务调度、访问日志 | `[MusicScan] Start scanning` |
| `DEBUG` | 诊断细节：仅开发排查时开启，生产默认不输出 | `Failed to probe audio` |

规则：
- **循环内禁止 INFO**——批量处理只记开始/结束 + 数量，单条失败用 WARN。
- **异常必须传对象**作为最后一个参数（`log.warn("...: {}", id, e)`），除非消息已含 `e.getMessage()` 且堆栈无价值。
- 客户端主动断连（Abort/AsyncRequestNotUsable）一律 DEBUG，不打 ERROR。

## 4. 安全红线

- 禁止记录：密码、token 全文、Cookie、第三方 API Key。
- token 只允许前 8 位：`token.substring(0, 8) + "…"`。
- 用户 IP 允许记录（安全审计需要）；用户 ID 用 `userId` 不用用户名做主键关联。

## 5. 模块 Tag 注册表（新增模块先来此登记）

| Tag | 类 | 职责 |
|---|---|---|
| `[Auth]` | AuthManager | 登录成功/失败/锁定 |
| `[Access]`* | TraceIdFilter | 访问日志（无 Tag，格式固定，见下） |
| `[MusicScan]` | MusicScanService | 音乐库扫描 |
| `[TagReader]` | MusicTagReaderService | 音频标签直读/乱码修复 |
| `[MusicCover]` | MusicScanService | 封面提取 |
| `[MusicLyrics]` | MusicScanService | 歌词提取 |
| `[Probe]` | TranscodingService | ffprobe 探测 |
| `[Scrape]` | VideoScrapeService | TMDB 刮削 |
| `[Bind]` / `[Unbind]` | VideoScrapeService | 剧集绑定/解绑 |
| `[VideoScan]` | VideoScanService | 视频库扫描 |
| `[Watcher]` | AbstractFileWatcherService | 文件变更监听 |
| `[Scheduler]` | *Scheduler | 周期任务 |

*访问日志由 TraceIdFilter 输出，格式：`GET /api/v1/x -> 200 12ms user=1 ip`，不套模块 Tag。

## 6. 新模块接入清单

1. 类上加 `@Slf4j`（禁止手写 `LoggerFactory.getLogger`）
2. 从 §5 选/登记模块 Tag，所有语句带 Tag
3. 长任务（扫描/刮削/迁移）：开始 INFO → 单条失败 WARN → 结束 INFO（数量+耗时）
4. 对外交互（HTTP/进程调用）：失败 WARN 带 `e.getMessage()`；超时/中断 ERROR
5. 需要 traceId 的手动子线程：提交前捕获 `MDC.getCopyOfContextMap()`，run 内 `MDC.setContextMap(...)` + finally clear
