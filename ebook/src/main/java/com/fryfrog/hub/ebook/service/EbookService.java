package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.util.TitleCleaner;
import com.fryfrog.hub.ebook.dto.ChapterInfo;
import com.fryfrog.hub.ebook.dto.EbookSeries;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fryfrog.hub.ebook.util.EpubParser;

import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookService {

    private final EbookRepository repository;

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

    public List<Ebook> getAllEbooks() {
        return repository.findAll();
    }

    public Ebook getEbookById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ebook", "id", id));
    }

    public List<Ebook> searchByTitle(String title) {
        return repository.findByTitleContainingIgnoreCase(title);
    }

    public List<Ebook> searchByAuthor(String author) {
        return repository.findByAuthorContainingIgnoreCase(author);
    }

    public List<Ebook> getFavorites() {
        return repository.findByFavoriteTrue();
    }

    public Ebook setFavorite(Long id, boolean status) {
        Ebook ebook = getEbookById(id);
        ebook.setFavorite(status);
        return repository.save(ebook);
    }

    public List<EbookSeries> getEbooksBySeries() {
        List<Ebook> allEbooks = repository.findAll();

        Map<String, List<Ebook>> grouped = allEbooks.stream()
                .collect(Collectors.groupingBy(e -> {
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
            series.setBooks(entry.getValue().stream()
                    .sorted(Comparator.comparing(e -> e.getVolume() != null ? e.getVolume() : 0))
                    .toList());
            series.setVolumeCount(entry.getValue().size());

            Optional<Ebook> withAuthor = entry.getValue().stream()
                    .filter(e -> e.getAuthor() != null && !e.getAuthor().isBlank())
                    .findFirst();
            series.setAuthor(withAuthor.map(Ebook::getAuthor).orElse(null));

            Optional<Ebook> withCover = entry.getValue().stream()
                    .filter(e -> e.getCoverArtPath() != null)
                    .findFirst();
            series.setCoverArtPath(withCover.map(Ebook::getCoverArtPath).orElse(null));

            seriesList.add(series);
        }

        seriesList.sort(Comparator.comparing(EbookSeries::getName));
        return seriesList;
    }

    public Ebook extractAndSaveMetadata(String filePath) {
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

                        if (meta.coverEntryName() != null) {
                            log.debug("Skipping cover extraction for epub: {}", fileName);
                        }

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

    @Transactional
    public int cleanupInvalidRecords() {
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
                repository.deleteAllByIdInBatch(idsToDelete);
                removed += idsToDelete.size();
            }
        } while (page.hasNext());

        if (removed > 0) {
            log.info("Ebook cleanup completed: removed {} invalid records", removed);
        }
        return removed;
    }

    public void scanFromRoot() {
        scanDirectory(getFirstRootPath());
    }

    public void scanDirectory(String directoryPath) {
        try {
            cleanupInvalidRecords();
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

    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "^\\s*(第[零一二三四五六七八九十百千万\\d]+[章节目回篇]|Chapter\\s+\\d+|CHAPTER\\s+\\d+).*",
            Pattern.MULTILINE
    );

    public List<ChapterInfo> getChapterList(Long id) {
        Ebook ebook = getEbookById(id);
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
        Ebook ebook = getEbookById(id);
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
        clean = clean.replaceAll("[\\[\\]［］]", " ").trim();
        clean = clean.replaceAll("[（(][^）)]*[）)]", " ").trim();
        clean = clean.replaceAll("[台日港]版", " ").trim();
        clean = clean.replaceAll("[Vv]ol\\.?\\s*\\d+", " ").trim();
        clean = clean.replaceAll("卷\\s*\\d+", " ").trim();
        clean = clean.replaceAll("#\\s*\\d+", " ").trim();
        clean = clean.replaceAll("\\d+\\.epub$", " ").trim();
        clean = clean.replaceAll("\\s+", " ").trim();
        return clean.isEmpty() ? title : clean;
    }

}
