package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.MediaSeries;
import com.fryfrog.hub.common.model.MediaSeriesCharacter;
import com.fryfrog.hub.common.repository.MediaSeriesCharacterRepository;
import com.fryfrog.hub.common.repository.MediaSeriesRepository;
import com.fryfrog.hub.common.service.BangumiService;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class EbookBangumiScrapeService {

    private final BangumiService bangumiService;
    private final EbookRepository repository;
    private final EbookService ebookService;
    private final RestTemplate scraperRestTemplate;
    private final MediaSeriesRepository seriesRepo;
    private final MediaSeriesCharacterRepository mediaCharRepo;

    public EbookBangumiScrapeService(BangumiService bangumiService, EbookRepository repository,
                                     EbookService ebookService,
                                     @Qualifier("scraperRestTemplate") RestTemplate scraperRestTemplate,
                                     MediaSeriesRepository seriesRepo,
                                     MediaSeriesCharacterRepository mediaCharRepo) {
        this.bangumiService = bangumiService;
        this.repository = repository;
        this.ebookService = ebookService;
        this.scraperRestTemplate = scraperRestTemplate;
        this.seriesRepo = seriesRepo;
        this.mediaCharRepo = mediaCharRepo;
    }

    public List<BangumiService.SearchResult> searchFromBangumi(String query, String subType) {
        return BangumiService.sortByPopularity(bangumiService.searchBooks(query, subType));
    }

    @Transactional
    public Ebook bindBangumi(Long ebookId, Integer bangumiId) {
        Ebook ebook = repository.findById(ebookId)
                .orElseThrow(() -> new ResourceNotFoundException("Ebook", "id", ebookId));
        BangumiService.SubjectDetail detail = bangumiService.getSubjectDetail(bangumiId);
        if (detail == null) {
            throw new ResourceNotFoundException("Bangumi Subject", "id", bangumiId);
        }

        updateEbookFromBangumiDetail(ebook, detail);
        ebook.setBangumiId(bangumiId);

        // 查找或创建 MediaSeries
        String seriesName = ebook.getSeries();
        if (seriesName != null && !seriesName.isBlank()) {
            MediaSeries ms = findOrCreateSeries(seriesName, "ebook", detail);
            ebook.setSeriesRef(ms);
        }

        // 先同步兄弟文件的 series（在移动文件之前，这样能找到同目录下的兄弟）
        syncSiblingSeriesNames(ebook);

        renameEbookFile(ebook);
        moveEbookToSeriesFolder(ebook);

        Ebook saved = repository.save(ebook);
        log.info("Bound ebook '{}' to Bangumi subject id={}", saved.getTitle(), bangumiId);

        downloadCoverFromBangumi(saved, detail);

        // 下载卷级封面（使用卷级 Bangumi 条目的封面）
        if (saved.getVolume() != null) {
            Map<Integer, BangumiService.RelatedSubject> volumeMap = bangumiService.buildVolumeSubjectMap(bangumiId);
            if (volumeMap.containsKey(saved.getVolume())) {
                BangumiService.RelatedSubject volSub = volumeMap.get(saved.getVolume());
                String volCoverUrl = volSub.getCoverUrl();
                if (volCoverUrl != null) {
                    String volCoverPrefix = "bangumi_vol_" + bangumiId + "_" + saved.getVolume();
                    Path volDir = Paths.get(saved.getFilePath()).getParent();
                    if (volDir != null) {
                        String volLocalPath = bangumiService.downloadCover(volCoverUrl, volCoverPrefix, volDir);
                        if (volLocalPath != null && !volLocalPath.isBlank()) {
                            saved.setCoverArtPath(volLocalPath);
                        }
                    }
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

        MediaSeries ms = seriesRepo.findByTitleAndMediaTypeIgnoreCase(series, "ebook")
                .or(() -> seriesRepo.findByTitleAndMediaTypeIgnoreCase(series, "both"))
                .orElse(null);
        if (ms == null) {
            log.warn("No MediaSeries found for '{}'", series);
            return 0;
        }
        List<Ebook> ebooks = repository.findBySeriesRef_Id(ms.getId());
        if (ebooks.isEmpty()) {
            log.warn("No ebooks found for series '{}'", series);
            return 0;
        }

        // 找到已有 bangumiId 的作为主条目，或者用第一本搜索
        Ebook mainBook = ebooks.stream()
                .filter(e -> e.getBangumiId() != null)
                .findFirst()
                .orElse(ebooks.get(0));

        Integer subjectId = mainBook.getBangumiId();
        if (subjectId == null) {
            // 没有绑定过，需要先搜索
            log.info("Series '{}' has no bangumi binding, searching...", series);
            List<BangumiService.SearchResult> results = bangumiService.searchBooks(series, "novel");
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

        int updated = 0;
        for (Ebook ebook : ebooks) {
            try {
                // 更新系列级元数据
                String seriesName = seriesDetail.getNameCn() != null ? seriesDetail.getNameCn() : seriesDetail.getName();
                if (seriesName != null && !seriesName.isBlank()) {
                    ebook.setSeriesName(seriesName.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim());
                }
                ebook.setBangumiId(subjectId);

                // 匹配卷级封面和简介
                Integer vol = extractVolume(ebook.getFileName());
                if (vol != null && volumeMap.containsKey(vol)) {
                    BangumiService.RelatedSubject volSub = volumeMap.get(vol);
                    BangumiService.SubjectDetail volDetail = bangumiService.getSubjectDetail(volSub.getId());
                    if (volDetail != null) {
                        updateEbookFromBangumiDetail(ebook, volDetail);
                        ebook.setBangumiId(volSub.getId());

                        // 下载卷级封面
                        String volCoverPrefix = "bangumi_vol_" + subjectId + "_" + vol;
                        Path volDir = Paths.get(ebook.getFilePath()).getParent();
                        if (volDir != null) {
                            String coverUrl = volSub.getCoverUrl();
                            String localPath = bangumiService.downloadCover(coverUrl, volCoverPrefix, volDir);
                            if (localPath != null) {
                                ebook.setCoverArtPath(localPath);
                            }
                        }
                    }
                } else {
                    // 没有匹配的卷，使用系列级信息
                    updateEbookFromBangumiDetail(ebook, seriesDetail);

                    // 下载系列封面作为该本封面
                    if (ebook.getCoverArtPath() == null) {
                        String coverPrefix = "bangumi_" + subjectId;
                        Path volDir = Paths.get(ebook.getFilePath()).getParent();
                        if (volDir != null) {
                            String localPath = bangumiService.downloadCover(seriesDetail, coverPrefix, volDir);
                            if (localPath != null) {
                                ebook.setCoverArtPath(localPath);
                            }
                        }
                    }
                }

                repository.save(ebook);
                updated++;
                log.info("Rescraped '{}' (vol={})", ebook.getTitle(), vol);
            } catch (Exception e) {
                log.warn("Failed to rescrape '{}': {}", ebook.getTitle(), e.getMessage());
            }
        }

        log.info("Rescrape series '{}': {}/{} updated", series, updated, ebooks.size());
        return updated;
    }

    // ==================== 元数据更新 ====================

    private void updateEbookFromBangumiDetail(Ebook ebook, BangumiService.SubjectDetail detail) {
        if (detail.getNameCn() != null && !detail.getNameCn().isBlank()) {
            ebook.setTitle(detail.getNameCn());
        } else if (detail.getName() != null) {
            ebook.setTitle(detail.getName());
        }

        if (detail.getSummary() != null) {
            String desc = detail.getSummary().trim();
            if (!desc.isBlank()) {
                ebook.setDescription(desc);
            }
        }

        if (detail.getDate() != null && detail.getDate().length() >= 4) {
            try {
                ebook.setYear(Integer.parseInt(detail.getDate().substring(0, 4)));
            } catch (NumberFormatException ignored) {}
        }

        if (detail.getInfobox() != null) {
            for (BangumiService.SubjectDetail.InfoboxEntry entry : detail.getInfobox()) {
                String key = entry.getKey();
                String val = entry.getValue() != null ? entry.getValue().toString() : null;
                if (val == null || val.isBlank()) continue;

                if ("作者".equals(key) && (ebook.getAuthor() == null || ebook.getAuthor().isBlank())) {
                    ebook.setAuthor(val);
                } else if ("出版社".equals(key) && (ebook.getPublisher() == null || ebook.getPublisher().isBlank())) {
                    ebook.setPublisher(val);
                } else if ("ISBN".equals(key) && (ebook.getIsbn() == null || ebook.getIsbn().isBlank())) {
                    ebook.setIsbn(val.replace("-", ""));
                }
            }
        }

        String genres = BangumiService.extractGenresFromTags(detail.getTags());
        if (genres != null) {
            ebook.setGenre(genres);
        }

        Integer volume = extractVolume(ebook.getFileName());
        if (volume != null) {
            log.info("Setting volume={} for '{}' (fileName={})", volume, ebook.getTitle(), ebook.getFileName());
            ebook.setVolume(volume);
            // 拼接卷号到标题：系列名 + Vol.XX
            String baseTitle = ebook.getTitle();
            if (baseTitle != null && !baseTitle.isBlank() && !baseTitle.contains("Vol")) {
                ebook.setTitle(baseTitle + " Vol." + String.format("%02d", volume));
            }
        }

        String seriesName = detail.getNameCn() != null ? detail.getNameCn() : detail.getName();
        if (seriesName != null && !seriesName.isBlank()) {
            // 清洗特殊字符，确保文件夹和文件名安全
            seriesName = seriesName.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
            ebook.setSeriesName(seriesName);
        }
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
        if (detail.getInfobox() != null) {
            for (var entry : detail.getInfobox()) {
                if ("作者".equals(entry.getKey())) {
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

    // ==================== 封面下载 ====================

    private void downloadCoverFromBangumi(Ebook ebook, BangumiService.SubjectDetail detail) {
        if (ebook.getFilePath() == null || ebook.getFilePath().isBlank()) return;
        Path ebookDir = Paths.get(ebook.getFilePath()).getParent();
        if (ebookDir == null) return;

        String localPath = bangumiService.downloadCover(detail, "bangumi_" + ebook.getBangumiId(), ebookDir);
        if (localPath != null) {
            ebook.setCoverArtPath(localPath);
            return;
        }

        // 兜底：查找目录中已有的封面文件
        findExistingCover(ebook, ebookDir);
    }

    private void findExistingCover(Ebook ebook, Path dir) {
        if (ebook.getCoverArtPath() != null && new java.io.File(ebook.getCoverArtPath()).exists()) return;
        try (var stream = java.nio.file.Files.list(dir)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
                    })
                    .filter(p -> p.getFileName().toString().startsWith("bangumi_"))
                    .findFirst()
                    .ifPresent(p -> ebook.setCoverArtPath(p.toAbsolutePath().toString()));
        } catch (Exception ignored) {}
    }

    // ==================== 角色保存 ====================

    @Transactional
    public void saveBangumiCharacters(Long ebookId, Integer subjectId) {
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

            Ebook ebook = repository.findById(ebookId).orElse(null);
            MediaSeries ms = ebook != null ? ebook.getSeriesRef() : null;

            if (ms == null) {
                log.warn("No MediaSeries for ebook id={}, cannot save characters", ebookId);
                return;
            }

            mediaCharRepo.deleteBySeries_Id(ms.getId());
            for (BangumiService.Character bgmChar : characters) {
                MediaSeriesCharacter mc = createMediaCharacter(bgmChar, ms);
                if (mc != null) mediaCharRepo.save(mc);
            }
            log.debug("Saved {} characters for series '{}' from Bangumi", characters.size(), ms.getTitle());
        } catch (Exception e) {
            log.warn("Failed to save Bangumi characters for ebook id={}: {}", ebookId, e.getMessage());
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

    private String downloadMediaCharacterImage(MediaSeries ms, String imageUrl, String characterName) {
        if (imageUrl == null || imageUrl.isBlank()) return null;

        try {
            List<Ebook> ebooks = repository.findBySeriesRef_Id(ms.getId());
            if (ebooks.isEmpty()) {
                log.debug("No ebooks found for series '{}', cannot download character image", ms.getTitle());
                return null;
            }

            Path ebookDir = Paths.get(ebooks.get(0).getFilePath()).getParent();
            if (ebookDir == null) return null;

            Path actorsDir = ebookDir.resolve("actors");
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
                log.debug("Downloaded character image '{}' to {}", characterName, imagePath);
                return imagePath.toAbsolutePath().toString();
            } else {
                log.warn("Failed to download character image '{}': HTTP {}", characterName, response.getStatusCode());
            }
        } catch (Exception e) {
            log.warn("Failed to download character image '{}': {}", characterName, e.getMessage());
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

    // ==================== 文件重命名 ====================

    private void renameEbookFile(Ebook ebook) {
        if (ebook.getSeries() == null || ebook.getSeries().isBlank()) return;
        if (ebook.getFilePath() == null || ebook.getFilePath().isBlank()) return;

        Path currentPath = Paths.get(ebook.getFilePath());
        if (!Files.exists(currentPath)) return;

        String oldFileName = ebook.getFileName();
        String ext = oldFileName.contains(".") ? oldFileName.substring(oldFileName.lastIndexOf('.')) : "";
        Integer vol = ebook.getVolume();
        String volPart = vol != null ? " Vol." + vol : "";

        // 清洗文件名中的特殊字符
        String cleanSeries = ebook.getSeries().replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
        String newFileName = cleanSeries + volPart + ext;
        if (oldFileName.equals(newFileName)) return;

        Path newPath = currentPath.getParent().resolve(newFileName);
        if (Files.exists(newPath) && !currentPath.equals(newPath)) {
            log.warn("Target file already exists, skipping rename: {}", newPath);
            return;
        }

        try {
            Files.move(currentPath, newPath, StandardCopyOption.REPLACE_EXISTING);
            ebook.setFileName(newFileName);
            ebook.setFilePath(newPath.toAbsolutePath().toString());
            log.info("Renamed ebook '{}' -> '{}'", oldFileName, newFileName);
        } catch (IOException e) {
            log.warn("Failed to rename ebook '{}' -> '{}': {}", oldFileName, newFileName, e.getMessage());
        }
    }

    // ==================== 同步兄弟文件系列名 ====================

    private void syncSiblingSeriesNames(Ebook source) {
        if (source.getSeries() == null || source.getSeries().isBlank()) return;
        if (source.getFilePath() == null || source.getFilePath().isBlank()) return;

        Path sourceDir = Paths.get(source.getFilePath()).getParent();
        if (sourceDir == null) return;

        // 找同目录下未绑定的文件
        List<Ebook> unbound = repository.findAll().stream()
                .filter(e -> !e.getId().equals(source.getId()))
                .filter(e -> e.getBangumiId() == null && e.getOpenLibraryId() == null)
                .filter(e -> {
                    if (e.getFilePath() == null) return false;
                    Path eDir = Paths.get(e.getFilePath()).getParent();
                    return sourceDir.equals(eDir);
                })
                .toList();

        for (Ebook sibling : unbound) {
            String sourceSeries = source.getSeries();
            if (sourceSeries == null || sourceSeries.isBlank()) continue;
            if (sibling.getSeries() == null || !sibling.getSeries().equals(sourceSeries)) {
                sibling.setSeriesName(sourceSeries);
                sibling.setAuthor(source.getAuthor());
                sibling.setGenre(source.getGenre());
                repository.save(sibling);
                log.info("Synced series '{}' to sibling '{}'", sourceSeries, sibling.getFileName());
            }
        }
    }

    // ==================== 文件移动 ====================

    private void moveEbookToSeriesFolder(Ebook ebook) {
        if (ebook.getSeries() == null || ebook.getSeries().isBlank()) return;
        if (ebook.getFilePath() == null || ebook.getFilePath().isBlank()) return;

        try {
            Path currentPath = Paths.get(ebook.getFilePath());
            if (!Files.exists(currentPath)) {
                log.warn("Ebook file not found: {}", ebook.getFilePath());
                return;
            }

            Path currentDir = currentPath.getParent();
            if (currentDir == null) return;

            Path rootDir = findRootDir(currentDir);
            if (rootDir == null) return;

            String seriesName = sanitizeFolderName(ebook.getSeries());
            Path seriesDir = rootDir.resolve(seriesName);
            Files.createDirectories(seriesDir);

            Path targetPath = seriesDir.resolve(currentPath.getFileName());
            if (currentPath.equals(targetPath)) {
                log.debug("Ebook '{}' already in series folder", ebook.getTitle());
                return;
            }

            // 目标路径已有文件，检查是否是自己（数据库 filePath 与实际不一致）
            if (Files.exists(targetPath)) {
                String targetAbsPath = targetPath.toAbsolutePath().toString();
                if (targetAbsPath.equals(ebook.getFilePath())) {
                    // 数据库记录的 filePath 指向旧位置，但文件已在目标位置，更新数据库即可
                    ebook.setFilePath(targetAbsPath);
                    log.debug("Ebook '{}' file already in target, updated db path", ebook.getTitle());
                    return;
                }
                log.warn("Target file already exists, skipping: {}", targetPath);
                return;
            }

            Files.move(currentPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            ebook.setFilePath(targetPath.toAbsolutePath().toString());

            updateCoverPaths(ebook, currentDir, seriesDir);
            moveCharacterImages(currentDir, seriesDir);

            cleanupEmptyFolders(currentDir, rootDir);

            log.debug("Moved ebook '{}' to series folder: {}", ebook.getTitle(), seriesDir);
        } catch (IOException e) {
            log.error("Failed to move ebook '{}': {}", ebook.getTitle(), e.getMessage());
        }
    }

    private void updateCoverPaths(Ebook ebook, Path oldDir, Path newDir) {
        if (ebook.getCoverArtPath() != null && !ebook.getCoverArtPath().isBlank()) {
            Path oldCover = Paths.get(ebook.getCoverArtPath());
            if (oldCover.startsWith(oldDir)) {
                Path newCover = newDir.resolve(oldCover.getFileName());
                if (Files.exists(oldCover) && !oldCover.equals(newCover)) {
                    try {
                        Files.createDirectories(newCover.getParent());
                        Files.move(oldCover, newCover, StandardCopyOption.REPLACE_EXISTING);
                        ebook.setCoverArtPath(newCover.toAbsolutePath().toString());
                    } catch (IOException e) {
                        log.warn("Failed to move cover: {}", e.getMessage());
                    }
                }
            }
        }

        String ebookBaseName = ebook.getFileName().replaceAll("\\.[^.]+$", "");
        try (var stream = Files.list(oldDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith("_cover.jpg") && name.startsWith(ebookBaseName);
                    })
                    .forEach(oldCover -> {
                        try {
                            Path newCover = newDir.resolve(oldCover.getFileName());
                            if (!Files.exists(newCover)) {
                                Files.move(oldCover, newCover);
                            }
                            if (ebook.getCoverArtPath() == null || !ebook.getCoverArtPath().isBlank()
                                    && Paths.get(ebook.getCoverArtPath()).equals(oldCover)) {
                                ebook.setCoverArtPath(newCover.toAbsolutePath().toString());
                            }
                        } catch (IOException e) {
                            log.warn("Failed to move extracted cover {}: {}", oldCover.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to scan old dir for covers: {}", e.getMessage());
        }

        if (ebook.getCoverArtPath() != null && !Files.exists(Paths.get(ebook.getCoverArtPath()))) {
            try (var stream = Files.list(newDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith(ebookBaseName)
                                && p.getFileName().toString().endsWith("_cover.jpg"))
                        .findFirst()
                        .ifPresent(p -> ebook.setCoverArtPath(p.toAbsolutePath().toString()));
            } catch (IOException ignored) {}
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
        for (String rootPath : ebookService.getRootPaths()) {
            Path root = Paths.get(rootPath).toAbsolutePath().normalize();
            if (currentDir.toAbsolutePath().normalize().startsWith(root)) {
                return root;
            }
        }
        return currentDir.getParent();
    }

    // ==================== 工具方法 ====================

    private static final Pattern VOLUME_PATTERN = Pattern.compile(
            "(?i)(?:卷|Vol\\.?|Volume|#)[\\s]*(\\d+)|[\\s]*(\\d+)\\s*(?:卷|卷目)"
    );

    private Integer extractVolume(String fileName) {
        if (fileName == null) return null;
        Matcher m = VOLUME_PATTERN.matcher(fileName);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) return Integer.parseInt(m.group(i));
            }
        }
        return null;
    }
}
