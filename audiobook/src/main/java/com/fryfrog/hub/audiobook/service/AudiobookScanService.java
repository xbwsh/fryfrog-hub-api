package com.fryfrog.hub.audiobook.service;

import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.model.AudiobookChapter;
import com.fryfrog.hub.audiobook.model.AudiobookTrack;
import com.fryfrog.hub.audiobook.repository.AudiobookChapterRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookRepository;
import com.fryfrog.hub.audiobook.repository.AudiobookTrackRepository;
import com.fryfrog.hub.audiobook.util.NaturalOrderComparator;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.mediacore.service.FFmpegRuntime;
import com.fryfrog.hub.mediacore.service.MediaProbeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * 有声书扫描：遍历资源库，把「直接包含音频文件的目录」聚合为一本书。
 * 单文件目录 → SINGLE（M4B 内嵌章节解析）；多文件目录 → MULTI（文件即章节，自然序播放）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AudiobookScanService {

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "m4a", "m4b", "m4r", "flac", "ogg", "oga", "opus", "wav", "aac", "mp4", "mka");

    private static final Set<String> CHAPTER_CAPABLE = Set.of("m4b", "m4a", "mp4");

    private static final Set<String> COVER_NAMES = Set.of("cover.jpg", "cover.png", "cover.webp", "folder.jpg");

    private final AudiobookRepository bookRepository;
    private final AudiobookTrackRepository trackRepository;
    private final AudiobookChapterRepository chapterRepository;
    private final MediaProbeService probeService;
    private final FFmpegRuntime ffmpegRuntime;
    private final ScrapeProgressService progressService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /**
     * synchronized 串行化：手动扫描与定时扫描并发时避免同目录重复建书。
     * 不加 @Transactional：整次扫描可能包含成千上万个文件、每个文件都有 ffprobe
     * 子进程调用（秒级），包成一个长事务会长期占用 DB 连接。事务边界放在
     * 单本书的 upsert 与 cleanup 内部，用 TransactionTemplate 显式开启
     *（self-invocation 不走代理，@Transactional 在内部调用上不生效）。
     */
    public synchronized void scanAndSave(String libraryPath, Long libraryId) {
        long startTime = System.currentTimeMillis();
        log.info("[AudiobookScan] Start: {} (libraryId={})", libraryPath, libraryId);
        String module = "audiobook-scan:" + libraryId;

        Path root = Paths.get(libraryPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + libraryPath);
        }

        Map<Path, List<Path>> books = collectBooks(root);
        progressService.start(module, books.size());

        Set<String> scannedPaths = new HashSet<>();
        int created = 0;
        int index = 0;
        for (Map.Entry<Path, List<Path>> entry : books.entrySet()) {
            try {
                boolean isNew = upsertBook(entry.getKey(), entry.getValue(), root, libraryId);
                if (isNew) created++;
                scannedPaths.add(entry.getKey().toString());
                progressService.advance(module, entry.getKey().getFileName().toString(), true);
            } catch (Exception e) {
                log.warn("[AudiobookScan] Failed book {}: {}", entry.getKey(), e.getMessage(), e);
                progressService.advance(module, entry.getKey().getFileName().toString(), false);
            }
            index++;
        }

        cleanupMissing(scannedPaths, libraryId);
        progressService.finish(module);
        log.info("[AudiobookScan] Done: {} books ({} new) in {}ms", books.size(), created,
                System.currentTimeMillis() - startTime);
    }

    /** 目录直接包含音频文件即一本书；跳过隐藏目录。返回按路径排序的结果保证扫描顺序稳定。 */
    public Map<Path, List<Path>> collectBooks(Path root) {
        Map<Path, List<Path>> books = new TreeMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !isHidden(p))
                    .filter(this::isAudioFile)
                    .forEach(p -> books.computeIfAbsent(p.getParent(), k -> new ArrayList<>()).add(p));
        } catch (IOException e) {
            throw new RuntimeException("Failed to walk directory: " + libraryPathOf(root), e);
        }
        // 每本书内文件按自然序排列 = 播放顺序
        Map<Path, List<Path>> sorted = new LinkedHashMap<>();
        for (Map.Entry<Path, List<Path>> entry : books.entrySet()) {
            List<Path> files = new ArrayList<>(entry.getValue());
            files.sort(Comparator.comparing(p -> p.getFileName().toString(), NaturalOrderComparator.INSTANCE));
            sorted.put(entry.getKey(), files);
        }
        return sorted;
    }

    /** upsert 一本书（单本书一个事务）。返回是否新建。 */
    public boolean upsertBook(Path dir, List<Path> audioFiles, Path libraryRoot, Long libraryId) {
        return transactionTemplate.execute(status -> upsertBookInTransaction(dir, audioFiles, libraryRoot, libraryId));
    }

    private boolean upsertBookInTransaction(Path dir, List<Path> audioFiles, Path libraryRoot, Long libraryId) {
        String bookPath = dir.toString();
        Audiobook book = bookRepository.findByBookPath(bookPath).orElseGet(() -> Audiobook.builder()
                .bookPath(bookPath)
                .build());
        boolean isNew = book.getId() == null;

        boolean single = audioFiles.size() == 1;
        book.setPlayType(single ? Audiobook.TYPE_SINGLE : Audiobook.TYPE_MULTI);
        book.setLibraryId(libraryId);

        // 探测所有音轨（多文件书逐个 ffprobe；单文件只探一次）
        List<AudiobookTrack> tracks = new ArrayList<>();
        Map<String, Map<String, String>> tagsByFile = new LinkedHashMap<>();
        for (int i = 0; i < audioFiles.size(); i++) {
            Path file = audioFiles.get(i);
            Map<String, Object> probe = probeService.probeAudioInfo(file.toString());
            Map<String, String> tags = tagsOf(probe);
            tagsByFile.put(file.toString(), tags);

            double duration = probe.get("duration") instanceof Number n ? n.doubleValue() : 0;
            tracks.add(AudiobookTrack.builder()
                    .trackIndex(i)
                    .title(trackTitle(tags, file, single))
                    .filePath(file.toString())
                    .format(formatOf(file))
                    .durationSeconds(duration > 0 ? duration : null)
                    .fileSize(fileSizeOf(file))
                    .build());
        }

        // 书级元数据：标签优先，目录名兜底（Author/Title 结构用父目录补作者）
        Map<String, String> firstTags = audioFiles.isEmpty() ? Map.of()
                : tagsByFile.get(audioFiles.get(0).toString());
        book.setTitle(firstTag(firstTags, "album", "title"));
        if (book.getTitle() == null || book.getTitle().isBlank()) {
            book.setTitle(dir.getFileName().toString());
        }
        book.setAuthor(firstTag(firstTags, "artist", "album_artist"));
        if (book.getAuthor() == null || book.getAuthor().isBlank()) {
            book.setAuthor(inferAuthorFromStructure(dir, libraryRoot));
        }
        book.setNarrator(firstTag(firstTags, "composer", "narrator", "description"));
        book.setSeries(firstTag(firstTags, "series", "show"));
        book.setSeriesPart(parsePart(firstTag(firstTags, "series-part", "part")));
        book.setTotalDurationSeconds(tracks.stream()
                .map(AudiobookTrack::getDurationSeconds)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).sum());
        book.setTotalFileSize(tracks.stream()
                .map(AudiobookTrack::getFileSize)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue).sum());
        book.setTrackCount(tracks.size());

        Audiobook saved = bookRepository.save(book);

        // 重建音轨（路径为唯一键，简单替换）
        trackRepository.deleteByAudiobook_Id(saved.getId());
        tracks.forEach(t -> t.setAudiobook(saved));
        trackRepository.saveAll(tracks);

        // 章节：SINGLE 且容器支持时解析内嵌章节；MULTI 的章节即音轨，不入表
        chapterRepository.deleteByAudiobook_Id(saved.getId());
        if (single && CHAPTER_CAPABLE.contains(formatOf(audioFiles.get(0)))) {
            List<Map<String, Object>> chapters = probeService.probeChapters(audioFiles.get(0).toString());
            if (!chapters.isEmpty()) {
                List<AudiobookChapter> rows = new ArrayList<>();
                for (int i = 0; i < chapters.size(); i++) {
                    Map<String, Object> ch = chapters.get(i);
                    rows.add(AudiobookChapter.builder()
                            .audiobook(saved)
                            .chapterIndex(i)
                            .title(ch.get("title") instanceof String s && !s.isBlank() ? s : "Chapter " + (i + 1))
                            .startSeconds(ch.get("start") instanceof Number n ? n.doubleValue() : 0d)
                            .endSeconds(ch.get("end") instanceof Number n ? n.doubleValue() : 0d)
                            .build());
                }
                chapterRepository.saveAll(rows);
            }
        }

        ensureCover(saved, audioFiles.isEmpty() ? null : audioFiles.get(0));
        return isNew;
    }

    /**
     * 清理已消失的书：本次扫描没扫到，且目录已不存在或目录内已无音频文件。
     * 只删音频文件而保留空目录时同样清理（否则幽灵书残留，播放 404）。
     */
    public void cleanupMissing(Set<String> scannedPaths, Long libraryId) {
        transactionTemplate.executeWithoutResult(status -> cleanupMissingInTransaction(scannedPaths, libraryId));
    }

    private void cleanupMissingInTransaction(Set<String> scannedPaths, Long libraryId) {
        List<Audiobook> existing = bookRepository.findByLibraryId(libraryId);
        for (Audiobook book : existing) {
            if (scannedPaths.contains(book.getBookPath())) {
                continue;
            }
            if (directoryHasAudio(Paths.get(book.getBookPath()))) {
                continue;
            }
            log.info("[AudiobookScan] Removing missing book: {}", book.getBookPath());
            trackRepository.deleteByAudiobook_Id(book.getId());
            chapterRepository.deleteByAudiobook_Id(book.getId());
            bookRepository.delete(book);
        }
    }

    /** 目录存在且内含音频文件时返回 true（目录不存在或已无音频均视为书已消失）。 */
    private boolean directoryHasAudio(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> !isHidden(p))
                    .anyMatch(this::isAudioFile);
        } catch (IOException e) {
            // 读不了目录时保守处理：视为仍在，避免误删
            return true;
        }
    }

    /** 封面：目录 cover.jpg/folder.jpg 优先，否则从首个音轨提取内嵌图存为 cover.jpg。 */
    private void ensureCover(Audiobook book, Path firstAudio) {
        Path dir = Paths.get(book.getBookPath());
        for (String name : COVER_NAMES) {
            Path candidate = dir.resolve(name);
            if (Files.exists(candidate)) {
                book.setCoverArtPath(candidate.toString());
                return;
            }
        }
        if (firstAudio == null) return;
        Path target = dir.resolve("cover.jpg");
        try {
            Path tmp = dir.resolve(".cover-tmp.jpg");
            ProcessBuilder pb = new ProcessBuilder(ffmpegRuntime.ffmpegPath(), "-y", "-i", firstAudio.toString(),
                    "-map", "0:v:0", "-frames:v", "1", tmp.toString());
            ffmpegRuntime.applyLibraryEnv(pb);
            Process p = pb.redirectErrorStream(true).start();
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return;
            }
            if (p.exitValue() == 0 && Files.exists(tmp) && Files.size(tmp) > 0) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                book.setCoverArtPath(target.toString());
                log.debug("[AudiobookScan] Extracted embedded cover: {}", target);
            } else {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            log.debug("[AudiobookScan] Cover extraction failed for {}: {}", book.getTitle(), e.getMessage());
        }
    }

    /** Author/Title 结构：父目录在库根下一级且自身不含音频时视为作者名。 */
    private String inferAuthorFromStructure(Path dir, Path libraryRoot) {
        Path parent = dir.getParent();
        if (parent == null || parent.equals(libraryRoot)) return null;
        try (Stream<Path> stream = Files.list(parent)) {
            boolean parentHasAudio = stream.filter(Files::isRegularFile)
                    .filter(p -> !isHidden(p))
                    .anyMatch(this::isAudioFile);
            if (!parentHasAudio) {
                return parent.getFileName().toString();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String trackTitle(Map<String, String> tags, Path file, boolean single) {
        if (single) {
            String t = firstTag(tags, "album", "title", "title_sort");
            if (t != null && !t.isBlank()) return t;
        } else {
            String t = tags.get("title");
            if (t != null && !t.isBlank()) return t;
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Map<String, String> tagsOf(Map<String, Object> probe) {
        Object tagsObj = probe.get("tags");
        if (tagsObj instanceof Map<?, ?> tags) {
            Map<String, String> result = new LinkedHashMap<>();
            tags.forEach((k, v) -> {
                if (k != null && v != null) result.put(k.toString().toLowerCase(), v.toString());
            });
            return result;
        }
        return Map.of();
    }

    private static String firstTag(Map<String, String> tags, String... keys) {
        for (String key : keys) {
            String value = tags.get(key);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static Integer parsePart(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            var m = java.util.regex.Pattern.compile("(\\d+)").matcher(raw);
            return m.find() ? Integer.parseInt(m.group(1)) : null;
        }
    }

    private static String formatOf(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    private static Long fileSizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isAudioFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot > 0 && AUDIO_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static boolean isHidden(Path p) {
        String name = p.getFileName().toString();
        return name.startsWith(".") || name.equals("Thumbs.db") || name.equals("desktop.ini");
    }

    private static String libraryPathOf(Path root) {
        return root.toString();
    }
}
