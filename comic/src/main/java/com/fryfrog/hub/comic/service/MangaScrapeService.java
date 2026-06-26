package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.common.service.SystemSettingService;
import com.fryfrog.hub.comic.dto.anilist.AnilistSearchResult;
import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.model.ComicCharacter;
import com.fryfrog.hub.comic.repository.ComicCharacterRepository;
import com.fryfrog.hub.comic.repository.ComicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final ComicCharacterRepository characterRepository;
    private final ComicMetadataService comicMetadataService;
    private final RestTemplate scraperRestTemplate;
    private final SystemSettingService settingService;
    private final ScrapeProgressService scrapeProgressService;

    private boolean isAutoScrape() {
        return settingService.getBoolean("hub.comic.auto-scrape", false);
    }

    private double getMinScore() {
        return settingService.getDouble("hub.comic.min-score", 0.0);
    }

    public List<BangumiService.SearchResult> searchFromBangumi(String query) {
        String cleaned = cleanTitleForSearch(query);
        List<BangumiService.SearchResult> results = bangumiService.searchManga(cleaned);
        if (results.isEmpty() && !cleaned.equals(query)) {
            results = bangumiService.searchManga(query);
        }
        List<BangumiService.SearchResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.<BangumiService.SearchResult>comparingInt(r -> {
            BangumiService.SearchResult.Rating rating = r.getRating();
            return rating != null && rating.getTotal() != null ? rating.getTotal() : 0;
        }).reversed());
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

        if (detail.getSummary() != null && !detail.getSummary().isBlank()) {
            comic.setSeriesSummary(detail.getSummary());
        }

        comic.setMetadataSource("bangumi");
        comic.setMetadataSourceId(bangumiId);
        comic.setMetadataUpdatedAt(LocalDateTime.now());

        moveComicToSeriesFolder(comic);

        Comic saved = repository.save(comic);
        log.info("Bound comic '{}' to Bangumi subject id={}", saved.getTitle(), bangumiId);

        downloadCoverFromBangumi(saved, detail);
        saveBangumiCharacters(saved.getId(), bangumiId);

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

        if (detail.getDescription() != null && !detail.getDescription().isBlank()) {
            comic.setSeriesSummary(detail.getDescription());
        }

        comic.setMetadataSource("anilist");
        comic.setMetadataSourceId(anilistId);
        comic.setMetadataUpdatedAt(LocalDateTime.now());

        moveComicToSeriesFolder(comic);

        Comic saved = repository.save(comic);
        log.info("Bound comic '{}' to AniList manga id={}", saved.getTitle(), anilistId);

        downloadCoverFromAnilist(saved, detail);
        saveAnilistCharacters(saved.getId(), detail);

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
                comic.setOriginalTitle(source.getOriginalTitle());
                comic.setTitle(source.getTitle());
                comic.setMetadataSource(source.getMetadataSource());
                comic.setMetadataSourceId(source.getMetadataSourceId());
                comic.setMetadataUpdatedAt(LocalDateTime.now());
                if (source.getSeriesSummary() != null) {
                    comic.setSeriesSummary(source.getSeriesSummary());
                }
                if (source.getSerializationStart() != null) {
                    comic.setSerializationStart(source.getSerializationStart());
                }
            }

            if (comic.getVolume() != null && volumeSubjectMap.containsKey(comic.getVolume())) {
                BangumiService.RelatedSubject volSub = volumeSubjectMap.get(comic.getVolume());

                String coverUrl = volSub.getCoverUrl();
                if (coverUrl != null) {
                    downloadCover(comic, coverUrl, "bangumi_vol_" + source.getMetadataSourceId() + "_" + comic.getVolume());
                }

                VolumeInfo volInfo = volumeInfoMap.get(comic.getVolume());
                if (volInfo != null) {
                    if (volInfo.summary() != null && !volInfo.summary().isBlank()) {
                        comic.setSummary(volInfo.summary());
                    }
                    if (volInfo.publisher() != null) comic.setPublisher(volInfo.publisher());
                    if (volInfo.isbn() != null) comic.setIsbn(volInfo.isbn());
                    if (volInfo.releaseDate() != null) comic.setReleaseDate(volInfo.releaseDate());
                    if (volInfo.rating() != null) comic.setRating(volInfo.rating());
                }
            } else {
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

    private record VolumeInfo(String summary, String publisher, String isbn, String releaseDate, Double rating) {}

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
                Double rating = detail.getScore();

                map.put(volNum, new VolumeInfo(summary, publisher, isbn, releaseDate, rating));
                log.debug("Vol {}: publisher={}, isbn={}, date={}, rating={}", volNum, publisher, isbn, releaseDate, rating);

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
        if (!isAutoScrape()) {
            log.debug("Auto-scrape is disabled");
            return;
        }

        List<Comic> unboundComics = repository.findByMetadataSourceIdIsNullOrderByVolumeAsc();
        log.debug("Found {} unbound comics, starting auto-scrape", unboundComics.size());

        scrapeProgressService.start("comic", unboundComics.size());

        for (Comic comic : unboundComics) {
            try {
                Comic fresh = repository.findById(comic.getId()).orElse(null);
                if (fresh == null || fresh.getMetadataSourceId() != null) {
                    continue;
                }
                scrapeProgressService.updateItem("comic", fresh.getTitle(), "processing", null);
                autoScrapeComic(fresh);
                scrapeProgressService.updateItem("comic", fresh.getTitle(), "completed", null);
                Thread.sleep(500);
            } catch (Exception e) {
                log.warn("Failed to auto-scrape comic '{}': {}", comic.getTitle(), e.getMessage());
                scrapeProgressService.updateItem("comic", comic.getTitle(), "failed", e.getMessage());
            }
        }
        scrapeProgressService.finish("comic");
        if (!unboundComics.isEmpty()) {
            log.info("Auto-scrape completed");
        }
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
            return bindBangumi(comic.getId(), best.getId(), true);
        }

        log.info("Bangumi returned no results for '{}', trying AniList", query);
        List<AnilistSearchResult.MediaItem> aniResults = searchFromAnilist(query);
        if (!aniResults.isEmpty()) {
            AnilistSearchResult.MediaItem best = aniResults.get(0);
            if (best.getMeanScore() != null && best.getMeanScore() >= getMinScore() * 10) {
                return bindAnilist(comic.getId(), best.getId(), true);
            }
        }

        log.info("No results for comic '{}' from any source", query);
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
                String key = entry.getKey();
                String val = entry.getValue() != null ? entry.getValue().toString() : null;
                if (val == null || val.isBlank()) continue;

                if ("作者".equals(key) || "作画".equals(key)) {
                    if (comic.getAuthor() == null || comic.getAuthor().isBlank()) {
                        comic.setAuthor(val);
                    } else if (!comic.getAuthor().contains(val)) {
                        comic.setAuthor(comic.getAuthor() + ", " + val);
                    }
                } else if ("出版社".equals(key)) {
                    if (comic.getPublisher() == null || comic.getPublisher().isBlank()) {
                        comic.setPublisher(val);
                    }
                } else if ("发售日".equals(key)) {
                    if (comic.getReleaseDate() == null || comic.getReleaseDate().isBlank()) {
                        comic.setReleaseDate(val);
                    }
                } else if ("连载开始".equals(key)) {
                    if (comic.getSerializationStart() == null || comic.getSerializationStart().isBlank()) {
                        comic.setSerializationStart(val);
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
        if (comic.getFilePath() == null || comic.getFilePath().isBlank()) return;

        try {
            Path comicDir = Paths.get(comic.getFilePath()).getParent();
            if (comicDir == null) return;
            Files.createDirectories(comicDir);

            Path coverPath = comicDir.resolve(filePrefix + "_cover.jpg");
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

    private String downloadCharacterImage(Comic comic, String imageUrl, String characterName) {
        if (imageUrl == null || imageUrl.isBlank() || comic.getFilePath() == null) return null;

        try {
            Path comicDir = Paths.get(comic.getFilePath()).getParent();
            if (comicDir == null) return null;

            Path charactersDir = comicDir.resolve("characters");
            Files.createDirectories(charactersDir);

            String safeName = characterName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (safeName.isBlank()) safeName = "character_" + System.currentTimeMillis();
            Path imagePath = charactersDir.resolve(safeName + ".jpg");

            if (Files.exists(imagePath)) {
                return imagePath.toAbsolutePath().toString();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "FryfrogHub/0.1.0");
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = scraperRestTemplate.exchange(
                    imageUrl, HttpMethod.GET, request, byte[].class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Files.write(imagePath, response.getBody());
                log.info("Downloaded character image '{}' to {}", characterName, imagePath);
                return imagePath.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            log.warn("Failed to download character image '{}': {}", characterName, e.getMessage());
        }
        return null;
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

            Path rootDir = findRootDir(currentDir);
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
            moveCharacterImages(currentDir, seriesDir);

            cleanupEmptyFolders(currentDir, rootDir);

            log.debug("Moved comic '{}' to series folder: {}", comic.getTitle(), seriesDir);
        } catch (IOException e) {
            log.error("Failed to move comic '{}': {}", comic.getTitle(), e.getMessage());
        }
    }

    private void cleanupEmptyFolders(Path startDir, Path rootDir) throws IOException {
        Path dir = startDir;
        while (dir != null && !dir.equals(rootDir) && Files.isDirectory(dir)) {
            if (isDirEmpty(dir)) {
                Path parent = dir.getParent();
                Files.deleteIfExists(dir);
                log.debug("Removed empty folder: {}", dir);
                dir = parent;
            } else {
                break;
            }
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

    private Path findRootDir(Path currentDir) {
        for (String rootPath : comicMetadataService.getRootPaths()) {
            Path root = Paths.get(rootPath).toAbsolutePath().normalize();
            if (currentDir.toAbsolutePath().normalize().startsWith(root)) {
                return root;
            }
        }
        return currentDir.getParent();
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

        String comicBaseName = comic.getFileName().replaceAll("\\.[^.]+$", "");
        try (var stream = Files.list(oldDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith("_cover.jpg") && name.startsWith(comicBaseName);
                    })
                    .forEach(oldCover -> {
                        try {
                            Path newCover = newDir.resolve(oldCover.getFileName());
                            if (!Files.exists(newCover)) {
                                Files.move(oldCover, newCover);
                            }
                            if (comic.getCoverArtPath() == null || !comic.getCoverArtPath().isBlank()
                                    && Paths.get(comic.getCoverArtPath()).equals(oldCover)) {
                                comic.setCoverArtPath(newCover.toAbsolutePath().toString());
                            }
                        } catch (IOException e) {
                            log.warn("Failed to move extracted cover {}: {}", oldCover.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to scan old dir for covers: {}", e.getMessage());
        }

        if (comic.getCoverArtPath() != null && !Files.exists(Paths.get(comic.getCoverArtPath()))) {
            try (var stream = Files.list(newDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith(comicBaseName)
                                && p.getFileName().toString().endsWith("_cover.jpg"))
                        .findFirst()
                        .ifPresent(p -> comic.setCoverArtPath(p.toAbsolutePath().toString()));
            } catch (IOException ignored) {}
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

    private void moveCharacterImages(Path oldDir, Path newDir) {
        Path oldCharsDir = oldDir.resolve("characters");
        if (!Files.isDirectory(oldCharsDir)) return;

        try {
            Path newCharsDir = newDir.resolve("characters");
            Files.createDirectories(newCharsDir);

            try (var stream = Files.list(oldCharsDir)) {
                stream.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        Path target = newCharsDir.resolve(file.getFileName());
                        if (!Files.exists(target)) {
                            Files.move(file, target);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to move character image {}: {}", file.getFileName(), e.getMessage());
                    }
                });
            }

            if (isDirEmpty(oldCharsDir)) {
                Files.deleteIfExists(oldCharsDir);
            }
        } catch (IOException e) {
            log.warn("Failed to move character images: {}", e.getMessage());
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
        cleaned = cleaned.replaceAll("(?i)\\[.*?(?:raw|scanned|digital|uncensored|hololive|moe|kmoe|ahoge|comic|magazine|tankoubon|bilibili|dmzj|包子漫画|拷贝漫画).*?\\]", " ");
        cleaned = cleaned.replaceAll("(?i)【.*?(?:raw|scanned|digital|uncensored|moe|kmoe|ahoge|comic|magazine|bilibili|dmzj).*?】", " ");

        cleaned = cleaned.replaceAll("\\[|\\]", " ");
        cleaned = cleaned.replaceAll("【|】", " ");

        cleaned = cleaned.replaceAll("(?i)[Vv]ol\\.?\\s*\\d+", " ");
        cleaned = cleaned.replaceAll("卷\\s*\\d+", " ");
        cleaned = cleaned.replaceAll("第\\s*\\d+\\s*卷", " ");
        cleaned = cleaned.replaceAll("#\\s*\\d+", " ");

        cleaned = cleaned.replaceAll("\\.(?:cbz|cbr|zip|rar|epub)$", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned.isBlank() ? title : cleaned;
    }

    public boolean isConfigured() {
        return true;
    }

    @Transactional
    public void saveBangumiCharacters(Long comicId, Integer subjectId) {
        try {
            List<BangumiService.Character> characters = bangumiService.getCharacters(subjectId);

            if (characters.isEmpty()) {
                Integer seriesId = findBangumiSeriesSubjectId(subjectId);
                if (seriesId != null && !seriesId.equals(subjectId)) {
                    log.info("No characters for subject {}, trying series subject {}", subjectId, seriesId);
                    characters = bangumiService.getCharacters(seriesId);
                }
            }

            if (characters.isEmpty()) {
                log.debug("No characters found for Bangumi subject {} or its series", subjectId);
                return;
            }

            characterRepository.deleteAll(characterRepository.findByComicId(comicId));

            Comic comic = repository.findById(comicId).orElse(null);

            for (BangumiService.Character bgmChar : characters) {
                ComicCharacter character = new ComicCharacter();
                character.setComicId(comicId);

                String chineseName = null;
                if (bgmChar.getNameCn() == null || bgmChar.getNameCn().isBlank()) {
                    BangumiService.CharacterDetail detail = bangumiService.getCharacterDetail(bgmChar.getId());
                    if (detail != null) {
                        chineseName = detail.getChineseName();
                    }
                    try { Thread.sleep(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                } else {
                    chineseName = bgmChar.getNameCn();
                }

                if (chineseName != null && !chineseName.isBlank()) {
                    character.setName(chineseName);
                    character.setOriginalName(bgmChar.getName());
                } else {
                    character.setName(bgmChar.getName());
                    character.setOriginalName(bgmChar.getName());
                }

                character.setDescription(bgmChar.getSummary());
                character.setRole(bgmChar.getRole());
                character.setSourceCharacterId(bgmChar.getId());
                character.setSource("bangumi");

                String imageUrl = bgmChar.getImageUrl();
                if (imageUrl != null && !imageUrl.isBlank()) {
                    character.setImageUrl(imageUrl);
                    if (comic != null) {
                        String localPath = downloadCharacterImage(comic, imageUrl, character.getName());
                        if (localPath != null) {
                            character.setImagePath(localPath);
                        }
                    }
                }

                characterRepository.save(character);
            }
            log.info("Saved {} characters for comic id={} from Bangumi", characters.size(), comicId);
        } catch (Exception e) {
            log.warn("Failed to save Bangumi characters for comic id={}: {}", comicId, e.getMessage());
        }
    }

    private Integer findBangumiSeriesSubjectId(Integer subjectId) {
        try {
            List<BangumiService.RelatedSubject> related = bangumiService.getRelatedSubjects(subjectId);
            for (BangumiService.RelatedSubject sub : related) {
                if (sub.getType() != null && sub.getType() == 1) {
                    return sub.getId();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to find series subject for {}: {}", subjectId, e.getMessage());
        }
        return null;
    }

    @Transactional
    public void saveAnilistCharacters(Long comicId, AnilistSearchResult.MediaItem detail) {
        try {
            if (detail.getCharacters() == null || detail.getCharacters().getEdges() == null) {
                log.debug("No characters found for AniList manga {}", detail.getId());
                return;
            }

            characterRepository.deleteAll(characterRepository.findByComicId(comicId));

            Comic comic = repository.findById(comicId).orElse(null);

            for (AnilistSearchResult.MediaItem.CharacterEdge edge : detail.getCharacters().getEdges()) {
                AnilistSearchResult.MediaItem.CharacterNode node = edge.getNode();
                if (node == null || node.getName() == null) continue;

                ComicCharacter character = new ComicCharacter();
                character.setComicId(comicId);
                character.setName(node.getName().getFull());
                character.setRole(edge.getRole());
                character.setSourceCharacterId(node.getId());
                character.setSource("anilist");

                if (node.getImage() != null) {
                    String imageUrl = node.getImage().getLarge();
                    if (imageUrl == null || imageUrl.isBlank()) {
                        imageUrl = node.getImage().getMedium();
                    }
                    if (imageUrl != null && !imageUrl.isBlank()) {
                        character.setImageUrl(imageUrl);
                        if (comic != null) {
                            String localPath = downloadCharacterImage(comic, imageUrl, node.getName().getFull());
                            if (localPath != null) {
                                character.setImagePath(localPath);
                            }
                        }
                    }
                }

                characterRepository.save(character);
            }
            log.info("Saved {} characters for comic id={} from AniList", detail.getCharacters().getEdges().size(), comicId);
        } catch (Exception e) {
            log.warn("Failed to save AniList characters for comic id={}: {}", comicId, e.getMessage());
        }
    }
}
