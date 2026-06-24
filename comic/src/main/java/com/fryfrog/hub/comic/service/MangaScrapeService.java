package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.comic.dto.anilist.AnilistSearchResult;
import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.repository.ComicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class MangaScrapeService {

    private final BangumiService bangumiService;
    private final AnilistService anilistService;
    private final ComicRepository repository;
    private final ComicMetadataService comicMetadataService;
    private final RestTemplate scraperRestTemplate;

    @Value("${hub.anilist.auto-scrape:false}")
    private boolean autoScrape;

    @Value("${hub.anilist.min-score:0.0}")
    private double minScore;

    public List<BangumiService.SearchResult> searchFromBangumi(String query) {
        String cleaned = cleanTitleForSearch(query);
        List<BangumiService.SearchResult> results = bangumiService.searchManga(cleaned);
        if (results.isEmpty() && !cleaned.equals(query)) {
            results = bangumiService.searchManga(query);
        }
        List<BangumiService.SearchResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparingDouble(r -> r.getScore() != null ? -r.getScore() : 0));
        return sorted;
    }

    public List<AnilistSearchResult.MediaItem> searchFromAnilist(String query) {
        String cleaned = cleanTitleForSearch(query);
        List<AnilistSearchResult.MediaItem> results = anilistService.searchManga(cleaned);
        if (results.isEmpty() && !cleaned.equals(query)) {
            results = anilistService.searchManga(query);
        }
        List<AnilistSearchResult.MediaItem> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparingInt(item -> {
            int score = item.getMeanScore() != null ? item.getMeanScore() : 0;
            return -score;
        }));
        return sorted;
    }

    @Transactional
    public Comic bindBangumi(Long comicId, Integer bangumiId, boolean bindSeries) {
        Comic comic = comicMetadataService.getComicById(comicId);
        BangumiService.SubjectDetail detail = bangumiService.getSubjectDetail(bangumiId);
        if (detail == null) {
            throw new ResourceNotFoundException("Bangumi Subject", "id", bangumiId);
        }

        updateComicFromBangumiDetail(comic, detail);

        comic.setMetadataSource("bangumi");
        comic.setMetadataSourceId(bangumiId);
        comic.setMetadataUpdatedAt(LocalDateTime.now());

        moveComicToSeriesFolder(comic);

        Comic saved = repository.save(comic);
        log.info("Bound comic '{}' to Bangumi subject id={}", saved.getTitle(), bangumiId);

        if (bindSeries) {
            propagateSeriesMetadata(saved);
        }

        return saved;
    }

    @Transactional
    public Comic bindAnilist(Long comicId, Integer anilistId, boolean bindSeries) {
        Comic comic = comicMetadataService.getComicById(comicId);
        AnilistSearchResult.MediaItem detail = anilistService.getMangaDetail(anilistId);
        if (detail == null) {
            throw new ResourceNotFoundException("AniList Manga", "id", anilistId);
        }

        updateComicFromAnilistDetail(comic, detail);

        comic.setMetadataSource("anilist");
        comic.setMetadataSourceId(anilistId);
        comic.setMetadataUpdatedAt(LocalDateTime.now());

        moveComicToSeriesFolder(comic);

        Comic saved = repository.save(comic);
        log.info("Bound comic '{}' to AniList manga id={}", saved.getTitle(), anilistId);

        if (bindSeries) {
            propagateSeriesMetadata(saved);
        }

        return saved;
    }

    @Transactional
    public void propagateSeriesMetadata(Comic source) {
        if (source.getSeries() == null || source.getSeries().isBlank()) {
            log.warn("Cannot propagate series metadata: comic '{}' has no series name", source.getTitle());
            return;
        }

        List<Comic> seriesComics = repository.findBySeriesIgnoreCase(source.getSeries());

        Map<Integer, BangumiService.RelatedSubject> volumeSubjectMap = Map.of();
        Map<Integer, VolumeInfo> volumeInfoMap = Map.of();
        if ("bangumi".equals(source.getMetadataSource()) && source.getMetadataSourceId() != null) {
            volumeSubjectMap = buildVolumeSubjectMap(source.getMetadataSourceId());
            volumeInfoMap = buildVolumeInfoMap(volumeSubjectMap);
        }

        for (Comic comic : seriesComics) {
            if (!comic.getId().equals(source.getId())) {
                comic.setAuthor(source.getAuthor());
                comic.setSeries(source.getSeries());
                comic.setYear(source.getYear());
                comic.setGenre(source.getGenre());
                comic.setRating(source.getRating());
                comic.setOriginalTitle(source.getOriginalTitle());
                comic.setTitle(source.getTitle());
                comic.setMetadataSource(source.getMetadataSource());
                comic.setMetadataSourceId(source.getMetadataSourceId());
                comic.setMetadataUpdatedAt(LocalDateTime.now());
            }

            if (comic.getVolume() != null && volumeSubjectMap.containsKey(comic.getVolume())) {
                BangumiService.RelatedSubject volSub = volumeSubjectMap.get(comic.getVolume());

                String coverUrl = volSub.getCoverUrl();
                if (coverUrl != null) {
                    downloadCover(comic, coverUrl, "bangumi_vol_" + source.getMetadataSourceId() + "_" + comic.getVolume());
                }

                VolumeInfo volInfo = volumeInfoMap.get(comic.getVolume());
                if (volInfo != null) {
                    if (volInfo.summary != null && !volInfo.summary.isBlank()) {
                        comic.setSummary(volInfo.summary);
                    }
                    if (volInfo.publisher != null) comic.setPublisher(volInfo.publisher);
                    if (volInfo.isbn != null) comic.setIsbn(volInfo.isbn);
                    if (volInfo.releaseDate != null) comic.setReleaseDate(volInfo.releaseDate);
                }
            } else {
                comicMetadataService.reExtractCover(comic);
                if (comic.getSummary() == null || comic.getSummary().isBlank()) {
                    comic.setSummary(source.getSummary());
                }
            }

            moveComicToSeriesFolder(comic);
            repository.save(comic);
        }

        log.info("Propagated series metadata from '{}' to {} comics in series '{}'",
                source.getTitle(), seriesComics.size() - 1, source.getSeries());
    }

    private record VolumeInfo(String summary, String publisher, String isbn, String releaseDate) {}

    private Map<Integer, BangumiService.RelatedSubject> buildVolumeSubjectMap(Integer subjectId) {
        Map<Integer, BangumiService.RelatedSubject> map = new HashMap<>();
        List<BangumiService.RelatedSubject> related = bangumiService.getRelatedSubjects(subjectId);

        for (BangumiService.RelatedSubject sub : related) {
            if (sub.getType() == null || sub.getType() != 1) continue;

            String name = sub.getName();
            if (name == null) continue;

            Integer volNum = extractVolumeFromRelatedName(name);
            if (volNum != null) {
                map.put(volNum, sub);
            }
        }

        log.info("Found {} volume subjects from related subjects for Bangumi subject {}", map.size(), subjectId);
        return map;
    }

    private Map<Integer, VolumeInfo> buildVolumeInfoMap(Map<Integer, BangumiService.RelatedSubject> volumeSubjectMap) {
        Map<Integer, VolumeInfo> map = new HashMap<>();

        for (Map.Entry<Integer, BangumiService.RelatedSubject> entry : volumeSubjectMap.entrySet()) {
            Integer volNum = entry.getKey();
            BangumiService.RelatedSubject sub = entry.getValue();

            try {
                BangumiService.SubjectDetail detail = bangumiService.getSubjectDetail(sub.getId());
                if (detail == null) continue;

                String summary = detail.getSummary();
                String publisher = extractInfoboxValue(detail, "出版社");
                String isbn = extractInfoboxValue(detail, "ISBN");
                String releaseDate = detail.getDate();

                map.put(volNum, new VolumeInfo(summary, publisher, isbn, releaseDate));
                log.debug("Vol {}: publisher={}, isbn={}, date={}", volNum, publisher, isbn, releaseDate);

                Thread.sleep(200);
            } catch (Exception e) {
                log.warn("Failed to fetch detail for volume {} (subject {}): {}", volNum, sub.getId(), e.getMessage());
            }
        }

        log.info("Got {} volume details from Bangumi", map.size());
        return map;
    }

    private String extractInfoboxValue(BangumiService.SubjectDetail detail, String key) {
        if (detail.getInfobox() == null) return null;
        return detail.getInfobox().stream()
                .filter(e -> key.equals(e.getKey()))
                .map(e -> String.valueOf(e.getValue()))
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    private Integer extractVolumeFromRelatedName(String name) {
        Matcher m = Pattern.compile("\\((\\d+)\\)").matcher(name);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = Pattern.compile("(?i)vol\\.?\\s*(\\d+)").matcher(name);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = Pattern.compile("第\\s*(\\d+)\\s*卷").matcher(name);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    @Async
    public void autoScrapeAll() {
        if (!autoScrape) {
            log.info("Auto-scrape is disabled");
            return;
        }

        List<Comic> unboundComics = repository.findByMetadataSourceIdIsNull();
        log.info("Found {} unbound comics, starting auto-scrape", unboundComics.size());

        for (Comic comic : unboundComics) {
            try {
                autoScrapeComic(comic);
                Thread.sleep(500);
            } catch (Exception e) {
                log.warn("Failed to auto-scrape comic '{}': {}", comic.getTitle(), e.getMessage());
            }
        }
        log.info("Auto-scrape completed");
    }

    public Comic autoScrapeComic(Comic comic) {
        if (comic.getMetadataSourceId() != null) {
            log.debug("Comic '{}' already bound, skipping", comic.getTitle());
            return comic;
        }

        String query = comic.getTitle();

        List<BangumiService.SearchResult> bgmResults = searchFromBangumi(query);
        if (!bgmResults.isEmpty()) {
            BangumiService.SearchResult best = bgmResults.get(0);
            return bindBangumi(comic.getId(), best.getId(), false);
        }

        log.info("Bangumi returned no results for '{}', trying AniList", query);
        List<AnilistSearchResult.MediaItem> aniResults = searchFromAnilist(query);
        if (!aniResults.isEmpty()) {
            AnilistSearchResult.MediaItem best = aniResults.get(0);
            if (best.getMeanScore() != null && best.getMeanScore() >= minScore * 10) {
                return bindAnilist(comic.getId(), best.getId(), false);
            }
        }

        log.info("No results for comic '{}' from any source", comic.getTitle());
        return comic;
    }

    private void updateComicFromBangumiDetail(Comic comic, BangumiService.SubjectDetail detail) {
        if (detail.getNameCn() != null && !detail.getNameCn().isBlank()) {
            comic.setTitle(detail.getNameCn());
            comic.setOriginalTitle(detail.getName());
        } else if (detail.getName() != null) {
            comic.setTitle(detail.getName());
        }

        if (detail.getSummary() != null) {
            String desc = detail.getSummary().trim();
            if (!desc.isBlank()) {
                comic.setSummary(desc);
            }
        }

        if (detail.getScore() != null) {
            comic.setRating(detail.getScore());
        }

        if (detail.getDate() != null && detail.getDate().length() >= 4) {
            try {
                comic.setYear(Integer.parseInt(detail.getDate().substring(0, 4)));
            } catch (NumberFormatException ignored) {}
        }

        if (detail.getInfobox() != null) {
            for (BangumiService.SubjectDetail.InfoboxEntry entry : detail.getInfobox()) {
                if ("作者".equals(entry.getKey()) || "作画".equals(entry.getKey())) {
                    String val = entry.getValue() != null ? entry.getValue().toString() : null;
                    if (val != null && !val.isBlank()) {
                        if (comic.getAuthor() == null || comic.getAuthor().isBlank()) {
                            comic.setAuthor(val);
                        } else if (!comic.getAuthor().contains(val)) {
                            comic.setAuthor(comic.getAuthor() + ", " + val);
                        }
                    }
                }
            }
        }

        if (detail.getTags() != null && !detail.getTags().isEmpty()) {
            String genres = detail.getTags().stream()
                    .map(BangumiService.SearchResult.Tag::getName)
                    .filter(Objects::nonNull)
                    .limit(5)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(null);
            if (genres != null) {
                comic.setGenre(genres);
            }
        }

        Integer localVolume = extractVolumeFromFileName(comic.getFileName());
        if (localVolume != null) {
            comic.setVolume(localVolume);
        }

        String newSeriesName = detail.getNameCn() != null ? detail.getNameCn() : detail.getName();
        if (newSeriesName != null && !newSeriesName.isBlank()) {
            comic.setSeries(newSeriesName);
        }

        downloadCoverFromBangumi(comic, detail);
    }

    private void updateComicFromAnilistDetail(Comic comic, AnilistSearchResult.MediaItem detail) {
        AnilistSearchResult.MediaItem.MediaTitle title = detail.getTitle();
        if (title != null) {
            if (title.getNativeTitle() != null && !title.getNativeTitle().isBlank()) {
                comic.setTitle(title.getNativeTitle());
            } else if (title.getRomaji() != null && !title.getRomaji().isBlank()) {
                comic.setTitle(title.getRomaji());
            }
            if (title.getEnglish() != null && !title.getEnglish().isBlank()) {
                comic.setOriginalTitle(title.getEnglish());
            } else if (title.getRomaji() != null && !title.getRomaji().isBlank()) {
                comic.setOriginalTitle(title.getRomaji());
            }
        }

        if (detail.getStaff() != null && detail.getStaff().getEdges() != null) {
            String author = detail.getStaff().getEdges().stream()
                    .filter(edge -> "Story & Art".equals(edge.getRole()) || "Story".equals(edge.getRole()) || "Art".equals(edge.getRole()))
                    .map(edge -> edge.getNode() != null && edge.getNode().getName() != null ? edge.getNode().getName().getFull() : null)
                    .filter(Objects::nonNull)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(null);
            if (author != null) {
                comic.setAuthor(author);
            }
        }

        if (detail.getMeanScore() != null) {
            comic.setRating(detail.getMeanScore() / 10.0);
        }

        if (detail.getGenres() != null && !detail.getGenres().isEmpty()) {
            comic.setGenre(String.join(", ", detail.getGenres()));
        }

        if (detail.getStartDate() != null && detail.getStartDate().getYear() != null) {
            comic.setYear(detail.getStartDate().getYear());
        }

        Integer localVolume = extractVolumeFromFileName(comic.getFileName());
        if (localVolume != null) {
            comic.setVolume(localVolume);
        }

        if (comic.getSeries() == null || comic.getSeries().isBlank()) {
            if (title != null && title.getBestTitle() != null) {
                comic.setSeries(title.getBestTitle());
            }
        }

        if (detail.getDescription() != null) {
            String desc = detail.getDescription()
                    .replaceAll("<br\\s*/?>", "\n")
                    .replaceAll("<[^>]+>", "")
                    .trim();
            if (!desc.isBlank()) {
                comic.setSummary(desc);
            }
        }

        downloadCoverFromAnilist(comic, detail);
    }

    private void downloadCoverFromBangumi(Comic comic, BangumiService.SubjectDetail detail) {
        String coverUrl = null;
        if (detail.getImages() != null) {
            coverUrl = detail.getImages().getLarge();
            if (coverUrl == null || coverUrl.isBlank()) {
                coverUrl = detail.getImages().getCommon();
            }
        }
        downloadCover(comic, coverUrl, "bangumi_" + detail.getId());
    }

    private void downloadCoverFromAnilist(Comic comic, AnilistSearchResult.MediaItem detail) {
        String coverUrl = null;
        if (detail.getCoverImage() != null) {
            coverUrl = detail.getCoverImage().getLarge();
            if (coverUrl == null || coverUrl.isBlank()) {
                coverUrl = detail.getCoverImage().getMedium();
            }
        }
        downloadCover(comic, coverUrl, "anilist_" + detail.getId());
    }

    private void downloadCover(Comic comic, String coverUrl, String filePrefix) {
        if (coverUrl == null || coverUrl.isBlank()) return;

        try {
            Path coverDir = Paths.get(comicMetadataService.getFirstRootPath(), ".cache", "covers");
            Files.createDirectories(coverDir);

            Path coverPath = coverDir.resolve(filePrefix + ".jpg");
            if (Files.exists(coverPath)) {
                comic.setCoverArtPath(coverPath.toAbsolutePath().toString());
                return;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "FryfrogHub/0.1.0");
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = scraperRestTemplate.exchange(
                    coverUrl, HttpMethod.GET, request, byte[].class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Files.write(coverPath, response.getBody());
                comic.setCoverArtPath(coverPath.toAbsolutePath().toString());
                comic.setPosterUrl(coverUrl);
                log.info("Downloaded cover for '{}' to {}", comic.getTitle(), coverPath);
            }
        } catch (Exception e) {
            log.warn("Failed to download cover for '{}': {}", comic.getTitle(), e.getMessage());
        }
    }

    private void moveComicToSeriesFolder(Comic comic) {
        if (comic.getSeries() == null || comic.getSeries().isBlank()) return;
        if (comic.getFilePath() == null || comic.getFilePath().isBlank()) return;

        try {
            Path currentPath = Paths.get(comic.getFilePath());
            if (!Files.exists(currentPath)) {
                log.warn("Comic file not found: {}", comic.getFilePath());
                return;
            }

            Path currentDir = currentPath.getParent();
            if (currentDir == null) return;

            Path rootDir = currentDir.getParent();
            if (rootDir == null) return;

            String seriesName = sanitizeFolderName(comic.getSeries());
            Path seriesDir = rootDir.resolve(seriesName);
            Files.createDirectories(seriesDir);

            Path targetPath = seriesDir.resolve(currentPath.getFileName());
            if (currentPath.equals(targetPath)) {
                log.debug("Comic '{}' already in series folder", comic.getTitle());
                return;
            }

            if (Files.exists(targetPath)) {
                log.warn("Target file already exists, skipping: {}", targetPath);
                return;
            }

            Files.move(currentPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            comic.setFilePath(targetPath.toAbsolutePath().toString());

            updateCoverAndThumbnailPaths(comic, currentDir, seriesDir);

            if (Files.isDirectory(currentDir) && isDirEmpty(currentDir) && !currentDir.equals(rootDir)) {
                Files.deleteIfExists(currentDir);
                log.info("Removed empty old folder: {}", currentDir);
            }

            log.info("Moved comic '{}' to series folder: {}", comic.getTitle(), seriesDir);
        } catch (IOException e) {
            log.error("Failed to move comic '{}': {}", comic.getTitle(), e.getMessage(), e);
        }
    }

    private boolean isDirEmpty(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> !p.getFileName().toString().equals(".DS_Store")).findFirst().isEmpty();
        }
    }

    private String sanitizeFolderName(String name) {
        if (name == null) return "Unknown";
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return sanitized.isBlank() ? "Unknown" : sanitized;
    }

    private void updateCoverAndThumbnailPaths(Comic comic, Path oldDir, Path newDir) {
        if (comic.getCoverArtPath() != null && !comic.getCoverArtPath().isBlank()) {
            Path oldCover = Paths.get(comic.getCoverArtPath());
            if (oldCover.startsWith(oldDir)) {
                Path newCover = newDir.resolve(oldCover.getFileName());
                if (Files.exists(oldCover) && !oldCover.equals(newCover)) {
                    try {
                        Files.createDirectories(newCover.getParent());
                        Files.move(oldCover, newCover, StandardCopyOption.REPLACE_EXISTING);
                        comic.setCoverArtPath(newCover.toAbsolutePath().toString());
                    } catch (IOException e) {
                        log.warn("Failed to move cover: {}", e.getMessage());
                    }
                }
            }
        }

        if (comic.getThumbnailDirPath() != null && !comic.getThumbnailDirPath().isBlank()) {
            Path oldThumbDir = Paths.get(comic.getThumbnailDirPath());
            if (oldThumbDir.startsWith(oldDir)) {
                Path newThumbDir = newDir.resolve(oldThumbDir.getFileName());
                if (Files.exists(oldThumbDir) && !oldThumbDir.equals(newThumbDir)) {
                    try {
                        Files.move(oldThumbDir, newThumbDir, StandardCopyOption.REPLACE_EXISTING);
                        comic.setThumbnailDirPath(newThumbDir.toAbsolutePath().toString());
                    } catch (IOException e) {
                        log.warn("Failed to move thumbnails: {}", e.getMessage());
                    }
                }
            }
        }
    }

    private Integer extractVolumeFromFileName(String fileName) {
        if (fileName == null) return null;

        Matcher m = Pattern.compile("卷\\s*(\\d+)").matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        m = Pattern.compile("第\\s*(\\d+)\\s*卷").matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        m = Pattern.compile("(?i)[Vv]ol\\.?\\s*(\\d+)").matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        m = Pattern.compile("#\\s*(\\d+)").matcher(fileName);
        if (m.find()) return Integer.parseInt(m.group(1));

        return null;
    }

    public String cleanTitleForSearch(String title) {
        if (title == null || title.isBlank()) return title;

        String cleaned = title;
        cleaned = cleaned.replaceAll("\\[.*?\\]", " ");
        cleaned = cleaned.replaceAll("【.*?】", " ");
        cleaned = cleaned.replaceAll("\\(.*?\\)", " ");
        cleaned = cleaned.replaceAll("（.*?）", " ");

        cleaned = cleaned.replaceAll("(?i)[Vv]ol\\.?\\s*\\d+", " ");
        cleaned = cleaned.replaceAll("卷\\s*\\d+", " ");
        cleaned = cleaned.replaceAll("#\\s*\\d+", " ");
        cleaned = cleaned.replaceAll("(?i)第\\s*\\d+\\s*卷", " ");

        cleaned = cleaned.replaceAll("\\.(?:cbz|cbr|zip|rar|epub)$", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned.isBlank() ? title : cleaned;
    }

    public boolean isConfigured() {
        return true;
    }
}
