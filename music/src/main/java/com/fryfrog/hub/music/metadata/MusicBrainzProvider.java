package com.fryfrog.hub.music.metadata;

import com.fryfrog.hub.music.dto.MusicBrainzResult;
import com.fryfrog.hub.music.service.MusicBrainzScrapingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class MusicBrainzProvider implements MusicMetadataProvider {

    private final MusicBrainzScrapingService musicBrainzScrapingService;

    @Override
    public String getName() {
        return "MusicBrainz";
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public MusicBrainzResult scrape(String title, String artist, String album) {
        log.debug("Trying MusicBrainz for: {} - {}", artist, title);
        return musicBrainzScrapingService.searchExact(title, artist);
    }
}