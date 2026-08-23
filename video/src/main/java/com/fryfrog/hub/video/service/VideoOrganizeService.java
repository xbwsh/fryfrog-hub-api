package com.fryfrog.hub.video.service;

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
                // 未识别目录中尚未绑定的视频不整理（保留原文件，等待手动绑定）
                if (nfoService.isInUnscrapedDir(video) && video.getTmdbId() == null) {
                    skipped++;
                    continue;
                }

                // 先重命名文件（如果有 metadata）
                renameVideoFile(video);

                Path oldDir = Paths.get(video.getFilePath()).getParent();
                Path metadataDir = nfoService.getMetadataDir(video);

                if (!oldDir.equals(metadataDir)) {
                    // Phase 1: 文件操作（无锁）
                    Files.createDirectories(metadataDir);

                    Path videoPath = Paths.get(video.getFilePath());
                    Path newVideoPath = metadataDir.resolve(video.getFileName());

                    // 检查目标路径是否已被其他视频占用（file_path 有 UNIQUE 约束）
                    String newVideoPathStr = newVideoPath.toString();
                    Optional<Video> existingPath = repository.findByFilePath(newVideoPathStr);
                    if (existingPath.isPresent() && !existingPath.get().getId().equals(video.getId())) {
                        log.warn("[Organize] Skip move {}: target path already used by video id={}",
                                video.getFileName(), existingPath.get().getId());
                        skipped++;
                        continue;
                    }

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

                    // Phase 2: DB 更新
                    video.setFilePath(newVideoPath.toString());
                    repository.save(video);
                    moved++;

                    // Phase 3: 移动 actors 目录（失败不影响 video 路径保存）
                    try {
                        Path oldActorsDir = findOldActorsDir(oldDir);
                        Path newActorsDir = metadataDir.resolve("actors");
                        if (oldActorsDir != null && !Files.exists(newActorsDir)) {
                            Files.createDirectories(newActorsDir.getParent());
                            Files.move(oldActorsDir, newActorsDir);
                            log.debug("[Organize] Moved actors dir: {} -> {}", oldActorsDir, newActorsDir);
                        }
                    } catch (Exception e) {
                        log.warn("[Organize] Failed to move actors dir: {}", e.getMessage());
                    }
                } else {
                    skipped++;
                }

                // Phase 4: 清理空的旧目录（无论文件是否移动都执行）
                if (!oldDir.equals(metadataDir)) {
                    try {
                        cleanupEmptyOldDirs(oldDir, metadataDir);
                    } catch (Exception e) {
                        log.debug("[Organize] Failed to cleanup old dirs: {}", e.getMessage());
                    }
                }

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

            if (video.getOriginalFileName() == null) {
                video.setOriginalFileName(video.getFileName());
            }

            Path newPath = videoPath.getParent().resolve(newFileName);

            // 检查目标路径是否已被其他视频占用（file_path 有 UNIQUE 约束）
            String newPathStr = newPath.toString();
            Optional<Video> existing = repository.findByFilePath(newPathStr);
            if (existing.isPresent() && !existing.get().getId().equals(video.getId())) {
                log.warn("[Organize] Skip rename {}: target path already used by video id={}",
                        newFileName, existing.get().getId());
                return;
            }

            // 目标物理文件已存在且不是本文件自身（仅大小写变更）时不覆盖，避免丢失用户文件
            boolean caseOnlyRename = newPath.toString().equalsIgnoreCase(videoPath.toString());
            if (Files.exists(newPath) && !caseOnlyRename) {
                log.warn("[Organize] Skip rename {}: target file already exists on disk", newFileName);
                return;
            }

            String oldBaseName = nfoService.getBaseName(video.getFileName());
            Files.move(videoPath, newPath, StandardCopyOption.REPLACE_EXISTING);

            Path parentDir = videoPath.getParent();
            renameAssociatedFile(parentDir, oldBaseName + ".nfo", newFileName);
            renamePosterFanartByPattern(parentDir, oldBaseName, newFileName);
            renameAssociatedSubtitles(parentDir, oldBaseName, newFileName);

            video.setFileName(newFileName);
            video.setFilePath(newPath.toString());
            repository.save(video);
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

            // 检查目标路径是否已被其他视频占用（file_path 有 UNIQUE 约束）
            String targetPathStr = targetPath.toString();
            Optional<Video> existing = repository.findByFilePath(targetPathStr);
            if (existing.isPresent() && !existing.get().getId().equals(video.getId())) {
                log.warn("[Organize] Skip move {}: target path already used by video id={}",
                        video.getFileName(), existing.get().getId());
                return;
            }

            Files.move(videoPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("[Organize] Moved video: {} -> {}", videoPath, targetPath);

            // 移动外挂字幕
            String baseName = nfoService.getBaseName(video.getFileName());
            moveAssociatedSubtitles(videoPath.getParent(), targetPath.getParent(), baseName);

            video.setFilePath(targetPath.toString());
            repository.save(video);
        } catch (IOException e) {
            log.error("[Organize] Failed to move video {} to metadata dir: {}", video.getFileName(), e.getMessage(), e);
        }
    }

    /**
     * 移动视频到未识别目录（保留原文件名，带字幕和本地封面）。
     * 已在未识别目录或无法确定媒体库时不做任何操作。
     *
     * @return 是否实际发生了移动
     */
    public boolean moveToUnscrapedDir(Video video) {
        try {
            if (nfoService.isInUnscrapedDir(video)) return false;

            Path unscrapedDir = nfoService.getUnscrapedDir(video);
            if (unscrapedDir == null || video.getFilePath() == null) return false;

            Files.createDirectories(unscrapedDir);

            Path videoPath = Paths.get(video.getFilePath());
            Path targetPath = unscrapedDir.resolve(video.getFileName());
            if (videoPath.equals(targetPath)) return false;

            if (!Files.exists(videoPath)) {
                log.warn("[Organize] Unscraped move skipped, source not found: {}", videoPath);
                return false;
            }

            // 检查目标路径是否已被其他视频占用（file_path 有 UNIQUE 约束）
            String targetPathStr = targetPath.toString();
            Optional<Video> existing = repository.findByFilePath(targetPathStr);
            if (existing.isPresent() && !existing.get().getId().equals(video.getId())) {
                log.warn("[Organize] Skip unscraped move {}: target path already used by video id={}",
                        video.getFileName(), existing.get().getId());
                return false;
            }

            Files.move(videoPath, targetPath, StandardCopyOption.REPLACE_EXISTING);

            // 移动关联文件（字幕、NFO、本地封面）
            Path oldDir = videoPath.getParent();
            String baseName = nfoService.getBaseName(video.getFileName());
            moveAssociatedSubtitles(oldDir, unscrapedDir, baseName);
            moveAssociatedFile(oldDir, unscrapedDir, baseName + ".nfo");
            moveAssociatedFile(oldDir, unscrapedDir, baseName + "-poster.jpg");
            moveAssociatedFile(oldDir, unscrapedDir, baseName + "-fanart.jpg");

            video.setFilePath(targetPath.toString());
            repository.save(video);

            // 清理移动后遗留的空目录，上界为库根（库根本身不删除）
            Path libraryRoot = unscrapedDir.getParent();
            cleanupEmptyOldDirs(oldDir, libraryRoot != null ? libraryRoot : unscrapedDir);
            log.info("[Organize] Moved unscraped video: {} -> {}", videoPath, targetPath);
            return true;
        } catch (Exception e) {
            log.error("[Organize] Failed to move video {} to unscraped dir: {}", video.getFileName(), e.getMessage());
            return false;
        }
    }

    // ==================== 文件名生成 ====================

    /**
     * 生成干净的文件名
     * 电视剧：{标题} - S{季数}E{集数}.{扩展名}
     * 电影：{标题}.{扩展名}
     */
    private String generateCleanFileName(Video video) {
        String title = video.getTitle();
        if (title == null || title.isBlank()) {
            return null;
        }

        String extension = TitleCleaner.getFileExtension(video.getFileName());
        String safeTitle = TitleCleaner.sanitizeFileName(title);

        if ("tv".equalsIgnoreCase(video.getMediaType())) {
            int season = video.getSeasonNumber() != null ? video.getSeasonNumber() : 1;
            int episode = video.getEpisodeNumber() != null ? video.getEpisodeNumber() : 1;
            return String.format("%s - S%02dE%02d.%s", safeTitle, season, episode, extension);
        } else {
            return safeTitle + "." + extension;
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
                        // 无扩展名的文件 lastIndexOf 返回 -1，不能直接 substring
                        int dot = name.lastIndexOf('.');
                        String ext = dot >= 0 ? name.substring(dot) : "";
                        return !ext.isEmpty() && SUBTITLE_EXTENSIONS.contains(ext);
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
        // 只查找视频所在目录及其直接父目录中的 actors（不继续向上遍历）
        Path direct = videoDir.resolve("actors");
        if (Files.isDirectory(direct)) return direct;
        Path parent = videoDir.getParent();
        if (parent != null) {
            Path parentActors = parent.resolve("actors");
            if (Files.isDirectory(parentActors)) return parentActors;
        }
        return null;
    }

    /**
     * 清理移动后的空目录。从 oldDir 向上逐层删除空目录，直到遇到 metadataDir 或非空目录为止。
     */
    private void cleanupEmptyOldDirs(Path oldDir, Path metadataDir) throws IOException {
        // 先清理旧目录中可能残留的系列级元数据文件和 actors 目录
        cleanupOldSeasonFiles(oldDir);
        deleteActorsDir(oldDir);

        Path current = oldDir;
        while (current != null && !current.equals(metadataDir)) {
            try (var files = Files.list(current)) {
                if (files.findAny().isEmpty()) {
                    Files.delete(current);
                    log.debug("[Organize] Removed empty directory: {}", current);
                    current = current.getParent();
                } else {
                    break;
                }
            } catch (Exception e) {
                break;
            }
        }
    }

    /**
     * 删除旧目录中的 actors 子目录及所有内容
     */
    private void deleteActorsDir(Path dir) {
        if (dir == null) return;
        Path actorsDir = dir.resolve("actors");
        if (!Files.isDirectory(actorsDir)) {
            Path parent = dir.getParent();
            if (parent != null) {
                actorsDir = parent.resolve("actors");
                if (!Files.isDirectory(actorsDir)) {
                    Path grandParent = parent.getParent();
                    if (grandParent != null) {
                        actorsDir = grandParent.resolve("actors");
                    }
                }
            }
        }
        deleteRecursively(actorsDir);
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            log.debug("[Organize] Removed actors dir: {}", path);
        } catch (Exception ignored) {}
    }

    /**
     * 删除旧目录中可能残留在季/剧名级别的元数据文件
     */
    private void cleanupOldSeasonFiles(Path episodeDir) {
        Path seasonDir = episodeDir.getParent();
        if (seasonDir != null) {
            deleteIfExists(seasonDir.resolve("tvshow.nfo"));
            deleteIfExists(seasonDir.resolve("tvshow-poster.jpg"));
            deleteIfExists(seasonDir.resolve("tvshow-fanart.jpg"));

            // 如果季目录也空了，尝试清理剧名目录下的系列级文件
            try (var files = Files.list(seasonDir)) {
                if (files.findAny().isEmpty()) {
                    Path showDir = seasonDir.getParent();
                    if (showDir != null) {
                        deleteIfExists(showDir.resolve("tvshow.nfo"));
                        deleteIfExists(showDir.resolve("tvshow-poster.jpg"));
                        deleteIfExists(showDir.resolve("tvshow-fanart.jpg"));
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    public void cleanupEmptyLibraryDir(String rootPath) {
        Path root = Paths.get(rootPath);
        if (!Files.isDirectory(root)) return;
        try {
            try (var stream = Files.walk(root)) {
                stream.sorted((a, b) -> b.compareTo(a))
                        .filter(Files::isDirectory)
                        .filter(dir -> !dir.equals(root))
                        .forEach(dir -> {
                            // 先删除已知的 orphaned 子目录（actors 等）
                            deleteOrphanedSubdirs(dir);
                            // 再检查目录是否为空
                            try (var files = Files.list(dir)) {
                                if (files.findAny().isEmpty()) {
                                    Files.delete(dir);
                                    log.debug("[Cleanup] Removed empty directory: {}", dir);
                                }
                            } catch (Exception ignored) {}
                        });
            }
        } catch (Exception e) {
            log.warn("[Cleanup] Failed to cleanup empty dirs in {}: {}", rootPath, e.getMessage());
        }
    }

    private void deleteOrphanedSubdirs(Path dir) {
        String[] orphans = {"actors"};
        for (String name : orphans) {
            Path sub = dir.resolve(name);
            if (Files.isDirectory(sub)) {
                deleteRecursively(sub);
                log.debug("[Cleanup] Removed orphaned subdir: {}", sub);
            }
        }
    }

    private void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {}
    }
}
