package com.fryfrog.hub.video.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 视频定时扫描服务（已弃用）。
 * <p>
 * 由 {@link VideoFileWatcherService} 替代，使用 inotify 事件驱动监听。
 * 保留此 bean 仅为避免外部配置引用报错，不再执行定时轮询。
 */
@Service
@Slf4j
public class VideoWatcherService {

    @PostConstruct
    public void init() {
        log.info("[Watcher] Periodic scan disabled - using VideoFileWatcherService (inotify) instead");
    }
}
