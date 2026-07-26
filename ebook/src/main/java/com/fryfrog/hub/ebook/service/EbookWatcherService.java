package com.fryfrog.hub.ebook.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 电子书定时扫描服务（已弃用）。
 * <p>
 * 由 {@link com.fryfrog.hub.ebook.service.EbookFileWatcherService} 替代。
 */
@Service
@Slf4j
public class EbookWatcherService {

    @PostConstruct
    public void init() {
        log.info("[EbookWatcher] Periodic scan disabled - using EbookFileWatcherService (inotify) instead");
    }
}
