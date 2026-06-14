package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.dto.TmdbTvDetail;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.repository.VideoSeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SeriesService {

    private final VideoSeriesRepository seriesRepository;
    private final VideoRepository videoRepository;
    private final TmdbService tmdbService;
    private final NfoService nfoService;

    private static final Pattern EPISODE_PATTERN = Pattern.compile(
            "(?:S\\d{1,2})?E(\\d{1,4})|(?i:EP?)(\\d{1,4})|[＃#](\\d{1,4})|[\\s._\\-　](\\d{1,4})$|(\\d{1,4})$", Pattern.CASE_INSENSITIVE
    );

    public Optional<VideoSeries> findSeriesByTitle(String title) {
        return seriesRepository.findByTitle(title);
    }

    public VideoSeries createSeries(String title) {
        VideoSeries series = new VideoSeries();
        series.setTitle(title);
        return seriesRepository.save(series);
    }

    public VideoSeries getOrCreateSeries(String title) {
        return findSeriesByTitle(title).orElseGet(() -> createSeries(title));
    }

    public VideoSeries bindTmdbToSeries(Long seriesId, Long tmdbId) {
        VideoSeries series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new RuntimeException("Series not found: " + seriesId));

        TmdbTvDetail detail = tmdbService.getTvDetail(tmdbId);
        if (detail == null) {
            throw new RuntimeException("TMDB TV not found: " + tmdbId);
        }

        series.setTmdbId(tmdbId);
        series.setTitle(detail.getName());
        series.setOriginalTitle(detail.getOriginalName());
        series.setOverview(detail.getOverview());
        series.setYear(detail.getYear());
        series.setMediaType("tv");
        series.setRating(detail.getVoteAverage());
        series.setPosterUrl(tmdbService.getPosterUrl(detail.getPosterPath()));
        series.setBackdropUrl(tmdbService.getBackdropUrl(detail.getBackdropPath()));
        series.setMetadataSource("tmdb");
        series.setTotalEpisodes(detail.getNumberOfEpisodes());

        return seriesRepository.save(series);
    }

    public VideoSeries getOrCreateAndBindSeries(String title, Long tmdbId) {
        Optional<VideoSeries> existing = seriesRepository.findByTmdbId(tmdbId);
        if (existing.isPresent()) {
            return existing.get();
        }
        VideoSeries series = createSeries(title);
        return bindTmdbToSeries(series.getId(), tmdbId);
    }

    public List<VideoSeries> getAllSeries() {
        return seriesRepository.findAll();
    }

    public Optional<VideoSeries> getSeriesById(Long id) {
        return seriesRepository.findById(id);
    }

    public void removeVideoFromSeries(Video video) {
        if (video.getSeries() != null) {
            VideoSeries series = video.getSeries();
            video.setSeries(null);
            video.setIsSeries(false);

            seriesRepository.save(series);
        }
    }

    public Map<String, List<Video>> groupVideosBySeries(List<Video> videos) {
        Map<String, List<Video>> grouped = new LinkedHashMap<>();

        for (Video video : videos) {
            String cleanedTitle = cleanTitle(video.getTitle());
            grouped.computeIfAbsent(cleanedTitle, k -> new ArrayList<>()).add(video);
        }

        return grouped.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    public String cleanTitle(String title) {
        if (title == null || title.isBlank()) {
            return "Unknown";
        }

        String cleaned = title;

        // 先处理明确的标记格式：S01E01, EP01, ＃1 等
        cleaned = cleaned.replaceAll("(?i)S\\d{1,2}E\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("(?i)EP?\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("[＃#]\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("[\\s._\\-]*(?:CD|DVD|BD|DISK?|PART|EP?|CHAPTER)[\\s._\\-#＃]*\\d{1,4}[\\s._\\-]*$", "");

        // 处理末尾数字（有分隔符或无分隔符）
        cleaned = cleaned.replaceAll("[\\s._\\-＃#EPep]+\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("\\d{1,4}$", "");

        cleaned = cleaned.replaceAll("[\\[\\]【】()（）]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned.isBlank() ? title : cleaned;
    }

    public Integer extractEpisodeNumber(String fileName) {
        Matcher matcher = EPISODE_PATTERN.matcher(fileName);
        if (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String val = matcher.group(i);
                if (val != null) {
                    return Integer.parseInt(val);
                }
            }
        }
        return null;
    }

    public void assignVideoToSeries(Video video, VideoSeries series) {
        video.setSeries(series);
        video.setIsSeries(true);
        if (video.getSeasonNumber() == null) {
            video.setSeasonNumber(series.getSeasonNumber());
        }
        if (video.getEpisodeNumber() == null) {
            Integer episodeNum = extractEpisodeNumber(video.getFileName());
            video.setEpisodeNumber(episodeNum != null ? episodeNum : (int) getEpisodeCount(series.getId()) + 1);
        }
    }

    public long getEpisodeCount(Long seriesId) {
        return videoRepository.countBySeriesId(seriesId);
    }

    public void saveSeries(VideoSeries series) {
        seriesRepository.save(series);
    }

    public int cleanupDuplicateSeries() {
        List<Long> duplicateTmdbIds = seriesRepository.findDuplicateTmdbIds();
        int merged = 0;

        for (Long tmdbId : duplicateTmdbIds) {
            List<VideoSeries> duplicates = seriesRepository.findByTmdbId(tmdbId)
                    .stream()
                    .filter(s -> s.getTmdbId() != null && s.getTmdbId().equals(tmdbId))
                    .toList();

            if (duplicates.size() <= 1) continue;

            VideoSeries primary = duplicates.get(0);
            List<VideoSeries> toDelete = duplicates.subList(1, duplicates.size());

            for (VideoSeries duplicate : toDelete) {
                List<Video> videos = videoRepository.findBySeries(duplicate);
                for (Video video : videos) {
                    video.setSeries(primary);
                    videoRepository.save(video);
                }
                seriesRepository.delete(duplicate);
                merged++;
                log.info("Merged duplicate series: {} (tmdbId={}) into primary: {}",
                        duplicate.getTitle(), tmdbId, primary.getTitle());
            }
        }

        log.info("Cleanup completed: merged {} duplicate series", merged);
        return merged;
    }
}
