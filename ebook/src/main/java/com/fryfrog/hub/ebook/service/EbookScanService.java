package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.repository.EbookProgressRepository;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * 电子书扫描：一个文件 = 一本书（epub/pdf/mobi/azw3）。
 * 增量：filePath + fileSize + fileMtime 均未变的书跳过解析。
 * EPUB 解析（zip + XML）在虚拟线程并行执行并以信号量限流，DB 写入串行批量。
 * 事务边界：批量 upsert 与 cleanup 各自一个事务（扫描整体不包长事务）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EbookScanService {

    private static final Set<String> EBOOK_EXTENSIONS = Set.of("epub", "pdf", "mobi", "azw3");
    private static final int PARSE_CONCURRENCY = 8;

    private final EbookRepository bookRepository;
    private final EbookProgressRepository progressRepository;
    private final EpubParser epubParser;
    private final TransactionTemplate transactionTemplate;

    public synchronized void scanAndSave(String libraryPath, Long libraryId) {
        long startTime = System.currentTimeMillis();
        log.info("[EbookScan] Start: {} (libraryId={})", libraryPath, libraryId);
        Path root = Paths.get(libraryPath).toAbsolutePath().normalize();

        List<Path> files = collectFiles(root);
        Map<String, Ebook> existing = new HashMap<>();
        bookRepository.findByLibraryId(libraryId)
                .forEach(b -> existing.put(b.getFilePath(), b));

        // 并行解析（未变更的文件直接跳过，无解析开销）
        Semaphore permits = new Semaphore(PARSE_CONCURRENCY);
        List<Ebook> upserts = Collections.synchronizedList(new ArrayList<>());
        Set<String> scannedPaths = Collections.synchronizedSet(new HashSet<>());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Path file : files) {
                executor.submit(() -> {
                    try {
                        permits.acquire();
                        try {
                            Ebook parsed = parseFile(file, root, libraryId, existing);
                            if (parsed != null) upserts.add(parsed);
                            scannedPaths.add(file.toString());
                        } finally {
                            permits.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }

        transactionTemplate.executeWithoutResult(status -> {
            if (!upserts.isEmpty()) {
                bookRepository.saveAll(upserts);
            }
            cleanupMissingInTransaction(scannedPaths, libraryId);
        });

        log.info("[EbookScan] Done: {} files, {} upserted, {} existing, {} ms",
                files.size(), upserts.size(), existing.size(), System.currentTimeMillis() - startTime);
    }

    private List<Path> collectFiles(Path root) {
        if (!Files.isDirectory(root)) {
            log.warn("[EbookScan] Library path is not a directory: {}", root);
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        int dot = name.lastIndexOf('.');
                        return dot > 0 && EBOOK_EXTENSIONS.contains(name.substring(dot + 1));
                    })
                    .toList();
        } catch (IOException e) {
            log.warn("[EbookScan] Walk failed {}: {}", root, e.getMessage());
            return List.of();
        }
    }

    /** 已有书且 size/mtime 未变 → 返回 null（跳过）；否则返回新建/更新的实体。 */
    private Ebook parseFile(Path file, Path root, Long libraryId, Map<String, Ebook> existing) {
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            String filePath = file.toString();

            Ebook existingBook = existing.get(filePath);
            if (existingBook != null
                    && Objects.equals(existingBook.getFileMtime(), mtime)
                    && Objects.equals(existingBook.getFileSize(), size)) {
                return null;
            }

            String format = formatOf(file);
            Ebook book = existingBook != null ? existingBook : Ebook.builder().build();
            book.setFilePath(filePath);
            book.setFileMtime(mtime);
            book.setFileSize(size);
            book.setFormat(format);
            book.setLibraryId(libraryId);
            book.setMetadataSource("scan");

            if (Ebook.FORMAT_EPUB.equals(format)) {
                applyEpubMeta(book, file);
            } else {
                applyFilenameMeta(book, file);
            }
            return book;
        } catch (Exception e) {
            log.warn("[EbookScan] Parse failed {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void applyEpubMeta(Ebook book, Path file) {
        try {
            Path bookDir = file.getParent();
            EpubParser.EpubMeta meta = epubParser.parse(file, bookDir);
            if (meta.title() != null && !meta.title().isBlank()) {
                book.setTitle(meta.title());
            } else {
                applyFilenameMeta(book, file);
            }
            book.setAuthor(meta.author());
            book.setPublisher(meta.publisher());
            book.setLanguage(meta.language());
            book.setPubYear(meta.pubYear());
            book.setOverview(meta.overview());
            book.setTotalChapters(meta.totalChapters());
            if (meta.coverPath() != null) {
                book.setCoverArtPath(meta.coverPath().toString());
            }
        } catch (Exception e) {
            log.warn("[EbookScan] EPUB meta failed, fallback to filename: {} ({})",
                    file.getFileName(), e.getMessage());
            applyFilenameMeta(book, file);
        }
    }

    /** 文件名兜底："作者 - 书名.epub" → author + title；否则 stem 即书名。 */
    private void applyFilenameMeta(Ebook book, Path file) {
        String stem = file.getFileName().toString();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        String title = stem.strip();
        int sep = title.indexOf(" - ");
        if (sep > 0 && sep < title.length() - 3) {
            book.setAuthor(title.substring(0, sep).strip());
            book.setTitle(title.substring(sep + 3).strip());
        } else {
            book.setTitle(title);
        }
    }

    private String formatOf(Path file) {
        String ext = file.getFileName().toString().toLowerCase();
        ext = ext.substring(ext.lastIndexOf('.') + 1);
        return switch (ext) {
            case "epub" -> Ebook.FORMAT_EPUB;
            case "pdf" -> Ebook.FORMAT_PDF;
            default -> Ebook.FORMAT_MOBI; // mobi / azw3
        };
    }

    /** 目录中已消失的书清理（进度随书删除）。 */
    private void cleanupMissingInTransaction(Set<String> scannedPaths, Long libraryId) {
        List<Ebook> books = bookRepository.findByLibraryId(libraryId);
        int removed = 0;
        for (Ebook book : books) {
            if (scannedPaths.contains(book.getFilePath())) continue;
            if (Files.exists(Paths.get(book.getFilePath()))) continue;
            progressRepository.deleteByEbook_Id(book.getId());
            bookRepository.delete(book);
            removed++;
        }
        if (removed > 0) {
            log.info("[EbookScan] Cleanup: {} missing books removed (libraryId={})", removed, libraryId);
        }
    }
}
