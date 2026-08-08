package com.fryfrog.hub.video.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.video.dto.LibrarySeriesGroupDTO;
import com.fryfrog.hub.video.dto.SeriesListDTO;
import com.fryfrog.hub.video.dto.TmdbTvDetail;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoRepository;
import com.fryfrog.hub.video.repository.VideoSeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final MediaLibraryService mediaLibraryService;

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
        series.setReleaseDate(detail.getFirstAirDate());
        series.setMediaType("tv");
        series.setRating(detail.getVoteAverage());
        series.setPosterUrl(tmdbService.getPosterUrl(detail.getPosterPath()));
        series.setBackdropUrl(tmdbService.getBackdropUrl(detail.getBackdropPath()));
        series.setMetadataSource("tmdb");
        series.setTotalEpisodes(detail.getNumberOfEpisodes());
        series.setNumberOfSeasons(detail.getNumberOfSeasons());
        series.setStatus(detail.getStatus());

        // 下一集信息（追更日历用）
        var next = detail.getNextEpisodeToAir();
        if (next != null) {
            series.setNextEpisodeDate(next.getAirDate());
            if (next.getSeasonNumber() != null && next.getEpisodeNumber() != null) {
                series.setNextEpisodeNumber(String.format("S%02dE%02d", next.getSeasonNumber(), next.getEpisodeNumber()));
            }
        } else {
            series.setNextEpisodeDate(null);
            series.setNextEpisodeNumber(null);
        }

        return seriesRepository.save(series);
    }

    public VideoSeries getOrCreateAndBindSeries(String title, Long tmdbId) {
        Optional<VideoSeries> existing = seriesRepository.findByTmdbId(tmdbId);
        if (existing.isPresent()) {
            return existing.get();
        }
        VideoSeries byTitle = seriesRepository.findByTitle(title).orElse(null);
        if (byTitle != null) {
            return bindTmdbToSeries(byTitle.getId(), tmdbId);
        }
        VideoSeries series = createSeries(title);
        return bindTmdbToSeries(series.getId(), tmdbId);
    }

    public List<VideoSeries> getAllSeries() {
        List<Long> enabledIds = mediaLibraryService.getEnabledLibraryIds();
        return seriesRepository.findAll().stream()
                .filter(series -> series.getVideos().isEmpty() ||
                        series.getVideos().stream().anyMatch(v ->
                                v.getLibraryId() == null || enabledIds.contains(v.getLibraryId())))
                .toList();
    }

    /**
     * 追更日历：返回在播（Returning Series）且有下一集播出日期的系列，按日期升序
     */
    public List<VideoSeries> getUpcomingCalendar() {
        List<Long> enabledIds = mediaLibraryService.getEnabledLibraryIds();
        java.time.LocalDate today = java.time.LocalDate.now();

        return seriesRepository.findAll().stream()
                .filter(series -> series.getVideos().isEmpty() ||
                        series.getVideos().stream().anyMatch(v ->
                                v.getLibraryId() == null || enabledIds.contains(v.getLibraryId())))
                .filter(series -> series.getNextEpisodeDate() != null)
                .filter(series -> "tv".equalsIgnoreCase(series.getMediaType()))
                .filter(series -> {
                    try {
                        return java.time.LocalDate.parse(series.getNextEpisodeDate()).isAfter(today.minusDays(1));
                    } catch (Exception e) {
                        return false;
                    }
                })
                .sorted(Comparator.comparing(
                        s -> s.getNextEpisodeDate() != null ? s.getNextEpisodeDate() : "",
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public long count() {
        return getAllSeries().size();
    }

    public Page<VideoSeries> getSeriesPage(Pageable pageable) {
        return seriesRepository.findAll(pageable);
    }

    public Optional<VideoSeries> getSeriesById(Long id) {
        return seriesRepository.findById(id);
    }

    /**
     * 设置系列收藏状态
     */
    public VideoSeries setFavorite(Long id, boolean status) {
        VideoSeries series = getSeriesById(id)
                .orElseThrow(() -> new RuntimeException("Series not found: " + id));
        series.setFavorite(status);
        return seriesRepository.save(series);
    }

    /**
     * 收藏的系列列表（按启用资源库过滤）
     */
    public List<VideoSeries> getFavoriteSeries() {
        List<Long> enabledIds = mediaLibraryService.getEnabledLibraryIds();
        return seriesRepository.findAll().stream()
                .filter(VideoSeries::getFavorite)
                .filter(series -> series.getVideos().isEmpty() ||
                        series.getVideos().stream().anyMatch(v ->
                                v.getLibraryId() == null || enabledIds.contains(v.getLibraryId())))
                .toList();
    }

    public long countFavoriteSeries() {
        return getFavoriteSeries().size();
    }

    @Transactional
    public void removeVideoFromSeries(Video video) {
        if (video.getSeries() != null) {
            Long seriesId = video.getSeries().getId();
            VideoSeries series = seriesRepository.findById(seriesId).orElse(null);
            if (series == null) {
                video.setSeries(null);
                video.setIsSeries(false);
                videoRepository.save(video);
                return;
            }
            String seriesTitle = series.getTitle();
            video.setSeries(null);
            video.setIsSeries(false);

            videoRepository.save(video);

            if (videoRepository.countBySeriesId(seriesId) == 0) {
                log.info("Removing empty series: {} (id={})", seriesTitle, seriesId);
                seriesRepository.deleteById(seriesId);
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

    public String cleanTitle(String title) {
        String cleaned = com.fryfrog.hub.common.util.TitleCleaner.cleanForSearch(title);
        return (cleaned == null || cleaned.isBlank()) ? "Unknown" : cleaned;
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

    /**
     * 仅刷新系列的下一集信息（追更日历用），不重写其他元数据
     */
    public void refreshNextEpisode(Long seriesId) {
        VideoSeries series = seriesRepository.findById(seriesId).orElse(null);
        if (series == null || series.getTmdbId() == null) return;

        TmdbTvDetail detail = tmdbService.getTvDetail(series.getTmdbId());
        if (detail == null) return;

        var next = detail.getNextEpisodeToAir();
        if (next != null) {
            series.setNextEpisodeDate(next.getAirDate());
            if (next.getSeasonNumber() != null && next.getEpisodeNumber() != null) {
                series.setNextEpisodeNumber(String.format("S%02dE%02d", next.getSeasonNumber(), next.getEpisodeNumber()));
            }
        } else {
            series.setNextEpisodeDate(null);
            series.setNextEpisodeNumber(null);
        }
        seriesRepository.save(series);
    }

    public List<LibrarySeriesGroupDTO> getSeriesGroupedByLibrary() {
        List<Long> enabledIds = mediaLibraryService.getEnabledLibraryIds();
        List<MediaLibrary> libraries = mediaLibraryService.getEnabledLibraries().stream()
                .filter(lib -> "VIDEO".equalsIgnoreCase(lib.getType()))
                .sorted(Comparator.comparingInt(lib -> lib.getSortOrder() != null ? lib.getSortOrder() : 0))
                .toList();

        List<VideoSeries> allSeries = getAllSeries();
        List<Video> allStandalone = videoRepository.findBySeriesIsNullOrderByTitleAsc();

        Map<Long, List<VideoSeries>> seriesByLibrary = new LinkedHashMap<>();
        Map<Long, List<Video>> standaloneByLibrary = new LinkedHashMap<>();
        List<VideoSeries> unassignedSeries = new ArrayList<>();
        List<Video> unassignedStandalone = new ArrayList<>();

        for (VideoSeries series : allSeries) {
            Set<Long> libraryIds = series.getVideos().stream()
                    .map(Video::getLibraryId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            boolean added = false;
            for (Long libId : libraryIds) {
                if (enabledIds.contains(libId)) {
                    seriesByLibrary.computeIfAbsent(libId, k -> new ArrayList<>()).add(series);
                    added = true;
                }
            }
            if (!added) {
                unassignedSeries.add(series);
            }
        }

        for (Video video : allStandalone) {
            if (video.getLibraryId() != null && enabledIds.contains(video.getLibraryId())) {
                standaloneByLibrary.computeIfAbsent(video.getLibraryId(), k -> new ArrayList<>()).add(video);
            } else {
                unassignedStandalone.add(video);
            }
        }

        List<LibrarySeriesGroupDTO> result = new ArrayList<>();

        for (MediaLibrary lib : libraries) {
            List<VideoSeries> libSeries = seriesByLibrary.getOrDefault(lib.getId(), List.of());
            List<Video> libStandalone = standaloneByLibrary.getOrDefault(lib.getId(), List.of());

            List<SeriesListDTO> seriesDTOs = libSeries.stream()
                    .map(s -> SeriesListDTO.fromEntity(s, s.getVideos()))
                    .toList();
            List<SeriesListDTO> standaloneDTOs = libStandalone.stream()
                    .map(SeriesListDTO::fromStandaloneVideo)
                    .toList();

            if (!seriesDTOs.isEmpty() || !standaloneDTOs.isEmpty()) {
                result.add(LibrarySeriesGroupDTO.fromLibrary(lib, seriesDTOs, standaloneDTOs));
            }
        }

        if (!unassignedSeries.isEmpty() || !unassignedStandalone.isEmpty()) {
            List<SeriesListDTO> unassignedSeriesDTOs = unassignedSeries.stream()
                    .map(s -> SeriesListDTO.fromEntity(s, s.getVideos()))
                    .toList();
            List<SeriesListDTO> unassignedStandaloneDTOs = unassignedStandalone.stream()
                    .map(SeriesListDTO::fromStandaloneVideo)
                    .toList();

            result.add(LibrarySeriesGroupDTO.builder()
                    .libraryId(null)
                    .libraryName("未分配")
                    .libraryPath(null)
                    .subType(null)
                    .series(unassignedSeriesDTOs)
                    .standaloneVideos(unassignedStandaloneDTOs)
                    .seriesCount(unassignedSeriesDTOs.size())
                    .standaloneCount(unassignedStandaloneDTOs.size())
                    .build());
        }

        return result;
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
