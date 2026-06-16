package com.fryfrog.hub.ebook.metadata;

import com.fryfrog.hub.ebook.dto.BookSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
@Slf4j
public class BookMetadataProviderManager {

    private final List<BookMetadataProvider> providers;

    public BookMetadataProviderManager(List<BookMetadataProvider> providers) {
        this.providers = providers.stream()
                .filter(BookMetadataProvider::isAvailable)
                .toList();
        log.info("Initialized {} book metadata providers: {}", providers.size(),
                this.providers.stream().map(BookMetadataProvider::getName).toList());
    }

    public ProviderResult scrape(String title, String author) {
        List<BookSearchResult> allResults = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(providers.size(), 4));
        List<Future<List<BookSearchResult>>> futures = new ArrayList<>();

        for (BookMetadataProvider provider : providers) {
            futures.add(executor.submit(() -> {
                try {
                    long start = System.currentTimeMillis();
                    List<BookSearchResult> results = provider.search(title, author);
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("Provider {} found {} results in {}ms for: {} - {}",
                            provider.getName(), results.size(), elapsed, title, author);
                    return results;
                } catch (Exception e) {
                    log.warn("Provider {} failed: {}", provider.getName(), e.getMessage());
                    return Collections.<BookSearchResult>emptyList();
                }
            }));
        }

        for (Future<List<BookSearchResult>> future : futures) {
            try {
                List<BookSearchResult> results = future.get(10, TimeUnit.SECONDS);
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

        BookMetadataProvider firstProvider = providers.isEmpty() ? null : providers.get(0);
        BookSearchResult best = firstProvider != null
                ? firstProvider.findBestMatch(allResults, title, author)
                : allResults.get(0);

        if (best == null) {
            return new ProviderResult(null, null);
        }

        byte[] cover = null;
        for (BookMetadataProvider provider : providers) {
            if (cover == null) {
                try {
                    cover = provider.getCover(best);
                } catch (Exception e) {
                    log.warn("Failed to get cover from {}: {}", provider.getName(), e.getMessage());
                }
            }
            if (cover != null) break;
        }

        return new ProviderResult(best, cover);
    }

    public record ProviderResult(
            BookSearchResult searchResult,
            byte[] coverData
    ) {}

    public List<BookMetadataProvider> getProviders() {
        return providers;
    }
}