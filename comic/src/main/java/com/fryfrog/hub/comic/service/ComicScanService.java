package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.model.ComicChapter;
import com.fryfrog.hub.comic.repository.ComicChapterRepository;
import com.fryfrog.hub.comic.repository.ComicProgressRepository;
import com.fryfrog.hub.comic.repository.ComicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 漫画扫描：目录聚合为一部作品，每个「图片子目录 / cbz·zip 压缩包」为一卷。
 * 结构：
 *   库根/作品名/第01卷/001.jpg ...          （目录卷）
 *   库根/作品名/第02卷.cbz                  （压缩包卷）
 *   库根/作品名 第01卷.cbz + 第02卷.cbz     （库根压缩包按卷后缀剥离聚合为系列）
 *   库根/散图作品/001.jpg ...               （目录直接放图 = 单卷作品）
 * 增量：章节 mtime 未变不重算页数；先扫后清（事务内删幽灵卷与幽灵作品）。
 * CBR/RAR 不支持（无解压依赖），扫描时跳过并记日志。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComicScanService {

    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of("cbz", "zip");
    private static final Set<String> UNSUPPORTED_ARCHIVES = Set.of("cbr", "rar");

    /** 卷/话后缀（"第01卷"、"Vol.2"、"v03"、"第5话"、"002"），用于库根压缩包聚合系列 */
    private static final Pattern VOLUME_SUFFIX = Pattern.compile(
            "(?:[\\s_-]*(?:第\\s*\\d+\\s*[卷话集]|Vol\\.?\\s*\\d+|v\\d+|#\\d+|\\d{1,3}))+\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final ComicRepository comicRepository;
    private final ComicChapterRepository chapterRepository;
    private final ComicProgressRepository progressRepository;
    private final ComicPageService pageService;
    private final TransactionTemplate transactionTemplate;

    public synchronized void scanAndSave(String libraryPath, Long libraryId) {
        long startTime = System.currentTimeMillis();
        log.info("[ComicScan] Start: {} (libraryId={})", libraryPath, libraryId);
        Path root = Paths.get(libraryPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.warn("[ComicScan] Library path is not a directory: {}", root);
            return;
        }

        Map<String, Comic> existingComics = new HashMap<>();
        comicRepository.findByLibraryId(libraryId)
                .forEach(c -> existingComics.put(c.getBookPath(), c));
        Map<Long, Map<String, ComicChapter>> existingChapters = new HashMap<>();
        existingComics.values().forEach(c ->
                existingChapters.put(c.getId(), loadChapters(c.getId())));

        // 结构收集：bookPath -> 系列草稿
        Map<String, SeriesDraft> drafts = new LinkedHashMap<>();
        try (Stream<Path> children = Files.list(root)) {
            for (Path entry : children.sorted().toList()) {
                if (Files.isDirectory(entry)) {
                    collectDirectorySeries(entry, drafts);
                } else if (isArchive(entry)) {
                    collectRootArchive(entry, drafts);
                } else if (isUnsupportedArchive(entry)) {
                    log.info("[ComicScan] Skipping unsupported archive: {}", entry.getFileName());
                }
            }
        } catch (IOException e) {
            log.warn("[ComicScan] Walk failed {}: {}", root, e.getMessage());
            return;
        }

        // 草稿 → 实体（mtime 未变的章节跳过页数重算）
        List<Comic> upsertComics = new ArrayList<>();
        List<ComicChapter> upsertChapters = new ArrayList<>();
        for (SeriesDraft draft : drafts.values()) {
            Comic comic = existingComics.get(draft.bookPath);
            if (comic == null) {
                comic = Comic.builder().build();
            }
            comic.setTitle(draft.title);
            comic.setBookPath(draft.bookPath);
            comic.setLibraryId(libraryId);
            comic.setMetadataSource("scan");
            comic.setTotalChapters(draft.chapters.size());
            comic.setTotalSize(draft.chapters.stream().mapToLong(c -> c.fileSize).sum());
            comic.setCoverArtPath(resolveCover(comic, draft));
            upsertComics.add(comic);

            Map<String, ComicChapter> existing = comic.getId() != null
                    ? existingChapters.getOrDefault(comic.getId(), Map.of()) : Map.of();
            for (int i = 0; i < draft.chapters.size(); i++) {
                ChapterDraft cd = draft.chapters.get(i);
                ComicChapter chapter = existing.get(cd.filePath);
                boolean recalc = chapter == null
                        || !Objects.equals(chapter.getFileMtime(), cd.mtime);
                if (chapter == null) {
                    chapter = ComicChapter.builder().build();
                }
                chapter.setComic(comic);
                chapter.setChapterIndex(i);
                chapter.setTitle(cd.title);
                chapter.setFilePath(cd.filePath);
                chapter.setType(cd.type);
                chapter.setFileMtime(cd.mtime);
                chapter.setFileSize(cd.fileSize);
                if (recalc || chapter.getPageCount() == null) {
                    chapter.setPageCount(pageService.pageCount(chapter));
                }
                upsertChapters.add(chapter);
            }
        }

        transactionTemplate.executeWithoutResult(status -> {
            comicRepository.saveAll(upsertComics);
            chapterRepository.saveAll(upsertChapters);
            cleanupInTransaction(existingComics, drafts.keySet());
        });

        log.info("[ComicScan] Done: {} series, {} chapters, {} ms",
                drafts.size(), upsertChapters.size(), System.currentTimeMillis() - startTime);
    }

    // ── 结构收集 ──

    /** 作品目录：子图片目录/压缩包为卷；目录直放图片则为单卷作品。 */
    private void collectDirectorySeries(Path seriesDir, Map<String, SeriesDraft> drafts) {
        List<Path> childDirs = new ArrayList<>();
        List<Path> childArchives = new ArrayList<>();
        int directImages = 0;
        try (Stream<Path> children = Files.list(seriesDir)) {
            for (Path child : children.sorted().toList()) {
                if (Files.isDirectory(child)) {
                    childDirs.add(child);
                } else if (isArchive(child)) {
                    childArchives.add(child);
                } else if (isUnsupportedArchive(child)) {
                    log.info("[ComicScan] Skipping unsupported archive: {}", child.getFileName());
                } else if (pageService.isImageFile(child.getFileName().toString())) {
                    directImages++;
                }
            }
        } catch (IOException e) {
            log.warn("[ComicScan] List failed {}: {}", seriesDir, e.getMessage());
            return;
        }

        String seriesName = seriesDir.getFileName().toString();
        SeriesDraft draft = new SeriesDraft(seriesDir.toString(), seriesName);
        for (Path dir : childDirs.stream().filter(this::hasImages).sorted().toList()) {
            draft.chapters.add(ChapterDraft.of(dir, dir.getFileName().toString(), ComicChapter.TYPE_DIRECTORY));
        }
        for (Path archive : childArchives) {
            draft.chapters.add(ChapterDraft.of(archive, stripExtension(archive), ComicChapter.TYPE_ARCHIVE));
        }
        if (draft.chapters.isEmpty()) {
            if (directImages > 0) {
                draft.chapters.add(ChapterDraft.of(seriesDir, seriesName, ComicChapter.TYPE_DIRECTORY));
            } else {
                return;
            }
        }
        drafts.put(draft.bookPath, draft);
    }

    /** 库根压缩包：卷后缀剥离后同名聚合为系列。 */
    private void collectRootArchive(Path archive, Map<String, SeriesDraft> drafts) {
        String seriesName = seriesTitleOf(stripExtension(archive));
        String bookPath = archive.getParent().resolve(seriesName).toString();
        SeriesDraft draft = drafts.computeIfAbsent(bookPath, k -> new SeriesDraft(bookPath, seriesName));
        draft.chapters.add(ChapterDraft.of(archive, stripExtension(archive), ComicChapter.TYPE_ARCHIVE));
    }

    // ── 工具 ──

    private boolean hasImages(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.anyMatch(p -> Files.isRegularFile(p)
                    && pageService.isImageFile(p.getFileName().toString()));
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isArchive(Path file) {
        return ext(file).map(ARCHIVE_EXTENSIONS::contains).orElse(false);
    }

    private boolean isUnsupportedArchive(Path file) {
        return ext(file).map(UNSUPPORTED_ARCHIVES::contains).orElse(false);
    }

    private Optional<String> ext(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? Optional.of(name.substring(dot + 1)) : Optional.empty();
    }

    private static String stripExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** "进击的巨人 第01卷" → "进击的巨人"；无卷后缀原样返回。 */
    static String seriesTitleOf(String name) {
        String stripped = name.strip();
        Matcher matcher = VOLUME_SUFFIX.matcher(stripped);
        if (matcher.find() && matcher.end() == stripped.length()) {
            String result = stripped.substring(0, matcher.start()).strip();
            if (!result.isBlank()) return result;
        }
        return stripped;
    }

    private Map<String, ComicChapter> loadChapters(Long comicId) {
        Map<String, ComicChapter> map = new HashMap<>();
        chapterRepository.findByComic_IdOrderByChapterIndexAsc(comicId)
                .forEach(c -> map.put(c.getFilePath(), c));
        return map;
    }

    /** 封面：第一卷第一页拷贝为作品目录下 cover.jpg。 */
    private String resolveCover(Comic comic, SeriesDraft draft) {
        if (draft.chapters.isEmpty()) return comic.getCoverArtPath();
        ChapterDraft first = draft.chapters.get(0);
        Path bookDir = Files.isDirectory(Paths.get(draft.bookPath))
                ? Paths.get(draft.bookPath)
                : Paths.get(draft.bookPath).getParent();
        if (bookDir == null) return comic.getCoverArtPath();
        Path target = bookDir.resolve("cover.jpg");
        try {
            ComicChapter probe = ComicChapter.builder()
                    .filePath(first.filePath).type(first.type).build();
            List<String> pages = pageService.listPageNames(probe);
            if (pages.isEmpty()) return comic.getCoverArtPath();
            if (ComicChapter.TYPE_ARCHIVE.equals(first.type)) {
                Path extracted = extractFirstPage(first.filePath, pages.get(0), bookDir);
                if (extracted == null) return comic.getCoverArtPath();
                Files.move(extracted, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(Paths.get(first.filePath, pages.get(0)), target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toString();
        } catch (Exception e) {
            log.debug("[ComicScan] Cover extract failed {}: {}", draft.bookPath, e.getMessage());
            return comic.getCoverArtPath();
        }
    }

    /** zip 首页解压到临时文件（避免压缩包整体解压）。 */
    private Path extractFirstPage(String archivePath, String entryName, Path outDir) {
        try (ZipFile zip = new ZipFile(archivePath)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) return null;
            Path out = outDir.resolve(".cover-" + UUID.randomUUID());
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private void cleanupInTransaction(Map<String, Comic> existingComics,
                                      Set<String> scannedBookPaths) {
        // 幽灵卷：路径不存在（含其归属作品已消失的情况，随作品级联删除）
        for (Comic comic : existingComics.values()) {
            for (ComicChapter chapter : chapterRepository.findByComic_IdOrderByChapterIndexAsc(comic.getId())) {
                if (!Files.exists(Paths.get(chapter.getFilePath()))) {
                    chapterRepository.delete(chapter);
                }
            }
        }
        // 幽灵作品：bookPath 不存在且不在本次扫描结果中
        for (Comic comic : existingComics.values()) {
            if (scannedBookPaths.contains(comic.getBookPath())) continue;
            if (Files.exists(Paths.get(comic.getBookPath()))) continue;
            progressRepository.deleteByComic_Id(comic.getId());
            chapterRepository.deleteByComic_Id(comic.getId());
            comicRepository.delete(comic);
        }
    }

    /** 系列草稿 */
    private static class SeriesDraft {
        final String bookPath;
        final String title;
        final List<ChapterDraft> chapters = new ArrayList<>();

        SeriesDraft(String bookPath, String title) {
            this.bookPath = bookPath;
            this.title = title;
        }
    }

    /** 章节草稿（构建时即取 mtime/size，页数延迟到 upsert 阶段） */
    private record ChapterDraft(String filePath, String title, String type, long mtime, long fileSize) {

        static ChapterDraft of(Path path, String title, String type) {
            try {
                return new ChapterDraft(path.toString(), title, type,
                        Files.getLastModifiedTime(path).toMillis(), Files.size(path));
            } catch (IOException e) {
                return new ChapterDraft(path.toString(), title, type, 0L, 0L);
            }
        }
    }
}
