package com.fryfrog.hub.music.metadata;

import com.fryfrog.hub.music.dto.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
@Slf4j
public class MusicMetadataProviderManager {

    private final List<MusicMetadataProvider> providers;

    public MusicMetadataProviderManager(List<MusicMetadataProvider> providers) {
        this.providers = providers.stream()
                .filter(MusicMetadataProvider::isAvailable)
                .toList();
        log.info("Initialized {} music metadata providers: {}", providers.size(),
                this.providers.stream().map(MusicMetadataProvider::getName).toList());
    }

    public ProviderResult scrape(String artist, String title, String album) {
        List<SearchResult> allResults = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(providers.size(), 4));
        List<Future<List<SearchResult>>> futures = new ArrayList<>();

        for (MusicMetadataProvider provider : providers) {
            futures.add(executor.submit(() -> {
                try {
                    long start = System.currentTimeMillis();
                    List<SearchResult> results = provider.search(artist, title, album);
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("Provider {} found {} results in {}ms for: {} - {}",
                            provider.getName(), results.size(), elapsed, artist, title);
                    return results;
                } catch (Exception e) {
                    log.warn("Provider {} failed: {}", provider.getName(), e.getMessage());
                    return Collections.<SearchResult>emptyList();
                }
            }));
        }

        for (Future<List<SearchResult>> future : futures) {
            try {
                List<SearchResult> results = future.get(10, TimeUnit.SECONDS);
                allResults.addAll(results);
            } catch (TimeoutException e) {
                log.warn("Provider timed out");
            } catch (Exception e) {
                log.warn("Provider execution failed: {}", e.getMessage());
            }
        }
        executor.shutdown();

        if (allResults.isEmpty()) {
            return new ProviderResult(null, null, null, null);
        }

        MusicMetadataProvider firstProvider = providers.isEmpty() ? null : providers.get(0);
        SearchResult best = firstProvider != null
                ? firstProvider.findBestMatch(allResults, artist, title)
                : allResults.get(0);

        if (best == null) {
            return new ProviderResult(null, null, null, null);
        }

        String lyrics = null;
        byte[] cover = null;

        List<String> allLyrics = new ArrayList<>();
        for (MusicMetadataProvider provider : providers) {
            try {
                String l = provider.getLyrics(best);
                if (l != null && !l.isBlank()) {
                    allLyrics.add(l);
                    log.info("Provider {} returned lyrics ({} chars) for: {} - {}",
                            provider.getName(), l.length(), artist, title);
                }
            } catch (Exception e) {
                log.warn("Failed to get lyrics from {}: {}", provider.getName(), e.getMessage());
            }
        }

        if (!allLyrics.isEmpty()) {
            lyrics = allLyrics.stream()
                    .max(Comparator.comparingInt(l -> countTimestampedLines(l)))
                    .orElse(allLyrics.get(0));
            log.info("Selected best lyrics ({} timestamped lines) from {} candidates",
                    countTimestampedLines(lyrics), allLyrics.size());
        }

        for (MusicMetadataProvider provider : providers) {
            if (cover == null) {
                try {
                    cover = provider.getCover(best);
                } catch (Exception e) {
                    log.warn("Failed to get cover from {}: {}", provider.getName(), e.getMessage());
                }
            }
            if (cover != null) break;
        }

        return new ProviderResult(best, best.getArtist(), lyrics, cover);
    }

    public record ProviderResult(
            SearchResult searchResult,
            String matchedArtist,
            String lyrics,
            byte[] coverData
    ) {}

    public List<MusicMetadataProvider> getProviders() {
        return providers;
    }

    private int countTimestampedLines(String lyrics) {
        if (lyrics == null) return 0;
        int count = 0;
        for (String line : lyrics.split("\n")) {
            if (line.matches("^\\[\\d{2}:\\d{2}[.:]\\d{2,3}].+")) {
                count++;
            }
        }
        return count;
    }
}
