package com.fryfrog.hub.music.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 音乐定时扫描服务（已弃用）。
 * <p>
 * 由 {@link com.fryfrog.hub.music.service.MusicFileWatcherService} 替代。
 */
@Service
@Slf4j
public class MediaWatcherService {

    @PostConstruct
    public void init() {
        log.info("[MusicWatcher] Periodic scan disabled - using MusicFileWatcherService (inotify) instead");
    }
}
