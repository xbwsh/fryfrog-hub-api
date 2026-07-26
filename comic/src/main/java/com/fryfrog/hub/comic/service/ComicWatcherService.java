package com.fryfrog.hub.comic.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 漫画定时扫描服务（已弃用）。
 * <p>
 * 由 {@link com.fryfrog.hub.comic.service.ComicFileWatcherService} 替代。
 */
@Service
@Slf4j
public class ComicWatcherService {

    @PostConstruct
    public void init() {
        log.info("[ComicWatcher] Periodic scan disabled - using ComicFileWatcherService (inotify) instead");
    }
}
