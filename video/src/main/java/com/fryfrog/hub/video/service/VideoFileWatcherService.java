package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.AbstractFileWatcherService;
import com.fryfrog.hub.common.service.MediaLibraryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 视频模块文件监听服务。
 * <p>
 * 基于 {@link AbstractFileWatcherService}，监听视频目录的新文件，
 * 自动触发扫描→刮削→整理→资产生成完整流水线。
 */
@Service
@Slf4j
public class VideoFileWatcherService extends AbstractFileWatcherService {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v"
    );

    private final VideoPipelineService pipelineService;
    private final MediaLibraryService mediaLibraryService;

    @Value("${video.root-paths:}")
    private String rootPathsConfig;

    public VideoFileWatcherService(VideoPipelineService pipelineService,
                                   MediaLibraryService mediaLibraryService) {
        this.pipelineService = pipelineService;
        this.mediaLibraryService = mediaLibraryService;
    }

    @Override
    protected List<String> getRootPaths() {
        List<String> dbPaths = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "VIDEO".equalsIgnoreCase(lib.getType()))
                .map(MediaLibrary::getPath)
                .toList();
        if (!dbPaths.isEmpty()) return dbPaths;
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    protected Set<String> getWatchedExtensions() {
        return VIDEO_EXTENSIONS;
    }

    @Override
    protected void processDirectory(Path directory) {
        String dir = directory.toString();
        log.info("[VideoWatcher] Processing directory: {}", dir);

        // 查找对应的 libraryId
        Long libraryId = null;
        var lib = mediaLibraryService.findByPath(dir);
        if (lib != null) {
            libraryId = lib.getId();
        }

        pipelineService.runFullPipeline(dir, libraryId);
    }
}
