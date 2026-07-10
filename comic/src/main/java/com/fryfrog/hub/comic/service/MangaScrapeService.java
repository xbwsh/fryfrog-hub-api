package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaSeries;
import com.fryfrog.hub.common.model.MediaSeriesCharacter;
import com.fryfrog.hub.common.repository.MediaSeriesCharacterRepository;
import com.fryfrog.hub.common.repository.MediaSeriesRepository;
import com.fryfrog.hub.common.service.BangumiService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.common.service.SystemSettingService;
import com.fryfrog.hub.comic.model.Comic;
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
    private final ComicRepository repository;
    private final ComicMetadataService comicMetadataService;
    private final RestTemplate scraperRestTemplate;
    private final ScrapeProgressService scrapeProgressService;
    private final MediaSeriesRepository seriesRepo;
    private final MediaSeriesCharacterRepository mediaCharRepo;
    private final SystemSettingService settingService;

    public List<BangumiService.SearchResult> searchFromBangumi(String query) {
        String cleaned = cleanTitleForSearch(query);
        List<BangumiService.SearchResult> results = bangumiService.searchBooks(cleaned, "manga");
        if (results.isEmpty() && !cleaned.equals(query)) {
            results = bangumiService.searchBooks(query, "manga");
        }
        return BangumiService.sortByPopularity(results);
    }

    @Transactional
    public Comic bindBangumi(Long comicId, Integer bangumiId) {
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

        // 查找或创建 MediaSeries
        String seriesName = comic.getSeries();
        if (seriesName != null && !seriesName.isBlank()) {
            MediaSeries ms = findOrCreateSeries(seriesName, "comic", detail);
            comic.setSeriesRef(ms);
        }

        moveComicToSeriesFolder(comic);

        Comic saved = repository.save(comic);
        log.info("Bound comic '{}' to Bangumi subject id={}", saved.getTitle(), bangumiId);

        downloadCoverFromBangumi(saved, detail);

        // 将封面路径同步到 MediaSeries（系列级封面）
        if (saved.getSeriesRef() != null && saved.getCoverArtPath() != null) {
            saved.getSeriesRef().setCoverArtPath(saved.getCoverArtPath());
            seriesRepo.save(saved.getSeriesRef());
        }

        // 下载卷级封面
        if (saved.getVolume() != null) {
            Map<Integer, BangumiService.RelatedSubject> volumeMap = bangumiService.buildVolumeSubjectMap(bangumiId);
            if (volumeMap.containsKey(saved.getVolume())) {
                BangumiService.RelatedSubject volSub = volumeMap.get(saved.getVolume());
                String volCoverUrl = volSub.getCoverUrl();
                if (volCoverUrl != null) {
                    downloadCover(saved, volCoverUrl, "bangumi_vol_" + bangumiId + "_" + saved.getVolume());
                }
            }
        }

        repository.save(saved);
        saveBangumiCharacters(saved.getId(), bangumiId);

        return saved;
    }

    /**
     * 按系列名重新刮削：重新获取系列简介、系列封面、每个卷的封面和简介
     */
    @Transactional
    public int rescrapeSeries(String series) {
        if (series == null || series.isBlank()) return 0;

        MediaSeries ms = seriesRepo.findByTitleAndMediaTypeIgnoreCase(series, "comic")
                .or(() -> seriesRepo.findByTitleAndMediaTypeIgnoreCase(series, "both"))
                .orElse(null);
        if (ms == null) {
            log.warn("No MediaSeries found for '{}'", series);
            return 0;
        }
        List<Comic> comics = repository.findBySeriesRef_Id(ms.getId());
        if (comics.isEmpty()) {
            log.warn("No comics found for series '{}'", series);
            return 0;
        }

        // 找到已有 metadataSourceId 的作为主条目，或者用第一本搜索
        Comic mainBook = comics.stream()
                .filter(c -> c.getMetadataSourceId() != null)
                .findFirst()
                .orElse(comics.get(0));

        Integer subjectId = mainBook.getMetadataSourceId();
        if (subjectId == null) {
            // 没有绑定过，需要先搜索
            log.info("Series '{}' has no bangumi binding, searching...", series);
            List<BangumiService.SearchResult> results = searchFromBangumi(series);
            if (results.isEmpty()) {
                log.warn("No Bangumi results for series '{}'", series);
                return 0;
            }
            subjectId = results.get(0).getId();
        }

        BangumiService.SubjectDetail seriesDetail = bangumiService.getSubjectDetail(subjectId);
        if (seriesDetail == null) {
            log.warn("Could not fetch Bangumi detail for series '{}' (id={})", series, subjectId);
            return 0;
        }

        log.info("Rescraping series '{}' with Bangumi id={}", series, subjectId);

        // 获取卷级映射
        Map<Integer, BangumiService.RelatedSubject> volumeMap = bangumiService.buildVolumeSubjectMap(subjectId);
        Map<Integer, VolumeInfo> volumeInfoMap = buildVolumeInfoMap(volumeMap);

        int updated = 0;
        for (Comic comic : comics) {
            try {
                // 更新系列级元数据
                updateComicFromBangumiDetail(comic, seriesDetail);
                if (seriesDetail.getSummary() != null && !seriesDetail.getSummary().isBlank()) {
                    comic.setSeriesSummary(seriesDetail.getSummary());
                }
                comic.setMetadataSource("bangumi");
                comic.setMetadataSourceId(subjectId);
                comic.setMetadataUpdatedAt(LocalDateTime.now());

                // 匹配卷级封面和简介
                if (comic.getVolume() != null && volumeMap.containsKey(comic.getVolume())) {
                    BangumiService.RelatedSubject volSub = volumeMap.get(comic.getVolume());

                    // 下载卷级封面
                    String coverUrl = volSub.getCoverUrl();
                    if (coverUrl != null) {
                        downloadCover(comic, coverUrl, "bangumi_vol_" + subjectId + "_" + comic.getVolume());
                    }

                    // 更新卷级简介
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
                }

                moveComicToSeriesFolder(comic);
                repository.save(comic);
                updated++;
                log.info("Rescraped '{}' (vol={})", comic.getTitle(), comic.getVolume());
            } catch (Exception e) {
                log.warn("Failed to rescrape '{}': {}", comic.getTitle(), e.getMessage());
            }
        }

        log.info("Rescrape series '{}': {}/{} updated", series, updated, comics.size());

        // 同步系列封面
        Comic firstWithPath = comics.stream().filter(c -> c.getFilePath() != null).findFirst().orElse(null);
        if (firstWithPath != null) {
            Path seriesDir = Paths.get(firstWithPath.getFilePath()).getParent();
            if (seriesDir != null) {
                String seriesCoverPath = bangumiService.downloadCover(seriesDetail, "bangumi_" + subjectId, seriesDir);
                if (seriesCoverPath != null) {
                    ms.setCoverArtPath(seriesCoverPath);
                    seriesRepo.save(ms);
                }
            }
        }

        return updated;
    }

    private record VolumeInfo(String summary, String publisher, String isbn, String releaseDate, Double rating) {}

    private Map<Integer, VolumeInfo> buildVolumeInfoMap(Map<Integer, BangumiService.RelatedSubject> volumeSubjectMap) {
        Map<Integer, VolumeInfo> map = new HashMap<>();

        for (Map.Entry<Integer, BangumiService.RelatedSubject> entry : volumeSubjectMap.entrySet()) {
            Integer volNum = entry.getKey();
            BangumiService.RelatedSubject sub = entry.getValue();

            try {
                BangumiService.SubjectDetail detail = bangumiService.getSubjectDetail(sub.getId());
                if (detail == null) continue;

                String summary = detail.getSummary();
                String publisher = BangumiService.extractInfoboxValue(detail, "出版社");
                String isbn = BangumiService.extractInfoboxValue(detail, "ISBN");
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

    @Async
    public void autoScrapeAll() {
        if (!settingService.getBoolean("scrape.auto-scrape", true)) {
            log.debug("Auto-scrape is disabled by setting");
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
            log.debug("Auto-scrape completed");
        }
    }

    @Transactional
    public Comic autoScrapeComic(Comic comic) {
        if (comic.getMetadataSourceId() != null) {
            log.debug("Comic '{}' already bound, skipping", comic.getTitle());
            return comic;
        }

        // 如果已有 MediaSeries 关联，复用其 bangumiId
        if (comic.getSeriesRef() != null && comic.getSeriesRef().getMetadataSourceId() != null) {
            log.info("Reusing bangumiId={} from MediaSeries '{}' for '{}'",
                    comic.getSeriesRef().getMetadataSourceId(), comic.getSeriesRef().getTitle(), comic.getTitle());
            return bindBangumi(comic.getId(), comic.getSeriesRef().getMetadataSourceId());
        }

        // 如果同系列已有绑定的书，复用其 bangumiId
        if (comic.getSeriesRef() != null) {
            List<Comic> siblings = repository.findBySeriesRef_Id(comic.getSeriesRef().getId());
            Integer inheritedBangumiId = siblings.stream()
                    .filter(c -> c.getMetadataSourceId() != null)
                    .map(Comic::getMetadataSourceId)
                    .findFirst()
                    .orElse(null);
            if (inheritedBangumiId != null) {
                log.info("Reusing bangumiId={} from series '{}' for '{}'",
                        inheritedBangumiId, comic.getSeries(), comic.getTitle());
                return bindBangumi(comic.getId(), inheritedBangumiId);
            }
        }

        String query = extractSeriesName(comic);
        if (query.isBlank()) {
            query = comic.getTitle();
        }

        List<BangumiService.SearchResult> bgmResults = searchFromBangumi(query);
        if (!bgmResults.isEmpty()) {
            BangumiService.SearchResult best = pickBestMatch(bgmResults, query);
            if (best != null) {
                return bindBangumi(comic.getId(), best.getId());
            }
        }

        log.info("No results for comic '{}' from Bangumi", query);
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

        String genres = BangumiService.extractGenresFromTags(detail.getTags());
        if (genres != null) {
            comic.setGenre(genres);
        }

        Integer localVolume = extractVolumeFromFileName(comic.getFileName());
        if (localVolume != null) {
            comic.setVolume(localVolume);
        }

        String newSeriesName = detail.getNameCn() != null ? detail.getNameCn() : detail.getName();
        if (newSeriesName != null && !newSeriesName.isBlank()) {
            newSeriesName = newSeriesName.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
            comic.setSeriesName(newSeriesName);
        }
    }

    private void downloadCoverFromBangumi(Comic comic, BangumiService.SubjectDetail detail) {
        if (comic.getFilePath() == null || comic.getFilePath().isBlank()) return;
        Path comicDir = Paths.get(comic.getFilePath()).getParent();
        if (comicDir == null) return;

        String localPath = bangumiService.downloadCover(detail, "bangumi_" + detail.getId(), comicDir);
        if (localPath != null) {
            comic.setCoverArtPath(localPath);
        }
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

    private String downloadMediaCharacterImage(MediaSeries ms, String imageUrl, String characterName) {
        if (imageUrl == null || imageUrl.isBlank()) return null;

        try {
            // 查找该系列下的任意一个漫画文件来确定目录
            List<Comic> comics = repository.findBySeriesRef_Id(ms.getId());
            if (comics.isEmpty()) {
                log.debug("No comics found for series '{}', cannot download character image", ms.getTitle());
                return null;
            }

            Path comicDir = Paths.get(comics.get(0).getFilePath()).getParent();
            if (comicDir == null) return null;

            Path actorsDir = comicDir.resolve("actors");
            Files.createDirectories(actorsDir);

            String safeName = characterName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (safeName.isBlank()) safeName = "character_" + System.currentTimeMillis();
            Path imagePath = actorsDir.resolve(safeName + ".jpg");

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
            } else {
                log.warn("Failed to download character image '{}': HTTP {}", characterName, response.getStatusCode());
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
        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
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

        // Match "标题 数字" at end (before extension), e.g. "魔女与佣兵 01"
        String baseName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;
        m = Pattern.compile("\\s+(\\d{1,3})$").matcher(baseName);
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

        // Strip trailing number (volume) preceded by space, e.g. "魔女与佣兵 01"
        cleaned = cleaned.replaceAll("\\s+\\d{1,3}$", "");

        cleaned = cleaned.replaceAll("\\.(?:cbz|cbr|zip|rar|epub)$", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned.isBlank() ? title : cleaned;
    }

    public String extractSeriesName(Comic comic) {
        // 优先从文件名提取系列名 —— 文件名是用户可控的，命名更一致
        String fileName = comic.getFileName();
        if (fileName != null && !fileName.isBlank()) {
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

        // 文件名不含中文字符，回退到标题
        String title = comic.getTitle();
        if (title != null && !title.isBlank()) {
            String fromTitle = cleanTitleForSearch(title);
            if (fromTitle != null && !fromTitle.isBlank()) {
                return fromTitle;
            }
        }
        return "Unknown";
    }

    /**
     * 从搜索结果中选取最佳匹配：优先标题完全匹配，其次包含匹配，最后回退到最热门
     */
    private BangumiService.SearchResult pickBestMatch(List<BangumiService.SearchResult> results, String seriesName) {
        if (results == null || results.isEmpty()) return null;

        // 1. 精确匹配 name_cn
        for (BangumiService.SearchResult r : results) {
            if (r.getNameCn() != null && r.getNameCn().equals(seriesName)) {
                log.info("Picked exact match (nameCn='{}' = '{}') id={}", r.getNameCn(), seriesName, r.getId());
                return r;
            }
        }

        // 2. 精确匹配 name（原始日文名）
        for (BangumiService.SearchResult r : results) {
            if (r.getName() != null && r.getName().equals(seriesName)) {
                log.info("Picked exact match (name='{}' = '{}') id={}", r.getName(), seriesName, r.getId());
                return r;
            }
        }

        // 3. 包含匹配 + 相似度检查
        double bestScore = 0;
        BangumiService.SearchResult bestResult = null;
        for (BangumiService.SearchResult r : results) {
            String target = r.getNameCn() != null ? r.getNameCn() : r.getName();
            if (target == null) continue;
            double score = calculateSimilarity(seriesName, target);
            if (score > bestScore) {
                bestScore = score;
                bestResult = r;
            }
        }

        if (bestResult != null && bestScore >= 0.6) {
            String matchedName = bestResult.getNameCn() != null ? bestResult.getNameCn() : bestResult.getName();
            log.info("Picked match (score={}): '{}' <-> '{}' id={}", String.format("%.2f", bestScore), seriesName, matchedName, bestResult.getId());
            return bestResult;
        }

        log.info("No confident match for '{}' (best score={}), skipping auto-scrape", seriesName, String.format("%.2f", bestScore));
        return null;
    }

    private static double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0;
        String a = s1.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff]", "");
        String b = s2.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff]", "");
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0;

        int maxLen = Math.max(a.length(), b.length());
        int distance = levenshteinDistance(a, b);
        return 1.0 - (double) distance / maxLen;
    }

    private static int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[s1.length()][s2.length()];
    }

    private MediaSeries findOrCreateSeries(String seriesName, String mediaType, BangumiService.SubjectDetail detail) {
        String cleanedName = seriesName.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
        MediaSeries ms = seriesRepo.findByTitleAndMediaTypeIgnoreCase(cleanedName, mediaType).orElse(null);
        if (ms != null) return ms;

        ms = new MediaSeries();
        ms.setTitle(cleanedName);
        ms.setMediaType(mediaType);
        ms.setMetadataSource("bangumi");
        ms.setMetadataSourceId(detail.getId());
        if (detail.getNameCn() != null && !detail.getNameCn().isBlank()) {
            ms.setOriginalTitle(detail.getName());
        }
        if (detail.getSummary() != null && !detail.getSummary().isBlank()) {
            ms.setDescription(detail.getSummary());
        }
        if (detail.getRating() != null && detail.getRating().getScore() != null) {
            ms.setRating(detail.getRating().getScore());
        }
        if (detail.getDate() != null && detail.getDate().length() >= 4) {
            try { ms.setYear(Integer.parseInt(detail.getDate().substring(0, 4))); } catch (NumberFormatException ignored) {}
        }
        // 从 infobox 提取 author/genre
        if (detail.getInfobox() != null) {
            for (var entry : detail.getInfobox()) {
                if ("作者".equals(entry.getKey()) || "作画".equals(entry.getKey())) {
                    if (ms.getAuthor() == null) ms.setAuthor(entry.getValue().toString());
                }
            }
        }
        String genres = BangumiService.extractGenresFromTags(detail.getTags());
        if (genres != null) ms.setGenre(genres);

        seriesRepo.save(ms);
        log.info("Created MediaSeries '{}' (id={})", cleanedName, ms.getId());
        return ms;
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

            Comic comic = repository.findById(comicId).orElse(null);
            MediaSeries ms = comic != null ? comic.getSeriesRef() : null;

            if (ms == null) {
                log.warn("No MediaSeries for comic id={}, cannot save characters", comicId);
                return;
            }

            mediaCharRepo.deleteBySeries_Id(ms.getId());
            for (BangumiService.Character bgmChar : characters) {
                MediaSeriesCharacter mc = createMediaCharacter(bgmChar, ms);
                if (mc != null) mediaCharRepo.save(mc);
            }
            log.info("Saved {} characters for series '{}' from Bangumi", characters.size(), ms.getTitle());
        } catch (Exception e) {
            log.warn("Failed to save Bangumi characters for comic id={}: {}", comicId, e.getMessage());
        }
    }

    private MediaSeriesCharacter createMediaCharacter(BangumiService.Character bgmChar, MediaSeries ms) {
        String chineseName = getChineseName(bgmChar);
        MediaSeriesCharacter mc = new MediaSeriesCharacter();
        mc.setSeries(ms);
        if (chineseName != null && !chineseName.isBlank()) {
            mc.setName(chineseName);
            mc.setOriginalName(bgmChar.getName());
        } else {
            mc.setName(bgmChar.getName());
            mc.setOriginalName(bgmChar.getName());
        }
        mc.setDescription(bgmChar.getSummary());
        mc.setRole(bgmChar.getRole());
        mc.setSourceCharacterId(bgmChar.getId());
        mc.setSource("bangumi");

        String imageUrl = bgmChar.getImageUrl();
        if (imageUrl != null && !imageUrl.isBlank()) {
            mc.setImageUrl(imageUrl);
            // 下载角色图片到本地
            String localPath = downloadMediaCharacterImage(ms, imageUrl, mc.getName());
            if (localPath != null) mc.setImagePath(localPath);
        }
        return mc;
    }

    private String getChineseName(BangumiService.Character bgmChar) {
        if (bgmChar.getNameCn() != null && !bgmChar.getNameCn().isBlank()) {
            return bgmChar.getNameCn();
        }
        try {
            BangumiService.CharacterDetail detail = bangumiService.getCharacterDetail(bgmChar.getId());
            if (detail != null && detail.getChineseName() != null) {
                return detail.getChineseName();
            }
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Failed to get character detail: {}", e.getMessage());
        }
        return null;
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
}
