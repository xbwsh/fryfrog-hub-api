package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.service.BangumiService;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import lombok.RequiredArgsConstructor;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookBangumiScrapeService {

    private final BangumiService bangumiService;
    private final EbookRepository repository;
    private final RestTemplate scraperRestTemplate;

    public List<BangumiService.SearchResult> searchFromBangumi(String query, String subType) {
        List<BangumiService.SearchResult> results = new java.util.ArrayList<>(bangumiService.searchBooks(query, subType));
        results.sort(Comparator.<BangumiService.SearchResult>comparingInt(r -> {
            BangumiService.SearchResult.Rating rating = r.getRating();
            return rating != null && rating.getTotal() != null ? rating.getTotal() : 0;
        }).reversed());
        return results;
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

        Ebook saved = repository.save(ebook);
        downloadCoverFromBangumi(saved, detail, "bangumi_" + bangumiId);
        log.info("Bound ebook '{}' to Bangumi subject id={}", saved.getTitle(), bangumiId);

        // 绑定完成后，将元数据传播到同系列其他单行本
        propagateSeriesMetadata(saved, bangumiId);

        return saved;
    }

    private void propagateSeriesMetadata(Ebook source, Integer subjectId) {
        if (source.getSeries() == null || source.getSeries().isBlank()) {
            log.debug("No series name, skipping propagation");
            return;
        }

        // 获取 Bangumi 关联条目，按卷号映射
        Map<Integer, BangumiService.RelatedSubject> volumeMap = buildVolumeMap(subjectId);
        if (volumeMap.isEmpty()) {
            log.debug("No volume subjects found for Bangumi id={}, skipping propagation", subjectId);
            return;
        }

        // 查找同系列其他电子书（未绑定的）
        List<Ebook> siblings = repository.findBySeriesIgnoreCase(source.getSeries());
        for (Ebook sibling : siblings) {
            if (sibling.getId().equals(source.getId())) continue;
            if (sibling.getBangumiId() != null) continue;

            Integer vol = extractVolume(sibling.getFileName());
            if (vol == null || !volumeMap.containsKey(vol)) {
                log.debug("No volume match for '{}', setting basic series metadata", sibling.getFileName());
                sibling.setSeries(source.getSeries());
                sibling.setAuthor(source.getAuthor());
                sibling.setGenre(source.getGenre());
                repository.save(sibling);
                continue;
            }

            // 有匹配的卷号，获取该卷详情
            BangumiService.RelatedSubject volSub = volumeMap.get(vol);
            BangumiService.SubjectDetail volDetail = bangumiService.getSubjectDetail(volSub.getId());
            if (volDetail == null) {
                log.debug("Could not fetch detail for volume {} (id={})", vol, volSub.getId());
                continue;
            }

            updateEbookFromBangumiDetail(sibling, volDetail);
            sibling.setBangumiId(volSub.getId());
            sibling.setVolume(vol);
            Ebook saved = repository.save(sibling);
            downloadCoverFromBangumi(saved, volDetail, "bangumi_vol_" + subjectId + "_" + vol);
            log.info("Bound sibling '{}' -> Bangumi vol.{} (id={})", saved.getTitle(), vol, volSub.getId());
        }
    }

    private Map<Integer, BangumiService.RelatedSubject> buildVolumeMap(Integer subjectId) {
        Map<Integer, BangumiService.RelatedSubject> map = new HashMap<>();
        List<BangumiService.RelatedSubject> related = bangumiService.getRelatedSubjects(subjectId);
        for (BangumiService.RelatedSubject sub : related) {
            if (sub.getType() == null || sub.getType() != 1) continue;
            String name = sub.getName();
            if (name == null) continue;
            Integer vol = extractVolumeFromRelatedName(name);
            if (vol != null) {
                map.put(vol, sub);
            }
        }
        log.info("Found {} volume subjects from related subjects for Bangumi id={}", map.size(), subjectId);
        return map;
    }

    private Integer extractVolumeFromRelatedName(String name) {
        Matcher m = Pattern.compile("\\((\\d+)\\)").matcher(name);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = Pattern.compile("(?i)vol\\.?\\s*(\\d+)").matcher(name);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = Pattern.compile("第\\s*(\\d+)\\s*卷").matcher(name);
        if (m.find()) return Integer.parseInt(m.group(1));
        return null;
    }

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

        if (detail.getTags() != null && !detail.getTags().isEmpty()) {
            String genres = detail.getTags().stream()
                    .map(BangumiService.SearchResult.Tag::getName)
                    .filter(Objects::nonNull)
                    .limit(5)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(null);
            ebook.setGenre(genres);
        }

        Integer volume = extractVolume(ebook.getFileName());
        if (volume != null) {
            ebook.setVolume(volume);
        }

        String seriesName = detail.getNameCn() != null ? detail.getNameCn() : detail.getName();
        if (seriesName != null && !seriesName.isBlank()) {
            ebook.setSeries(seriesName);
        }
    }

    private void downloadCoverFromBangumi(Ebook ebook, BangumiService.SubjectDetail detail, String prefix) {
        String coverUrl = null;
        if (detail.getImages() != null) {
            coverUrl = detail.getImages().getLarge();
            if (coverUrl == null || coverUrl.isBlank()) {
                coverUrl = detail.getImages().getCommon();
            }
        }
        if (coverUrl == null || coverUrl.isBlank()) return;
        if (ebook.getFilePath() == null) return;

        try {
            Path ebookDir = Paths.get(ebook.getFilePath()).getParent();
            if (ebookDir == null) return;
            Files.createDirectories(ebookDir);

            Path coverPath = ebookDir.resolve(prefix + "_cover.jpg");
            if (Files.exists(coverPath)) {
                ebook.setCoverArtPath(coverPath.toAbsolutePath().toString());
                return;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "FryfrogHub/0.1.0");
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = scraperRestTemplate.exchange(
                    coverUrl, HttpMethod.GET, request, byte[].class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Files.write(coverPath, response.getBody());
                ebook.setCoverArtPath(coverPath.toAbsolutePath().toString());
                log.info("Downloaded Bangumi cover for '{}' to {}", ebook.getTitle(), coverPath);
            }
        } catch (IOException e) {
            log.warn("Failed to download Bangumi cover for '{}': {}", ebook.getTitle(), e.getMessage());
        }
    }

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
