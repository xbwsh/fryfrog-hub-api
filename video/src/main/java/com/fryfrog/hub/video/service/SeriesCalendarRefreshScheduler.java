package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.VideoSeries;
import com.fryfrog.hub.video.repository.VideoSeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 追更日历定时刷新：每天凌晨刷新在播剧集的下一集信息。
 * next_episode_to_air 随播出动态变化，需定期重新拉取 TMDB 详情。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeriesCalendarRefreshScheduler {

    private final VideoSeriesRepository seriesRepository;
    private final SeriesService seriesService;

    @Scheduled(cron = "0 30 3 * * *")
    public void refreshUpcomingCalendars() {
        log.info("[Calendar] Starting daily next-episode refresh");
        int updated = 0;
        int pageNum = 0;
        final int pageSize = 50;

        Page<VideoSeries> page;
        do {
            page = seriesRepository.findAll(PageRequest.of(pageNum++, pageSize));
            for (VideoSeries series : page.getContent()) {
                if (series.getTmdbId() == null) continue;
                if (!"tv".equalsIgnoreCase(series.getMediaType())) continue;
                try {
                    seriesService.refreshNextEpisode(series.getId());
                    updated++;
                } catch (Exception e) {
                    log.debug("[Calendar] Failed to refresh series {}: {}", series.getId(), e.getMessage());
                }
            }
        } while (page.hasNext());

        log.info("[Calendar] Next-episode refresh complete: {} series updated", updated);
    }
}
