package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.util.TitleCleaner;
import com.fryfrog.hub.ebook.dto.ChapterInfo;
import com.fryfrog.hub.ebook.dto.EbookDTO;
import com.fryfrog.hub.ebook.dto.EbookReadingProgressDTO;
import com.fryfrog.hub.ebook.dto.EbookSeries;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.model.EbookReadingProgress;
import com.fryfrog.hub.ebook.repository.EbookReadingProgressRepository;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fryfrog.hub.ebook.util.EpubParser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookService {

    private final EbookRepository repository;
    private final EbookReadingProgressRepository readingProgressRepository;
    private final com.fryfrog.hub.common.repository.MediaSeriesRepository seriesRepository;
    private final com.fryfrog.hub.common.repository.MediaSeriesCharacterRepository seriesCharacterRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock dbWriteLock = new ReentrantLock();

    @Value("${hub.ebook.root-paths:./media-library/ebook}")
    private String rootPathsConfig;

    public List<String> getRootPaths() {
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public String getFirstRootPath() {
        List<String> paths = getRootPaths();
        return paths.isEmpty() ? "./media-library/ebook" : paths.get(0);
    }

    private static final Set<String> SUPPORTED_FORMATS = Set.of("epub", "pdf", "mobi", "azw", "azw3", "fb2", "txt");

    private boolean hasCover(Ebook ebook) {
        return ebook.getCoverArtPath() != null && new File(ebook.getCoverArtPath()).exists();
    }

    public List<EbookDTO> getAllEbooks() {
        return repository.findAll().stream()
                .map(e -> EbookDTO.fromEntity(e, hasCover(e)))
                .toList();
    }

    public EbookDTO getEbookById(Long id) {
        Ebook ebook = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ebook", "id", id));
        return EbookDTO.fromEntity(ebook, hasCover(ebook));
    }

    public Ebook getEbookEntityById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ebook", "id", id));
    }

    public List<EbookDTO> searchByTitle(String title) {
        return repository.findByTitleContainingIgnoreCase(title).stream()
                .map(e -> EbookDTO.fromEntity(e, hasCover(e)))
                .toList();
    }

    public List<EbookDTO> searchByAuthor(String author) {
        return repository.findByAuthorContainingIgnoreCase(author).stream()
                .map(e -> EbookDTO.fromEntity(e, hasCover(e)))
                .toList();
    }

    public List<EbookDTO> getFavorites() {
        return repository.findByFavoriteTrue().stream()
                .map(e -> EbookDTO.fromEntity(e, hasCover(e)))
                .toList();
    }

    public List<EbookDTO> getRecentlyAdded() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(e -> EbookDTO.fromEntity(e, hasCover(e)))
                .toList();
    }

    public List<EbookReadingProgressDTO> getRecentlyRead() {
        List<EbookReadingProgress> progressList = readingProgressRepository.findAllByOrderByUpdatedAtDesc();
        return progressList.stream()
                .map(EbookReadingProgressDTO::fromEntity)
                .toList();
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalBooks", repository.countAll());
        stats.put("totalFavorites", repository.countFavorites());
        stats.put("totalReading", readingProgressRepository.count() - readingProgressRepository.countByCompletedTrue());
        stats.put("totalCompleted", readingProgressRepository.countByCompletedTrue());
        return stats;
    }

    public EbookDTO setFavorite(Long id, boolean status) {
        Ebook ebook = getEbookEntityById(id);
        ebook.setFavorite(status);
        repository.save(ebook);
        return EbookDTO.fromEntity(ebook, hasCover(ebook));
    }

    public List<EbookSeries> getEbooksBySeries() {
        List<Ebook> allEbooks = repository.findAll();

        Map<String, List<Ebook>> grouped = allEbooks.stream()
                .collect(Collectors.groupingBy(e -> {
                    if (e.getSeriesRef() != null) return e.getSeriesRef().getTitle();
                    String series = e.getSeries();
                    if (series == null || series.isBlank()) {
                        series = cleanTitleForSearch(e.getFileName());
                    }
                    if (series == null || series.isBlank()) {
                        series = e.getTitle();
                    }
                    return series;
                }));

        List<EbookSeries> seriesList = new ArrayList<>();
        for (Map.Entry<String, List<Ebook>> entry : grouped.entrySet()) {
            EbookSeries series = new EbookSeries();
            series.setName(entry.getKey());
            series.setSeriesId(entry.getValue().stream()
                    .filter(e -> e.getSeriesRef() != null)
                    .map(e -> e.getSeriesRef().getId())
                    .findFirst().orElse(null));
            series.setBooks(entry.getValue().stream()
                    .sorted(Comparator.comparing(e -> e.getVolume() != null ? e.getVolume() : 0))
                    .map(e -> EbookDTO.fromEntity(e, hasCover(e)))
                    .toList());
            series.setVolumeCount(entry.getValue().size());

            // 优先从 MediaSeries 获取作者和简介
            Optional<Ebook> withSeriesRef = entry.getValue().stream()
                    .filter(e -> e.getSeriesRef() != null)
                    .findFirst();
            if (withSeriesRef.isPresent() && withSeriesRef.get().getSeriesRef().getAuthor() != null) {
                series.setAuthor(withSeriesRef.get().getSeriesRef().getAuthor());
                series.setSeriesSummary(withSeriesRef.get().getSeriesRef().getDescription());
            } else {
                Optional<Ebook> withAuthor = entry.getValue().stream()
                        .filter(e -> e.getAuthor() != null && !e.getAuthor().isBlank())
                        .findFirst();
                series.setAuthor(withAuthor.map(Ebook::getAuthor).orElse(null));

                Optional<Ebook> withSummary = entry.getValue().stream()
                        .filter(e -> e.getDescription() != null && !e.getDescription().isBlank())
                        .findFirst();
                series.setSeriesSummary(withSummary.map(Ebook::getDescription).orElse(null));
            }

            Optional<Ebook> withCover = entry.getValue().stream()
                    .filter(e -> e.getCoverArtPath() != null && new File(e.getCoverArtPath()).exists())
                    .findFirst();
            series.setHasCover(withCover.isPresent());
            series.setCoverArtPath(withCover.map(Ebook::getCoverArtPath).orElse(null));

            seriesList.add(series);
        }

        seriesList.sort(Comparator.comparing(EbookSeries::getName));
        return seriesList;
    }

    public Ebook extractAndSaveMetadata(String filePath) {
        dbWriteLock.lock();
        try {
            return transactionTemplate.execute(status -> doExtractAndSaveMetadata(filePath));
        } finally {
            dbWriteLock.unlock();
        }
    }

    private Ebook doExtractAndSaveMetadata(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }

            String absolutePath = java.nio.file.Path.of(file.getAbsolutePath()).toAbsolutePath().normalize().toString();
            Ebook existing = repository.findByFilePath(absolutePath).orElse(null);

            Ebook ebook = existing != null ? existing : new Ebook();

            String fileName = file.getName();
            String baseName = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;

            ebook.setFilePath(java.nio.file.Path.of(absolutePath).toAbsolutePath().normalize().toString());
            ebook.setFileName(fileName);
            ebook.setFileSize(file.length());
            ebook.setFormat(TitleCleaner.getFileExtension(fileName).toUpperCase());

            if (EpubParser.isEpub(filePath)) {
                try {
                    EpubParser.EpubMetadata meta = EpubParser.extractMetadata(filePath);
                    if (meta != null) {
                        if (meta.title() != null && !meta.title().isBlank()) {
                            ebook.setTitle(meta.title());
                        } else {
                            ebook.setTitle(baseName);
                        }
                        ebook.setAuthor(meta.author());
                        ebook.setLanguage(meta.language());
                        ebook.setDescription(meta.description());
                        ebook.setPublisher(meta.publisher());
                        ebook.setIsbn(meta.isbn());
                        ebook.setYear(meta.year());

                        // 封面由 Bangumi 刮削下载，不从 EPUB 提取

                        try {
                            List<EpubParser.ChapterEntry> chapters = EpubParser.extractChapters(filePath);
                            if (!chapters.isEmpty()) {
                                ebook.setPageCount(chapters.size());
                            }
                        } catch (Exception e) {
                            log.debug("Failed to count chapters in epub: {}", fileName);
                        }
                    } else {
                        ebook.setTitle(baseName);
                    }
                } catch (Exception e) {
                    log.warn("Failed to extract epub metadata from: {}", fileName, e);
                    ebook.setTitle(baseName);
                }
            } else {
                ebook.setTitle(baseName);
            }

            return repository.save(ebook);
        } catch (Exception e) {
            log.error("Failed to extract metadata from: {}", filePath, e);
            throw new RuntimeException("Failed to extract metadata: " + e.getMessage(), e);
        }
    }

    public int cleanupInvalidRecords() {
        return transactionTemplate.execute(status -> {
            int removed = 0;
            int pageNum = 0;
            final int pageSize = 100;
            org.springframework.data.domain.Page<Ebook> page;

            do {
                page = repository.findAll(org.springframework.data.domain.PageRequest.of(pageNum++, pageSize));
                List<Long> idsToDelete = new ArrayList<>();
                for (Ebook ebook : page.getContent()) {
                    if (ebook.getFilePath() == null || !Files.exists(Paths.get(ebook.getFilePath()))) {
                        log.info("Removing invalid record: {} (path: {})", ebook.getTitle(), ebook.getFilePath());
                        idsToDelete.add(ebook.getId());
                    }
                }
                if (!idsToDelete.isEmpty()) {
                    Set<Long> seriesIdsToCheck = new HashSet<>();
                    for (Long id : idsToDelete) {
                        Ebook ebook = repository.findById(id).orElse(null);
                        if (ebook != null && ebook.getSeriesRef() != null) {
                            seriesIdsToCheck.add(ebook.getSeriesRef().getId());
                        }
                        readingProgressRepository.findByEbookId(id).ifPresent(readingProgressRepository::delete);
                    }
                    repository.deleteAllById(idsToDelete);
                    removed += idsToDelete.size();

                    for (Long seriesId : seriesIdsToCheck) {
                        if (repository.findBySeriesRef_Id(seriesId).isEmpty()) {
                            seriesCharacterRepository.deleteBySeries_Id(seriesId);
                            seriesRepository.deleteById(seriesId);
                            log.debug("Removed empty MediaSeries id={}", seriesId);
                        }
                    }
                }
            } while (page.hasNext());

            if (removed > 0) {
                log.info("Ebook cleanup completed: removed {} invalid records", removed);
            }
            return removed;
        });
    }

    public void scanFromRoot() {
        scanDirectory(getFirstRootPath());
    }

    public void scanDirectory(String directoryPath) {
        try {
            cleanupInvalidRecords();
            entityManager.clear();  // 清除 session 中 batch delete 残留的实体引用
            Path dir = Paths.get(directoryPath);
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException("Not a directory: " + directoryPath);
            }

            try (Stream<Path> paths = Files.walk(dir)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase();
                            String extension = name.substring(name.lastIndexOf('.') + 1);
                            return SUPPORTED_FORMATS.contains(extension);
                        })
                        .forEach(path -> {
                            try {
                                extractAndSaveMetadata(path.toString());
                                log.debug("Indexed ebook: {}", path.getFileName());
                            } catch (Exception e) {
                                log.warn("Failed to index ebook: {}", path.getFileName(), e);
                            }
                        });
            }
        } catch (Exception e) {
            log.error("Failed to scan directory: {}", directoryPath, e);
            throw new RuntimeException("Failed to scan directory: " + e.getMessage(), e);
        }
    }

    private static final Pattern VOLUME_PATTERN = Pattern.compile(
            "(?i)(?:卷|Vol\\.?|Volume|#)[\\s]*(\\d+)|[\\s]*(\\d+)\\s*(?:卷|卷目)"
    );

    private static final Pattern TRAILING_NUMBER_PATTERN = Pattern.compile(
            "[\\s]*(\\d{1,3})$"
    );

    private Integer extractVolumeFromName(String fileName) {
        if (fileName == null) return null;
        // 先试带标记的卷号（卷01、Vol.1、#1）
        Matcher m = VOLUME_PATTERN.matcher(fileName);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) return Integer.parseInt(m.group(i));
            }
        }
        // 回退到文件名末尾的数字（如 刀剑神域进击篇 01）
        String baseName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        Matcher tm = TRAILING_NUMBER_PATTERN.matcher(baseName);
        if (tm.find()) {
            return Integer.parseInt(tm.group(1));
        }
        return null;
    }

    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "^\\s*(第[零一二三四五六七八九十百千万\\d]+[章节目回篇]|Chapter\\s+\\d+|CHAPTER\\s+\\d+).*",
            Pattern.MULTILINE
    );

    public List<ChapterInfo> getChapterList(Long id) {
        Ebook ebook = getEbookEntityById(id);
        File file = new File(ebook.getFilePath());
        if (!file.exists()) {
            throw new IllegalArgumentException("Ebook file not found: " + ebook.getFilePath());
        }

        String filePath = file.getAbsolutePath();

        if (EpubParser.isEpub(filePath)) {
            try {
                List<EpubParser.ChapterEntry> epubChapters = EpubParser.extractChapters(filePath);
                if (!epubChapters.isEmpty()) {
                    return epubChapters.stream()
                            .map(ch -> new ChapterInfo(Integer.parseInt(ch.id()), ch.title()))
                            .toList();
                }
                return List.of(new ChapterInfo(1, "全文"));
            } catch (Exception e) {
                throw new RuntimeException("Failed to read epub chapters: " + e.getMessage(), e);
            }
        }

        try {
            String content = Files.readString(file.toPath());
            List<ChapterInfo> chapters = new ArrayList<>();
            Matcher matcher = CHAPTER_PATTERN.matcher(content);

            int chapterNum = 1;
            while (matcher.find()) {
                String title = matcher.group().trim();
                chapters.add(new ChapterInfo(chapterNum++, title));
            }

            if (chapters.isEmpty()) {
                chapters.add(new ChapterInfo(1, "全文"));
            }

            return chapters;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read ebook: " + e.getMessage(), e);
        }
    }

    public String getChapterContent(Long id, int chapterNum) {
        Ebook ebook = getEbookEntityById(id);
        File file = new File(ebook.getFilePath());
        if (!file.exists()) {
            throw new IllegalArgumentException("Ebook file not found: " + ebook.getFilePath());
        }

        String filePath = file.getAbsolutePath();

        if (EpubParser.isEpub(filePath)) {
            try {
                List<EpubParser.ChapterEntry> epubChapters = EpubParser.extractChapters(filePath);
                if (chapterNum < 1 || chapterNum > epubChapters.size()) {
                    throw new IllegalArgumentException("Chapter number out of range: " + chapterNum);
                }
                EpubParser.ChapterEntry chapter = epubChapters.get(chapterNum - 1);
                return EpubParser.readChapterContent(filePath, chapter.href());
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to read epub chapter: " + e.getMessage(), e);
            }
        }

        try {
            String content = Files.readString(file.toPath());
            Matcher matcher = CHAPTER_PATTERN.matcher(content);

            List<int[]> chapterPositions = new ArrayList<>();
            while (matcher.find()) {
                chapterPositions.add(new int[]{matcher.start(), matcher.end()});
            }

            if (chapterPositions.isEmpty()) {
                return content;
            }

            if (chapterNum < 1 || chapterNum > chapterPositions.size()) {
                throw new IllegalArgumentException("Chapter number out of range: " + chapterNum);
            }

            int start = chapterPositions.get(chapterNum - 1)[1];
            int end = chapterNum < chapterPositions.size()
                    ? chapterPositions.get(chapterNum)[0]
                    : content.length();

            return content.substring(start, end).trim();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read chapter: " + e.getMessage(), e);
        }
    }

    private String cleanTitleForSearch(String title) {
        String clean = title;

        // 移除包含标签的方括号: [台版] [digital] [Uncensored] [DL版]
        clean = clean.replaceAll("(?i)\\[.*?(?:台版|日版|港版|简中|繁中|中文版|digital|uncensored|dl版|raw|scanned).*?\\]", " ");

        // 移除纯数字/卷号方括号: [01] [1] [Vol.1] [第1卷]
        clean = clean.replaceAll("(?i)\\[\\s*(?:第?\\s*)?\\d+\\s*(?:卷|集|话|期)?\\s*\\]", " ");
        clean = clean.replaceAll("(?i)\\[\\s*Vol\\.?\\s*\\d+\\s*\\]", " ");

        // 移除数字文件扩展名
        clean = clean.replaceAll("\\d+\\.epub$", " ");

        // 移除所有剩余方括号
        clean = clean.replaceAll("[\\[\\]［］]", " ");

        // 移除圆括号内容
        clean = clean.replaceAll("[（(][^）)]*[）)]", " ");

        // 移除卷号格式: Vol.1, 卷1, 第1卷, #1
        clean = clean.replaceAll("(?i)Vol\\.?\\s*\\d+", " ");
        clean = clean.replaceAll("卷\\s*\\d+", " ");
        clean = clean.replaceAll("第\\s*\\d+\\s*卷", " ");
        clean = clean.replaceAll("#\\s*\\d+", " ");

        // 移除末尾的纯数字（卷号）: 001, 002, 01
        clean = clean.replaceAll("[\\s]*\\d{1,3}$", " ");

        // 压缩空格
        clean = clean.replaceAll("\\s+", " ").trim();
        return clean.isEmpty() ? title : clean;
    }

    public String extractSeriesName(Ebook ebook) {
        // 优先从文件名提取系列名 —— 文件名是用户可控的，命名更一致
        String fileName = ebook.getFileName();
        if (fileName != null && !fileName.isBlank()) {
            // 去掉扩展名再提取
            String fileBase = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            String fromFile = cleanTitleForSearch(fileBase);
            if (fromFile != null && !fromFile.isBlank()) {
                // 文件名含中日文字符 → 说明命名有意义，直接用它
                if (fromFile.codePoints().anyMatch(cp ->
                        (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                        || (cp >= 0x3040 && cp <= 0x309F) || (cp >= 0x30A0 && cp <= 0x30FF))) {
                    return fromFile;
                }
            }
        }

        // 文件名不含中文字符（如英文数字命名），回退到 EPUB 内嵌标题
        String title = ebook.getTitle();
        if (title != null && !title.isBlank()) {
            String fromTitle = cleanTitleForSearch(title);
            if (fromTitle != null && !fromTitle.isBlank()) {
                return fromTitle;
            }
        }
        return "Unknown";
    }

    @Transactional
    public void organizeAll() {
        List<Ebook> allEbooks = repository.findAll();
        int moved = 0;

        for (Ebook ebook : allEbooks) {
            try {
                if (moveEbookToSeriesFolder(ebook)) {
                    moved++;
                }
            } catch (Exception e) {
                log.warn("Failed to organize ebook '{}': {}", ebook.getTitle(), e.getMessage());
            }
        }

        if (moved > 0) {
            log.info("Organized {} ebooks into series folders", moved);
        }
    }

    public boolean moveEbookToSeriesFolder(Ebook ebook) {
        dbWriteLock.lock();
        try {
            return transactionTemplate.execute(status -> {
                try {
                    return doMoveEbookToSeriesFolder(ebook);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to move ebook: " + e.getMessage(), e);
                }
            });
        } finally {
            dbWriteLock.unlock();
        }
    }

    private boolean doMoveEbookToSeriesFolder(Ebook ebook) throws IOException {
        if (ebook.getFilePath() == null || ebook.getFilePath().isBlank()) return false;

            Path currentPath = Paths.get(ebook.getFilePath());
            if (!Files.exists(currentPath)) {
                log.warn("Ebook file not found: {}", ebook.getFilePath());
                return false;
            }

            Path currentDir = currentPath.getParent();
            if (currentDir == null) return false;

            Path rootDir = findRootDir(currentDir);
            if (rootDir == null) return false;

            String seriesName = extractSeriesName(ebook);
            if (seriesName.isBlank()) return false;

            Path seriesDir = rootDir.resolve(sanitizeFolderName(seriesName));
            Files.createDirectories(seriesDir);

            Path targetPath = seriesDir.resolve(currentPath.getFileName());
            if (currentPath.equals(targetPath)) {
                return false;
            }

            if (Files.exists(targetPath)) {
                log.warn("Target file already exists, skipping: {}", targetPath);
                return false;
            }

            // 清除目标路径可能存在的重复记录（同一文件被 watcher 和 periodic scan 重复索引）
            String targetPathStr = targetPath.toAbsolutePath().normalize().toString();
            repository.findByFilePath(targetPathStr).ifPresent(dup -> {
                if (!dup.getId().equals(ebook.getId())) {
                    repository.delete(dup);
                    log.debug("Removed duplicate record for target path: {}", targetPathStr);
                }
            });

            Files.move(currentPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            ebook.setFilePath(targetPathStr);

            // 同步移动封面文件
            if (ebook.getCoverArtPath() != null) {
                Path oldCover = Paths.get(ebook.getCoverArtPath());
                if (Files.exists(oldCover)) {
                    Path newCover = seriesDir.resolve(oldCover.getFileName());
                    Files.move(oldCover, newCover, StandardCopyOption.REPLACE_EXISTING);
                    ebook.setCoverArtPath(newCover.toAbsolutePath().normalize().toString());
                    log.debug("Moved cover for '{}': {}", ebook.getTitle(), newCover.getFileName());
                }
            }

            if (ebook.getSeries() == null || ebook.getSeries().isBlank()) {
                ebook.setSeriesName(seriesName);
            }

            // 按 {系列名} Vol.{卷号}.{ext} 格式重命名文件
            String ext = TitleCleaner.getFileExtension(ebook.getFileName());
            String newBaseName = seriesName;
            if (ebook.getVolume() != null) {
                newBaseName = seriesName + " Vol." + String.format("%02d", ebook.getVolume());
            } else {
                // 尝试从文件名提取卷号
                Integer vol = extractVolumeFromName(ebook.getFileName());
                if (vol != null) {
                    ebook.setVolume(vol);
                    newBaseName = seriesName + " Vol." + String.format("%02d", vol);
                }
            }
            String newFileName = newBaseName + "." + ext;
            Path renamedPath = seriesDir.resolve(newFileName);
            if (!Files.exists(renamedPath) && !ebook.getFileName().equals(newFileName)) {
                Files.move(seriesDir.resolve(ebook.getFileName()), renamedPath);
                ebook.setFileName(newFileName);
                ebook.setFilePath(renamedPath.toAbsolutePath().normalize().toString());
                // 同步重命名封面文件
                if (ebook.getCoverArtPath() != null) {
                    Path oldCover = Paths.get(ebook.getCoverArtPath());
                    if (Files.exists(oldCover)) {
                        String coverExt = oldCover.getFileName().toString().contains(".")
                                ? oldCover.getFileName().toString().substring(oldCover.getFileName().toString().lastIndexOf('.'))
                                : ".jpg";
                        Path newCover = seriesDir.resolve(newBaseName + "_cover" + coverExt);
                        if (!Files.exists(newCover)) {
                            Files.move(oldCover, newCover);
                            ebook.setCoverArtPath(newCover.toAbsolutePath().normalize().toString());
                        }
                    }
                }
                log.debug("Renamed ebook to: {}", newFileName);
            }

            repository.save(ebook);
            log.info("Moved ebook '{}' to series folder: {}", ebook.getTitle(), seriesDir);
            return true;
    }

    private Path findRootDir(Path currentDir) {
        for (String rootPath : getRootPaths()) {
            Path root = Paths.get(rootPath).toAbsolutePath().normalize();
            if (currentDir.toAbsolutePath().normalize().startsWith(root)) {
                return root;
            }
        }
        return null;
    }

    private String sanitizeFolderName(String name) {
        if (name == null) return "Unknown";
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
        return sanitized.isBlank() ? "Unknown" : sanitized;
    }

}
