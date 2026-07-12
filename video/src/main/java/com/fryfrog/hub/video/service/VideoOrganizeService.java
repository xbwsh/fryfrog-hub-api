package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.util.DatabaseWriteLock;
import com.fryfrog.hub.common.util.TitleCleaner;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 视频文件整理服务：负责文件重命名、移动、目录整理。
 * 所有文件 I/O 操作在写锁外执行，只在 DB 更新时短暂持有写锁。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoOrganizeService {

    private final VideoRepository repository;
    private final NfoService nfoService;

    private static final Pattern SEASON_DIR_PATTERN = Pattern.compile(
            "第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*季"
    );

    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of(
            ".srt", ".ass", ".ssa", ".vtt", ".sub", ".sup", ".idx"
    );

    /**
     * 批量整理视频文件（重命名 + 移动到元数据目录）
     */
    public Map<String, Object> batchOrganize(List<Video> videos) {
        log.debug("[Organize] Starting batch organize for {} videos", videos.size());

        // 按季数+集数排序
        List<Video> sorted = new ArrayList<>(videos);
        sorted.sort((a, b) -> {
            int sa = a.getSeasonNumber() != null ? a.getSeasonNumber() : 1;
            int sb = b.getSeasonNumber() != null ? b.getSeasonNumber() : 1;
            if (sa != sb) return Integer.compare(sa, sb);
            int ea = a.getEpisodeNumber() != null ? a.getEpisodeNumber() : 1;
            int eb = b.getEpisodeNumber() != null ? b.getEpisodeNumber() : 1;
            return Integer.compare(ea, eb);
        });

        int moved = 0;
        int skipped = 0;
        int failed = 0;

        for (Video video : sorted) {
            try {
                // 先重命名文件（如果有 metadata）
                renameVideoFile(video);

                Path oldDir = Paths.get(video.getFilePath()).getParent();
                Path metadataDir = nfoService.getMetadataDir(video);

                if (oldDir.equals(metadataDir)) {
                    skipped++;
                    continue;
                }

                // Phase 1: 文件操作（无锁）
                Files.createDirectories(metadataDir);

                Path videoPath = Paths.get(video.getFilePath());
                Path newVideoPath = metadataDir.resolve(video.getFileName());

                // 移动视频文件
                if (Files.exists(videoPath) && !Files.exists(newVideoPath)) {
                    Files.move(videoPath, newVideoPath);
                    log.debug("[Organize] Moved video: {} -> {}", videoPath, newVideoPath);
                }

                // 移动关联的元数据文件（NFO、poster、fanart）
                String baseName = nfoService.getBaseName(video.getFileName());
                moveAssociatedFile(oldDir, metadataDir, baseName + ".nfo");
                moveAssociatedFile(oldDir, metadataDir, baseName + "-poster.jpg");
                moveAssociatedFile(oldDir, metadataDir, baseName + "-fanart.jpg");

                // 移动外挂字幕文件
                moveAssociatedSubtitles(oldDir, metadataDir, baseName);

                // 移动 actors 目录
                Path oldActorsDir = findOldActorsDir(oldDir);
                Path newActorsDir = metadataDir.resolve("actors");
                if (oldActorsDir != null && !Files.exists(newActorsDir)) {
                    Files.createDirectories(newActorsDir.getParent());
                    Files.move(oldActorsDir, newActorsDir);
                    log.debug("[Organize] Moved actors dir: {} -> {}", oldActorsDir, newActorsDir);
                }

                // Phase 2: DB 更新（写锁内）
                video.setFilePath(newVideoPath.toString());
                DatabaseWriteLock.runInWriteLock(() -> repository.save(video));
                moved++;

            } catch (Exception e) {
                failed++;
                log.error("[Organize] Failed to organize {}: {}", video.getFileName(), e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", videos.size());
        result.put("moved", moved);
        result.put("skipped", skipped);
        result.put("failed", failed);
        log.debug("[Organize] Batch organize complete: {} moved, {} skipped, {} failed", moved, skipped, failed);
        return result;
    }

    /**
     * 重命名视频文件为干净的文件名
     */
    public void renameVideoFile(Video video) {
        try {
            Path videoPath = Paths.get(video.getFilePath());
            if (!Files.exists(videoPath)) {
                return;
            }

            String newFileName = generateCleanFileName(video);
            if (newFileName == null || newFileName.isBlank()) {
                return;
            }

            String oldExtension = TitleCleaner.getFileExtension(video.getFileName());
            String newExtension = TitleCleaner.getFileExtension(newFileName);
            if (newExtension.isBlank()) {
                newFileName = newFileName + "." + oldExtension;
            }

            if (video.getFileName().equals(newFileName)) {
                return;
            }

            Path newPath = videoPath.getParent().resolve(newFileName);

            String oldBaseName = nfoService.getBaseName(video.getFileName());
            Files.move(videoPath, newPath, StandardCopyOption.REPLACE_EXISTING);

            Path parentDir = videoPath.getParent();
            renameAssociatedFile(parentDir, oldBaseName + ".nfo", newFileName);
            renamePosterFanartByPattern(parentDir, oldBaseName, newFileName);
            renameAssociatedSubtitles(parentDir, oldBaseName, newFileName);

            video.setFileName(newFileName);
            video.setFilePath(newPath.toString());
            DatabaseWriteLock.runInWriteLock(() -> repository.save(video));
            log.info("[Organize] Renamed video: {} -> {}", oldBaseName, newFileName);
        } catch (Exception e) {
            log.warn("[Organize] Failed to rename video {}: {}", video.getFileName(), e.getMessage());
        }
    }

    /**
     * 移动视频到元数据目录
     */
    public void moveVideoToMetadataDir(Video video) {
        try {
            Path metadataDir = nfoService.getMetadataDir(video);
            Files.createDirectories(metadataDir);

            Path videoPath = Paths.get(video.getFilePath());
            Path targetPath = metadataDir.resolve(video.getFileName());

            if (videoPath.equals(targetPath)) {
                return;
            }

            if (!Files.exists(videoPath)) {
                log.warn("[Organize] Source video not found: {}", videoPath);
                return;
            }

            Files.move(videoPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("[Organize] Moved video: {} -> {}", videoPath, targetPath);

            // 移动外挂字幕
            String baseName = nfoService.getBaseName(video.getFileName());
            moveAssociatedSubtitles(videoPath.getParent(), targetPath.getParent(), baseName);

            video.setFilePath(targetPath.toString());
            DatabaseWriteLock.runInWriteLock(() -> repository.save(video));
        } catch (IOException e) {
            log.error("[Organize] Failed to move video {} to metadata dir: {}", video.getFileName(), e.getMessage(), e);
        }
    }

    // ==================== 文件名生成 ====================

    /**
     * 生成干净的文件名
     * 电视剧：{标题} - {集数}.{扩展名}
     * 电影：{标题}.{扩展名}
     */
    private String generateCleanFileName(Video video) {
        String title = video.getTitle();
        if (title == null || title.isBlank()) {
            return null;
        }

        String extension = TitleCleaner.getFileExtension(video.getFileName());

        if ("tv".equalsIgnoreCase(video.getMediaType())) {
            int episode = video.getEpisodeNumber() != null ? video.getEpisodeNumber() : 1;
            return String.format("%s - %d.%s", title, episode, extension);
        } else {
            return title + "." + extension;
        }
    }

    // ==================== 关联文件操作 ====================

    private void moveAssociatedFile(Path oldDir, Path newDir, String fileName) {
        try {
            Path oldFile = oldDir.resolve(fileName);
            Path newFile = newDir.resolve(fileName);
            if (Files.exists(oldFile) && !Files.exists(newFile)) {
                Files.move(oldFile, newFile);
                log.debug("[Organize] Moved associated file: {}", fileName);
            }
        } catch (Exception e) {
            log.warn("[Organize] Failed to move associated file {}: {}", fileName, e.getMessage());
        }
    }

    private void moveAssociatedSubtitles(Path oldDir, Path newDir, String baseName) {
        try (var stream = Files.list(oldDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString().toLowerCase();
                        String ext = name.substring(name.lastIndexOf('.'));
                        return SUBTITLE_EXTENSIONS.contains(ext);
                    })
                    .filter(file -> file.getFileName().toString().startsWith(baseName))
                    .forEach(file -> {
                        try {
                            Path target = newDir.resolve(file.getFileName());
                            if (!Files.exists(target)) {
                                Files.move(file, target);
                                log.debug("[Organize] Moved subtitle: {}", file.getFileName());
                            }
                        } catch (Exception e) {
                            log.warn("[Organize] Failed to move subtitle {}: {}", file.getFileName(), e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("[Organize] Failed to list directory for subtitles: {}", e.getMessage());
        }
    }

    private void renameAssociatedFile(Path dir, String oldBaseName, String newFileName) {
        try {
            String ext = TitleCleaner.getFileExtension(oldBaseName);
            String dottedExt = ext.isEmpty() ? "" : "." + ext;
            String newBaseName = nfoService.getBaseName(newFileName);
            Path oldFile = dir.resolve(oldBaseName);
            Path newFile = dir.resolve(newBaseName + dottedExt);
            if (Files.exists(oldFile) && !Files.exists(newFile)) {
                Files.move(oldFile, newFile);
                log.debug("[Organize] Renamed associated file: {} -> {}", oldBaseName, newBaseName + dottedExt);
            }
        } catch (Exception e) {
            log.warn("[Organize] Failed to rename associated file: {}", e.getMessage());
        }
    }

    private void renameAssociatedSubtitles(Path dir, String oldBaseName, String newFileName) {
        try {
            String newBaseName = nfoService.getBaseName(newFileName);
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(file -> {
                            String name = file.getFileName().toString();
                            return name.startsWith(oldBaseName) && SUBTITLE_EXTENSIONS.stream()
                                    .anyMatch(ext -> name.toLowerCase().endsWith(ext));
                        })
                        .forEach(file -> {
                            try {
                                String name = file.getFileName().toString();
                                String subExt = name.substring(name.lastIndexOf('.'));
                                Path newFile = dir.resolve(newBaseName + subExt);
                                if (!Files.exists(newFile)) {
                                    Files.move(file, newFile);
                                }
                            } catch (Exception e) {
                                log.warn("[Organize] Failed to rename subtitle: {}", e.getMessage());
                            }
                        });
            }
        } catch (Exception e) {
            log.warn("[Organize] Failed to rename subtitles: {}", e.getMessage());
        }
    }

    private void renamePosterFanartByPattern(Path dir, String oldBaseName, String newFileName) {
        try {
            String newBaseName = nfoService.getBaseName(newFileName);
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(file -> {
                            String name = file.getFileName().toString().toLowerCase();
                            return name.contains("-poster.jpg") || name.contains("-fanart.jpg");
                        })
                        .filter(file -> file.getFileName().toString().contains(oldBaseName))
                        .forEach(file -> {
                            try {
                                String name = file.getFileName().toString();
                                String suffix = name.contains("-poster") ? "-poster.jpg" : "-fanart.jpg";
                                Path newFile = dir.resolve(newBaseName + suffix);
                                if (!Files.exists(newFile)) {
                                    Files.move(file, newFile);
                                    log.debug("[Organize] Renamed poster/fanart: {}", file.getFileName());
                                }
                            } catch (Exception e) {
                                log.warn("[Organize] Failed to rename poster/fanart: {}", e.getMessage());
                            }
                        });
            }
        } catch (Exception e) {
            log.warn("[Organize] Failed to list directory for poster/fanart: {}", e.getMessage());
        }
    }

    private Path findOldActorsDir(Path videoDir) {
        Path direct = videoDir.resolve("actors");
        if (Files.isDirectory(direct)) return direct;

        Path current = videoDir.getParent();
        while (current != null) {
            if (current.getFileName() == null) break;
            Path actorsPath = current.resolve("actors");
            if (Files.isDirectory(actorsPath)) return actorsPath;
            current = current.getParent();
        }
        return null;
    }
}
