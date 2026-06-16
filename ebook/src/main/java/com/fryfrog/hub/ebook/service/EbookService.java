package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.ebook.dto.BookSearchResult;
import com.fryfrog.hub.ebook.dto.ChapterInfo;
import com.fryfrog.hub.ebook.metadata.BookMetadataProviderManager;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fryfrog.hub.ebook.util.EpubParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookService {

    private final EbookRepository repository;
    private final BookMetadataProviderManager providerManager;

    @Value("${hub.ebook.root-path}")
    private String rootPath;

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

    public List<BookSearchResult> searchBooks(String title, String author) {
        BookMetadataProviderManager.ProviderResult result = providerManager.scrape(title, author);
        if (result.searchResult() == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(result.searchResult());
    }

    public Ebook scrapeAndSave(Long ebookId, BookSearchResult searchResult) {
        Ebook ebook = getEbookById(ebookId);

        if (searchResult.getTitle() != null && !searchResult.getTitle().isBlank()) {
            ebook.setTitle(searchResult.getTitle());
        }
        if (searchResult.getAuthor() != null && !searchResult.getAuthor().isBlank()) {
            ebook.setAuthor(searchResult.getAuthor());
        }
        if (searchResult.getPublisher() != null && !searchResult.getPublisher().isBlank()) {
            ebook.setPublisher(searchResult.getPublisher());
        }
        if (searchResult.getIsbn() != null && !searchResult.getIsbn().isBlank()) {
            ebook.setIsbn(searchResult.getIsbn());
        }
        if (searchResult.getYear() != null) {
            ebook.setYear(searchResult.getYear());
        }
        if (searchResult.getGenre() != null && !searchResult.getGenre().isBlank()) {
            ebook.setGenre(searchResult.getGenre());
        }
        if (searchResult.getDescription() != null && !searchResult.getDescription().isBlank()) {
            ebook.setDescription(searchResult.getDescription());
        }
        if (searchResult.getPageCount() != null && searchResult.getPageCount() > 0) {
            ebook.setPageCount(searchResult.getPageCount());
        }
        if (searchResult.getLanguage() != null && !searchResult.getLanguage().isBlank()) {
            ebook.setLanguage(searchResult.getLanguage());
        }

        BookMetadataProviderManager.ProviderResult providerResult = providerManager.scrape(
                searchResult.getTitle(), searchResult.getAuthor());

        if (providerResult.coverData() != null) {
            try {
                Path coverDir = Paths.get(rootPath, ".cache", "covers");
                Files.createDirectories(coverDir);
                String coverFileName = ebook.getFileName().replaceAll("\\.[^.]+$", ".jpg");
                Path coverPath = coverDir.resolve(coverFileName);
                Files.write(coverPath, providerResult.coverData());
                ebook.setCoverArtPath(coverPath.toAbsolutePath().toString());
                log.info("Saved cover to: {}", coverPath);
            } catch (IOException e) {
                log.warn("Failed to save cover for ebook {}: {}", ebookId, e.getMessage());
            }
        }

        return repository.save(ebook);
    }

    public int autoScrape() {
        List<Ebook> ebooks = repository.findAll();
        int scraped = 0;

        for (Ebook ebook : ebooks) {
            if (ebook.getIsbn() != null && !ebook.getIsbn().isBlank()) {
                log.debug("Skipping already scraped ebook: {}", ebook.getTitle());
                continue;
            }

            try {
                String title = ebook.getTitle();
                String author = ebook.getAuthor();

                if (title == null || title.isBlank()) {
                    continue;
                }

                BookMetadataProviderManager.ProviderResult result = providerManager.scrape(title, author);

                if (result.searchResult() != null) {
                    scrapeAndSave(ebook.getId(), result.searchResult());
                    scraped++;
                    log.info("Auto-scraped: {} -> {} ({})",
                            ebook.getTitle(), result.searchResult().getTitle(),
                            result.searchResult().getPlatform());
                } else {
                    log.info("No results for: {}", ebook.getTitle());
                }
            } catch (Exception e) {
                log.warn("Failed to auto-scrape ebook {}: {}", ebook.getTitle(), e.getMessage());
            }
        }

        log.info("Auto-scrape completed: {}/{} ebooks scraped", scraped, ebooks.size());
        return scraped;
    }

    public Ebook extractAndSaveMetadata(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }

            String absolutePath = file.getAbsolutePath();
            Ebook existing = repository.findByFilePath(absolutePath).orElse(null);

            Ebook ebook = existing != null ? existing : new Ebook();

            String fileName = file.getName();
            String baseName = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;

            ebook.setFilePath(absolutePath);
            ebook.setFileName(fileName);
            ebook.setFileSize(file.length());
            ebook.setFormat(getFileExtension(fileName).toUpperCase());

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
                            try {
                                byte[] coverData = EpubParser.readCover(filePath, meta);
                                if (coverData != null && coverData.length > 0) {
                                    Path coverDir = Paths.get(rootPath, ".cache", "covers");
                                    Files.createDirectories(coverDir);
                                    String coverFileName = baseName + ".jpg";
                                    Path coverPath = coverDir.resolve(coverFileName);
                                    Files.write(coverPath, coverData);
                                    ebook.setCoverArtPath(coverPath.toAbsolutePath().toString());
                                }
                            } catch (Exception e) {
                                log.warn("Failed to extract cover from epub: {}", fileName, e);
                            }
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

    public void scanDirectory(String directoryPath) {
        try {
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
                                log.info("Indexed ebook: {}", path.getFileName());
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

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot + 1) : "";
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
}
