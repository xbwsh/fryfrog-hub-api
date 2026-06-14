package com.fryfrog.hub.music.metadata;

import com.fryfrog.hub.music.dto.MusicBrainzResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class MusicMetadataProviderManager {

    private final List<MusicMetadataProvider> providers;

    public MusicMetadataProviderManager(List<MusicMetadataProvider> providers) {
        this.providers = providers.stream()
                .sorted((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                .toList();
        log.info("Initialized {} music metadata providers: {}", providers.size(),
                providers.stream().map(MusicMetadataProvider::getName).toList());
    }

    public MusicBrainzResult scrapeWithFallback(String title, String artist, String album) {
        for (MusicMetadataProvider provider : providers) {
            if (!provider.isAvailable()) {
                log.debug("Provider {} is not available, skipping", provider.getName());
                continue;
            }

            try {
                var result = provider.scrape(title, artist, album);
                if (result != null) {
                    log.info("Scraped successfully with {}: {} - {}", provider.getName(), artist, title);
                    return result;
                }
            } catch (Exception e) {
                log.warn("Provider {} failed: {}", provider.getName(), e.getMessage());
            }
        }

        log.info("No provider found data for: {} - {}", artist, title);
        return null;
    }

    public List<MusicMetadataProvider> getProviders() {
        return providers;
    }
}