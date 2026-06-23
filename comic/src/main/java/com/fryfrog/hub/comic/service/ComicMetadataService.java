package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.comic.dto.ComicSeries;
import com.fryfrog.hub.comic.dto.PageInfo;
import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.repository.ComicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComicMetadataService {

    private final ComicRepository repository;

    @Value("${hub.comic.root-paths:./media-library/comic}")
    private String rootPathsConfig;

    public List<String> getRootPaths() {
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public String getFirstRootPath() {
        List<String> paths = getRootPaths();
        return paths.isEmpty() ? "./media-library/comic" : paths.get(0);
    }

    private static final Set<String> SUPPORTED_FORMATS = Set.of("cbz", "cbr", "zip", "rar", "epub");

    public List<Comic> getAllComics() {
        return repository.findAll();
    }

    public Comic getComicById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Comic", "id", id));
    }

    public List<Comic> searchByTitle(String title) {
        return repository.findByTitleContainingIgnoreCase(title);
    }

    public List<Comic> searchByAuthor(String author) {
        return repository.findByAuthorContainingIgnoreCase(author);
    }

    public List<Comic> getFavorites() {
        return repository.findByFavoriteTrue();
    }

    public Comic setFavorite(Long id, boolean status) {
        Comic comic = getComicById(id);
        comic.setFavorite(status);
        return repository.save(comic);
    }

    public List<ComicSeries> getComicsBySeries() {
        List<Comic> allComics = repository.findAll();

        Map<String, List<Comic>> grouped = allComics.stream()
                .collect(Collectors.groupingBy(c -> {
                    String series = c.getSeries();
                    if (series == null || series.isBlank()) {
                        series = cleanTitleForSearch(c.getFileName());
                    }
                    if (series == null || series.isBlank()) {
                        series = c.getTitle();
                    }
                    return series;
                }));

        List<ComicSeries> seriesList = new ArrayList<>();
        for (Map.Entry<String, List<Comic>> entry : grouped.entrySet()) {
            ComicSeries series = new ComicSeries();
            series.setName(entry.getKey());
            series.setComics(entry.getValue().stream()
                    .sorted(Comparator.comparing(c -> c.getVolume() != null ? c.getVolume() : 0))
                    .toList());
            series.setVolumeCount(entry.getValue().size());

            Optional<Comic> withAuthor = entry.getValue().stream()
                    .filter(c -> c.getAuthor() != null && !c.getAuthor().isBlank())
                    .findFirst();
            series.setAuthor(withAuthor.map(Comic::getAuthor).orElse(null));

            Optional<Comic> withCover = entry.getValue().stream()
                    .filter(c -> c.getCoverArtPath() != null)
                    .findFirst();
            series.setCoverArtPath(withCover.map(Comic::getCoverArtPath).orElse(null));

            seriesList.add(series);
        }

        seriesList.sort(Comparator.comparing(ComicSeries::getName));
        return seriesList;
    }

    public Comic extractAndSaveMetadata(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }

            String absolutePath = file.getAbsolutePath();
            Comic existing = repository.findByFilePath(absolutePath).orElse(null);

            Comic comic = existing != null ? existing : new Comic();

            String fileName = file.getName();
            String baseName = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;

            comic.setTitle(baseName);
            comic.setFilePath(absolutePath);
            comic.setFileName(fileName);
            comic.setFileSize(file.length());
            comic.setFormat(getFileExtension(fileName).toUpperCase());

            try {
                int pageCount = countPages(file);
                comic.setPageCount(pageCount);
            } catch (Exception e) {
                log.warn("Failed to count pages for: {}", fileName, e);
            }

            if (comic.getCoverArtPath() == null) {
                try {
                    String coverPath = extractCover(file);
                    if (coverPath != null) {
                        comic.setCoverArtPath(coverPath);
                    }
                } catch (Exception e) {
                    log.warn("Failed to extract cover for: {}", fileName, e);
                }
            }

            return repository.save(comic);
        } catch (Exception e) {
            log.error("Failed to extract metadata from: {}", filePath, e);
            throw new RuntimeException("Failed to extract metadata: " + e.getMessage(), e);
        }
    }

    @Transactional
    public int cleanupInvalidRecords() {
        List<Comic> allComics = repository.findAll();
        int removed = 0;

        for (Comic comic : allComics) {
            if (comic.getFilePath() == null || !Files.exists(Paths.get(comic.getFilePath()))) {
                log.info("Removing invalid record: {} (path: {})", comic.getTitle(), comic.getFilePath());
                repository.deleteById(comic.getId());
                removed++;
            }
        }

        log.info("Comic cleanup completed: removed {} invalid records", removed);
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
                                log.info("Indexed comic: {}", path.getFileName());
                            } catch (Exception e) {
                                log.warn("Failed to index comic: {}", path.getFileName(), e);
                            }
                        });
            }
        } catch (Exception e) {
            log.error("Failed to scan directory: {}", directoryPath, e);
            throw new RuntimeException("Failed to scan directory: " + e.getMessage(), e);
        }
    }

    public List<PageInfo> getPageList(Long id) {
        Comic comic = getComicById(id);
        File file = new File(comic.getFilePath());
        if (!file.exists()) {
            throw new IllegalArgumentException("Comic file not found: " + comic.getFilePath());
        }

        String ext = getFileExtension(file.getName()).toLowerCase();
        try {
            if (isZipBased(ext)) {
                return listZipPages(file);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read comic pages: " + e.getMessage(), e);
        }
        throw new UnsupportedOperationException("Unsupported format: " + ext);
    }

    public byte[] getPageImage(Long id, int pageNum) {
        Comic comic = getComicById(id);
        File file = new File(comic.getFilePath());
        if (!file.exists()) {
            throw new IllegalArgumentException("Comic file not found: " + comic.getFilePath());
        }

        String ext = getFileExtension(file.getName()).toLowerCase();
        try {
            if (isZipBased(ext)) {
                return readZipPage(file, pageNum);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read comic page: " + e.getMessage(), e);
        }
        throw new UnsupportedOperationException("Unsupported format: " + ext);
    }

    private void organizeComicFile(Comic comic) {
        try {
            File file = new File(comic.getFilePath());
            if (!file.exists()) return;

            String seriesName = comic.getSeries();
            if (seriesName == null || seriesName.isBlank()) {
                seriesName = comic.getTitle();
            }
            if (seriesName == null || seriesName.isBlank()) return;

            Path targetDir = Paths.get(getFirstRootPath(), sanitizeFileName(seriesName));
            Files.createDirectories(targetDir);

            String newName = buildComicFileName(comic);
            Path targetPath = targetDir.resolve(newName);

            if (!file.toPath().toAbsolutePath().equals(targetPath.toAbsolutePath())) {
                Files.move(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                comic.setFilePath(targetPath.toAbsolutePath().toString());
                comic.setFileName(newName);
                repository.save(comic);
                log.info("Organized comic to: {}", targetPath);
            }
        } catch (Exception e) {
            log.warn("Failed to organize comic file for {}: {}", comic.getTitle(), e.getMessage());
        }
    }

    private String buildComicFileName(Comic comic) {
        StringBuilder name = new StringBuilder();
        String seriesName = comic.getSeries();
        if (seriesName != null && !seriesName.isBlank()) {
            name.append(sanitizeFileName(seriesName));
        } else {
            name.append(sanitizeFileName(comic.getTitle()));
        }
        if (comic.getVolume() != null) {
            name.append(" Vol.").append(comic.getVolume());
        }
        String ext = comic.getFormat() != null ? comic.getFormat().toLowerCase() : "cbz";
        name.append(".").append(ext);
        return name.toString();
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "Unknown";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private String cleanTitleForSearch(String title) {
        String clean = title;
        clean = clean.replaceAll("[\\[\\]［］]", "").trim();
        clean = clean.replaceAll("[（(].*?[）)]", "").trim();
        clean = clean.replaceAll("[Vv]ol\\.?\\s*\\d+", "").trim();
        clean = clean.replaceAll("卷\\s*\\d+", "").trim();
        clean = clean.replaceAll("#\\s*\\d+", "").trim();
        clean = clean.replaceAll("\\s+", " ").trim();
        log.info("Title cleaned: '{}' -> '{}'", title, clean);
        return clean.isEmpty() ? title : clean;
    }

    private String extractSeriesName(String fileName) {
        String clean = fileName;
        clean = clean.replaceAll("[\\[\\]［］]", "|").trim();
        String[] parts = clean.split("\\|");
        List<String> seriesParts = new ArrayList<>();

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.matches("\\d+")) continue;
            if (trimmed.matches("[台日港]版")) continue;
            if (trimmed.toLowerCase().matches("(progressive|extra|online|alzation|unlasting|rainbow|clover|regret|mother|deepening|candid|calibur|divine|editorial|illustration|fanbox|doujin|dl版)")) continue;
            if (trimmed.length() <= 4 && trimmed.matches("[\\p{IsHan}]+")) continue;
            seriesParts.add(trimmed);
        }

        String result = String.join("", seriesParts).replaceAll("[\\s\\-_]+", "").trim();
        if (result.isEmpty()) {
            result = cleanTitleForSearch(fileName).replaceAll("[\\s\\-_]+", "").trim();
        }
        return result;
    }

    private Integer extractVolumeFromFileName(String fileName) {
        if (fileName == null) return null;

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("卷(\\d+)").matcher(fileName);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }

        m = java.util.regex.Pattern.compile("[Vv]ol\\.?\\s*(\\d+)").matcher(fileName);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }

        m = java.util.regex.Pattern.compile("#(\\d+)").matcher(fileName);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }

        m = java.util.regex.Pattern.compile("[(\\[]\\s*(\\d+)\\s*[)\\]]").matcher(fileName);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }

        return null;
    }

    private List<PageInfo> listZipPages(File file) throws IOException {
        List<PageInfo> pages = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(file)) {
            List<ZipEntry> imageEntries = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && isImageFile(entry.getName())) {
                    imageEntries.add(entry);
                }
            }
            imageEntries.sort(Comparator.comparing(ZipEntry::getName));

            for (int i = 0; i < imageEntries.size(); i++) {
                ZipEntry entry = imageEntries.get(i);
                String name = entry.getName();
                String fileName = name.contains("/")
                        ? name.substring(name.lastIndexOf('/') + 1)
                        : name;
                pages.add(new PageInfo(i + 1, fileName));
            }
        }
        return pages;
    }

    private byte[] readZipPage(File file, int pageNum) throws IOException {
        try (ZipFile zipFile = new ZipFile(file)) {
            List<ZipEntry> imageEntries = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && isImageFile(entry.getName())) {
                    imageEntries.add(entry);
                }
            }
            imageEntries.sort(Comparator.comparing(ZipEntry::getName));

            if (pageNum < 1 || pageNum > imageEntries.size()) {
                throw new IllegalArgumentException("Page number out of range: " + pageNum);
            }

            ZipEntry entry = imageEntries.get(pageNum - 1);
            try (InputStream is = zipFile.getInputStream(entry)) {
                return is.readAllBytes();
            }
        }
    }

    private String extractCover(File file) throws IOException {
        String ext = getFileExtension(file.getName()).toLowerCase();
        if (isZipBased(ext)) {
            try (ZipFile zipFile = new ZipFile(file)) {
                ZipEntry firstImage = null;
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && isImageFile(entry.getName())) {
                        firstImage = entry;
                        break;
                    }
                }

                if (firstImage != null) {
                    Path coverDir = Paths.get(getFirstRootPath(), ".cache", "covers");
                    Files.createDirectories(coverDir);
                    String coverFileName = file.getName().replaceAll("\\.[^.]+$", ".jpg");
                    Path coverPath = coverDir.resolve(coverFileName);

                    try (InputStream is = zipFile.getInputStream(firstImage)) {
                        Files.copy(is, coverPath, StandardCopyOption.REPLACE_EXISTING);
                    }

                    return coverPath.toAbsolutePath().toString();
                }
            }
        }
        return null;
    }

    private int countPages(File file) throws IOException {
        String ext = getFileExtension(file.getName()).toLowerCase();
        if (isZipBased(ext)) {
            try (ZipFile zipFile = new ZipFile(file)) {
                int count = 0;
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && isImageFile(entry.getName())) {
                        count++;
                    }
                }
                return count;
            }
        }
        return 0;
    }

    private boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif")
                || lower.endsWith(".webp");
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot + 1) : "";
    }

    private boolean isZipBased(String ext) {
        return "cbz".equals(ext) || "zip".equals(ext) || "epub".equals(ext);
    }
}