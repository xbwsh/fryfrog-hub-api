package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.AbstractFileWatcherService;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoActorRepository;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 视频模块文件监听服务。
 * <p>
 * 基于 {@link AbstractFileWatcherService}，监听视频目录的新文件创建和删除。
 * 新文件自动触发扫描→刮削→整理→资产生成完整流水线。
 * 文件删除时自动清理数据库记录和相关资产。
 */
@Service
@Slf4j
public class VideoFileWatcherService extends AbstractFileWatcherService {

    private final VideoPipelineService pipelineService;
    private final MediaLibraryService mediaLibraryService;
    private final VideoRepository videoRepository;
    private final VideoActorRepository actorRepository;
    private final NfoService nfoService;
    private final SeriesService seriesService;

    @Value("${video.root-paths:}")
    private String rootPathsConfig;

    public VideoFileWatcherService(VideoPipelineService pipelineService,
                                   MediaLibraryService mediaLibraryService,
                                   VideoRepository videoRepository,
                                   VideoActorRepository actorRepository,
                                   NfoService nfoService,
                                   SeriesService seriesService) {
        this.pipelineService = pipelineService;
        this.mediaLibraryService = mediaLibraryService;
        this.videoRepository = videoRepository;
        this.actorRepository = actorRepository;
        this.nfoService = nfoService;
        this.seriesService = seriesService;
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
        // 与扫描器共用同一份格式列表，避免两处维护导致不一致
        return VideoScanService.SUPPORTED_FORMATS;
    }

    @Override
    protected void processDirectory(Path directory) {
        String dir = directory.toString();
        log.info("[VideoWatcher] Processing directory: {}", dir);

        Long libraryId = null;
        var lib = mediaLibraryService.findByPath(dir);
        if (lib != null) {
            libraryId = lib.getId();
        }

        pipelineService.runFullPipeline(dir, libraryId);
    }

    @Override
    protected void onFileDeleted(Path filePath) {
        String path = filePath.toString();
        Optional<Video> opt = videoRepository.findByFilePath(path);
        if (opt.isEmpty()) {
            log.debug("[VideoWatcher] Deleted file not in database (likely a rename), skipping: {}", filePath);
            return;
        }

        Video video = opt.get();
        log.info("[VideoWatcher] File deleted, removing record: {} ({})", video.getTitle(), path);

        // 删除关联演员
        actorRepository.deleteAll(actorRepository.findByVideo_Id(video.getId()));

        // 删除磁盘上的 NFO 和封面文件
        try { java.nio.file.Files.deleteIfExists(nfoService.getNfoPath(video)); } catch (Exception ignored) {}
        try { java.nio.file.Files.deleteIfExists(nfoService.getPosterPath(video)); } catch (Exception ignored) {}
        try { java.nio.file.Files.deleteIfExists(nfoService.getFanartPath(video)); } catch (Exception ignored) {}

        // 删除同名的外挂字幕文件
        Path dir = Paths.get(video.getFilePath()).getParent();
        if (dir != null) {
            String baseName = nfoService.getBaseName(video.getFileName());
            java.util.Set<String> subExts = java.util.Set.of(".srt", ".ass", ".ssa", ".vtt", ".sub", ".sup", ".idx");
            try (var files = java.nio.file.Files.list(dir)) {
                files.filter(java.nio.file.Files::isRegularFile)
                        .filter(f -> {
                            String name = f.getFileName().toString();
                            return name.startsWith(baseName) && subExts.stream().anyMatch(name.toLowerCase()::endsWith);
                        })
                        .forEach(f -> {
                            try { java.nio.file.Files.deleteIfExists(f); } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
        }

        // 从系列中移除
        if (video.getSeries() != null) {
            seriesService.removeVideoFromSeries(video);
        }

        // 删除视频记录
        videoRepository.delete(video);

        log.info("[VideoWatcher] Removed record and assets for deleted file: {}", video.getTitle());
    }
}
