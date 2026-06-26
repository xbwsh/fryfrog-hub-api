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
            String coverPath = withCover.map(Comic::getCoverArtPath).orElse(null);

            if (coverPath != null) {
                Path seriesDir = Paths.get(coverPath).getParent();
                try (var stream = java.nio.file.Files.list(seriesDir)) {
                    String bangumiCover = stream
                            .filter(p -> {
                                String name = p.getFileName().toString();
                                return name.startsWith("bangumi_") && name.endsWith("_cover.jpg") && !name.contains("_vol_");
                            })
                            .map(p -> p.toAbsolutePath().toString())
                            .findFirst()
                            .orElse(null);
                    if (bangumiCover != null) {
                        coverPath = bangumiCover;
                    }
                } catch (Exception ignored) {}
            }
            series.setCoverArtPath(coverPath);
            if (coverPath != null) {
                try {
                    series.setCoverUrl("/api/v1/comic/series/cover?series=" +
                            java.net.URLEncoder.encode(entry.getKey(), "UTF-8"));
                } catch (Exception ignored) {}
            }

            Optional<Comic> withSummary = entry.getValue().stream()
                    .filter(c -> c.getSeriesSummary() != null && !c.getSeriesSummary().isBlank())
                    .findFirst();
            series.setSeriesSummary(withSummary.map(Comic::getSeriesSummary).orElse(null));

            Optional<Comic> withSerialization = entry.getValue().stream()
                    .filter(c -> c.getSerializationStart() != null && !c.getSerializationStart().isBlank())
                    .findFirst();
            series.setSerializationStart(withSerialization.map(Comic::getSerializationStart).orElse(null));

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

            if (comic.getSeries() == null || comic.getSeries().isBlank()) {
                comic.setSeries(extractSeriesName(fileName));
            }
            if (comic.getVolume() == null) {
                comic.setVolume(extractVolumeFromFileName(fileName));
            }

            try {
                int pageCount = countPages(file);
                comic.setPageCount(pageCount);
            } catch (Exception e) {
                log.warn("Failed to count pages for: {}", fileName, e);
            }

            if (comic.getCoverArtPath() == null) {
                log.debug("Skipping cover extraction for: {}", fileName);
            }

            return repository.save(comic);
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
        org.springframework.data.domain.Page<Comic> page;

        do {
            page = repository.findAll(org.springframework.data.domain.PageRequest.of(pageNum++, pageSize));
            List<Long> idsToDelete = new ArrayList<>();
            for (Comic comic : page.getContent()) {
                if (comic.getFilePath() == null || !Files.exists(Paths.get(comic.getFilePath()))) {
                    log.debug("Removing invalid record: {} (path: {})", comic.getTitle(), comic.getFilePath());
                    idsToDelete.add(comic.getId());
                }
            }
            if (!idsToDelete.isEmpty()) {
                repository.deleteAllByIdInBatch(idsToDelete);
                removed += idsToDelete.size();
            }
        } while (page.hasNext());

        if (removed > 0) {
            log.info("Comic cleanup completed: removed {} invalid records", removed);
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
                                log.debug("Indexed comic: {}", path.getFileName());
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

    public void organizeAll() {
        List<Comic> comics = repository.findAll();
        int organized = 0;
        for (Comic comic : comics) {
            try {
                if (organizeComicFile(comic)) {
                    organized++;
                }
            } catch (Exception e) {
                log.warn("Failed to organize comic {}: {}", comic.getFileName(), e.getMessage());
            }
        }
        if (organized > 0) {
            log.info("Organize completed: {}/{} files moved", organized, comics.size());
        }
    }

    private boolean organizeComicFile(Comic comic) {
        try {
            File file = new File(comic.getFilePath());
            if (!file.exists()) return false;

            if (comic.getSeries() == null || comic.getSeries().isBlank()) {
                comic.setSeries(extractSeriesName(comic.getFileName()));
            }
            if (comic.getVolume() == null) {
                comic.setVolume(extractVolumeFromFileName(comic.getFileName()));
            }

            String seriesName = comic.getSeries();
            if (seriesName == null || seriesName.isBlank()) return false;

            Path targetDir = Paths.get(getFirstRootPath(), sanitizeFileName(seriesName));
            Files.createDirectories(targetDir);

            String newName = buildComicFileName(comic);
            Path targetPath = targetDir.resolve(newName);

            if (file.toPath().toAbsolutePath().equals(targetPath.toAbsolutePath())) {
                return false;
            }

            Files.move(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            comic.setFilePath(targetPath.toAbsolutePath().toString());
            comic.setFileName(newName);
            repository.save(comic);
            log.info("Organized: {} -> {}", file.getName(), targetPath);
            return true;
        } catch (Exception e) {
            log.warn("Failed to organize comic {}: {}", comic.getFileName(), e.getMessage());
            return false;
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
            name.append(" Vol.").append(String.format("%02d", comic.getVolume()));
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
        clean = clean.replaceAll("\\s*[Vv]ol\\.?\\s*\\d+\\s*", " ").trim();
        clean = clean.replaceAll("\\s*卷\\s*\\d+\\s*", " ").trim();
        clean = clean.replaceAll("\\s*#\\s*\\d+\\s*", " ").trim();
        clean = clean.replaceAll("\\s+", " ").trim();
        log.debug("Title cleaned: '{}' -> '{}'", title, clean);
        return clean.isEmpty() ? title : clean;
    }

    private String extractSeriesName(String fileName) {
        List<String> bracketContents = new ArrayList<>();

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\[\\[（(]([^\\]）)]+)[\\]）)]").matcher(fileName);
        while (m.find()) {
            bracketContents.add(m.group(1).trim());
        }

        String withoutBrackets = fileName.replaceAll("[\\[\\]（）()［］]", " ").trim();

        String series = null;
        for (String content : bracketContents) {
            if (content.matches("\\d+")) continue;
            if (content.toLowerCase().matches("(progressive|extra|online|alzation|unlasting|rainbow|clover|regret|mother|deepening|candid|calibur|divine|editorial|illustration|fanbox|doujin|dl版|tl|翻)")) continue;
            if (content.matches("[台日港]版")) continue;
            if (content.length() > 1) {
                series = content;
            }
        }

        if (series == null) {
            series = cleanTitleForSearch(withoutBrackets).split("[\\s._-]")[0].trim();
        }

        return series.isEmpty() ? null : series;
    }

    private Integer extractVolumeFromFileName(String fileName) {
        if (fileName == null) return null;

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("卷\\s*(\\d+)").matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        m = java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*卷").matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        m = java.util.regex.Pattern.compile("(?i)[Vv]ol\\.?\\s*(\\d+)").matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        m = java.util.regex.Pattern.compile("#\\s*(\\d+)").matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        m = java.util.regex.Pattern.compile("[(\\[]\\s*(\\d+)\\s*[)\\]]").matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

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
                    Path coverDir = file.toPath().getParent();
                    String coverFileName = file.getName().replaceAll("\\.[^.]+$", "_cover.jpg");
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

    public String reExtractCover(Comic comic) {
        if (comic.getFilePath() == null) return null;
        File file = new File(comic.getFilePath());
        if (!file.exists()) return null;
        try {
            String coverPath = extractCover(file);
            if (coverPath != null) {
                comic.setCoverArtPath(coverPath);
                repository.save(comic);
            }
            return coverPath;
        } catch (Exception e) {
            log.warn("Failed to re-extract cover for {}: {}", comic.getFileName(), e.getMessage());
            return null;
        }
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