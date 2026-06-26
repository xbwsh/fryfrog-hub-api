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
        series.setNumberOfSeasons(detail.getNumberOfSeasons());
        series.setStatus(detail.getStatus());

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

            videoRepository.save(video);

            if (videoRepository.countBySeriesId(series.getId()) == 0) {
                log.info("Removing empty series: {} (id={})", series.getTitle(), series.getId());
                seriesRepository.delete(series);
            }
        }
    }

    @Transactional
    public void unbindSeriesTmdb(Long seriesId) {
        VideoSeries series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new RuntimeException("Series not found: " + seriesId));

        log.info("Unbinding TMDB from series: {} (tmdbId={})", series.getTitle(), series.getTmdbId());

        series.setTmdbId(null);
        series.setOriginalTitle(null);
        series.setOverview(null);
        series.setImdbId(null);
        series.setRating(null);
        series.setPosterUrl(null);
        series.setBackdropUrl(null);
        series.setMetadataSource(null);
        series.setStatus(null);
        series.setNumberOfSeasons(null);

        seriesRepository.save(series);
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

        cleaned = cleaned.replaceAll("\\[.*?\\]", " ");
        cleaned = cleaned.replaceAll("【.*?】", " ");
        cleaned = cleaned.replaceAll("\\(.*?\\)", " ");
        cleaned = cleaned.replaceAll("（.*?）", " ");

        cleaned = cleaned.replaceAll("(?i)\\bH\\.?264\\b|\\bH\\.?265\\b|\\bDD[P+]\\s*\\.?\\s*\\d+(?:\\.\\d+)*\\b|\\bDTS(?:\\s*-?\\s*HD)?(?:\\s*\\.?\\s*(?:MA|ES|RA))?(?:\\s*\\.?\\s*\\d+(?:\\.\\d+)*)?\\b|\\bAC\\s*\\.?\\s*3\\b|\\bE\\s*-?\\s*AC\\s*-?\\s*3\\b|\\bMPEG\\s*-?\\s*[24]\\b|\\bFLAC\\s*\\.?\\s*\\d+(?:\\.\\d+)*\\b", " ");
        cleaned = cleaned.replaceAll("[._]", " ");

        cleaned = cleaned.replaceAll("(?i)\\bS\\d{1,2}\\s*E\\d{1,4}\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(?:2160p|1080p|720p|480p|4K|UHD|FHD)\\b|\\b(?:BluRay|BDRip|BRRip|WEB-?DL|WEB-?Rip|HDRip|DVDRip|HDTV|TVRip|CamRip|TS|TC|SCR|R5)\\b|\\b(?:x264|x265|HEVC|AVC|AV1)\\b|\\b(?:AAC|FLAC|DTS(?:MA|HD)?|AC3|EAC3|DDP?\\d|Atmos|TrueHD|MP3|OGG|Opus)\\b|\\bHDR(?:10?)?\\b|\\bDoVi\\b|\\bDV\\b|\\bHLG\\b|\\b(?:10-?bit|8-?bit)\\b|\\bREMUX\\b|\\bBlu-?ray\\b|\\b(?:AVS|FRDS|Ma10p|Ma10s|NCOP|NCED)\\b|\\b(?:Baha|ADBA|Bilibili|ABEMA|Crunchyroll|Funimation|Netflix|Disney\\+?|Amazon|Hulu|Hi10|Nazzy|FGT|SPARKS|SHAFT)\\b|\\b(?:ASS|SSA|SRT|BIG5|GB2312|UTF-?8|EUC-?JP|Shift-?JIS)\\b|\\b(?:PART|CHAPTER|CD|DVD|BD|DISC?)\\b", " ");

        cleaned = cleaned.replaceAll("(?i)第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*(?:季|部|期)", " ");
        cleaned = cleaned.replaceAll("(?i)第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*(?:话|集|回|篇|章)", " ");

        cleaned = cleaned.replaceAll("(?i)\\bSeason\\s*\\d+\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\bS\\d{1,2}\\b", " ");

        cleaned = cleaned.replaceAll("(?i)\\bE(?:p(?:isode)?)?\\s*\\d{1,4}\\b", " ");
        cleaned = cleaned.replaceAll("[＃#]\\s*\\d{1,4}\\b", " ");

        cleaned = cleaned.replaceAll("\\s*[-–—]\\s*\\d{1,4}\\s*$", "");
        cleaned = cleaned.replaceAll("\\s+\\d{1,4}\\s*$", "");
        cleaned = cleaned.replaceAll("^\\d{1,4}\\s*[-–—]\\s*", "");
        cleaned = cleaned.replaceAll("\\s*[-–—]\\s*$", "");

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

    public int cleanupEmptySeries() {
        List<VideoSeries> allSeries = seriesRepository.findAll();
        int removed = 0;

        for (VideoSeries series : allSeries) {
            if (videoRepository.countBySeriesId(series.getId()) == 0) {
                log.info("Removing empty series: {} (id={})", series.getTitle(), series.getId());
                seriesRepository.delete(series);
                removed++;
            }
        }

        if (removed > 0) {
            log.info("Empty series cleanup completed: removed {} series", removed);
        }
        return removed;
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

        if (merged > 0) {
            log.info("Cleanup completed: merged {} duplicate series", merged);
        }
        return merged;
    }
}
