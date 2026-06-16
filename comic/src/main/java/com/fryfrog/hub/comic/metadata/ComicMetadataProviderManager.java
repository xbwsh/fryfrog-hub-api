package com.fryfrog.hub.comic.metadata;

import com.fryfrog.hub.comic.dto.ComicSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
@Slf4j
public class ComicMetadataProviderManager {

    private final List<ComicMetadataProvider> providers;

    public ComicMetadataProviderManager(List<ComicMetadataProvider> providers) {
        this.providers = providers.stream()
                .filter(ComicMetadataProvider::isAvailable)
                .toList();
        log.info("Initialized {} comic metadata providers: {}", providers.size(),
                this.providers.stream().map(ComicMetadataProvider::getName).toList());
    }

    public ProviderResult scrape(String title, String author) {
        List<ComicSearchResult> allResults = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(providers.size(), 4));
        List<Future<List<ComicSearchResult>>> futures = new ArrayList<>();

        for (ComicMetadataProvider provider : providers) {
            futures.add(executor.submit(() -> {
                try {
                    long start = System.currentTimeMillis();
                    List<ComicSearchResult> results = provider.search(title, author);
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("Provider {} found {} results in {}ms for: {}",
                            provider.getName(), results.size(), elapsed, title);
                    return results;
                } catch (Exception e) {
                    log.warn("Provider {} failed: {}", provider.getName(), e.getMessage());
                    return Collections.<ComicSearchResult>emptyList();
                }
            }));
        }

        for (Future<List<ComicSearchResult>> future : futures) {
            try {
                List<ComicSearchResult> results = future.get(10, TimeUnit.SECONDS);
                allResults.addAll(results);
            } catch (TimeoutException e) {
                log.warn("Provider timed out");
            } catch (Exception e) {
                log.warn("Provider execution failed: {}", e.getMessage());
            }
        }
        executor.shutdown();

        if (allResults.isEmpty()) {
            return new ProviderResult(null, null);
        }

        ComicMetadataProvider firstProvider = providers.isEmpty() ? null : providers.get(0);
        ComicSearchResult best = firstProvider != null
                ? firstProvider.findBestMatch(allResults, title, author)
                : allResults.get(0);

        if (best == null) {
            return new ProviderResult(null, null);
        }

        byte[] cover = null;
        for (ComicMetadataProvider provider : providers) {
            try {
                cover = provider.getCover(best);
            } catch (Exception e) {
                log.warn("Failed to get cover from {}: {}", provider.getName(), e.getMessage());
            }
            if (cover != null) break;
        }

        return new ProviderResult(best, cover);
    }

    public record ProviderResult(
            ComicSearchResult searchResult,
            byte[] coverData
    ) {}

    public List<ComicMetadataProvider> getProviders() {
        return providers;
    }
}
