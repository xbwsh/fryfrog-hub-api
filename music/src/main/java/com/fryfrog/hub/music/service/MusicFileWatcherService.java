package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.AbstractFileWatcherService;
import com.fryfrog.hub.common.service.MediaLibraryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 音乐模块文件监听服务。
 * <p>
 * 新音频文件出现后自动触发元数据扫描。
 */
@Service
@Slf4j
public class MusicFileWatcherService extends AbstractFileWatcherService {

    private static final Set<String> MUSIC_EXTENSIONS = Set.of(
            "mp3", "flac", "ogg", "wav", "aac", "m4a"
    );

    private final MusicMetadataService metadataService;
    private final MediaLibraryService mediaLibraryService;

    public MusicFileWatcherService(MusicMetadataService metadataService,
                                   MediaLibraryService mediaLibraryService) {
        this.metadataService = metadataService;
        this.mediaLibraryService = mediaLibraryService;
    }

    @Override
    protected List<String> getRootPaths() {
        List<String> dbPaths = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "MUSIC".equalsIgnoreCase(lib.getType()))
                .map(MediaLibrary::getPath)
                .toList();
        if (!dbPaths.isEmpty()) return dbPaths;
        return metadataService.getRootPaths();
    }

    @Override
    protected Set<String> getWatchedExtensions() {
        return MUSIC_EXTENSIONS;
    }

    @Override
    protected void processDirectory(Path directory) {
        log.info("[MusicWatcher] Scanning directory: {}", directory);
        metadataService.scanDirectory(directory.toString());
    }
}
