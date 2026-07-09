package com.fryfrog.hub.service;

import com.fryfrog.hub.common.model.MediaSeries;
import com.fryfrog.hub.common.repository.MediaSeriesRepository;
import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.repository.ComicRepository;
import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class MediaSeriesMigrationService implements ApplicationRunner {

    private final MediaSeriesRepository seriesRepo;
    private final ComicRepository comicRepo;
    private final EbookRepository ebookRepo;

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (seriesRepo.count() > 0) {
            log.info("MediaSeries migration already done, skipping");
            return;
        }

        log.info("Starting MediaSeries migration...");

        // 从 comics 表查找有 series 字符串但没有 series_id 的记录
        List<Comic> unboundComics = comicRepo.findUnboundWithSeriesName();
        List<Ebook> unboundEbooks = ebookRepo.findUnboundWithSeriesName();

        Set<String> allSeries = new HashSet<>();
        for (Comic c : unboundComics) {
            if (c.getSeries() != null && !c.getSeries().isBlank()) allSeries.add(c.getSeries());
        }
        for (Ebook e : unboundEbooks) {
            if (e.getSeries() != null && !e.getSeries().isBlank()) allSeries.add(e.getSeries());
        }

        if (allSeries.isEmpty()) {
            log.info("No series found, migration complete");
            return;
        }

        log.info("Found {} series to migrate", allSeries.size());

        for (String seriesName : allSeries) {
            MediaSeries ms = new MediaSeries();
            ms.setTitle(seriesName);
            ms.setMediaType(determineMediaType(seriesName, unboundComics, unboundEbooks));
            enrichFromExistingData(ms, seriesName, unboundComics, unboundEbooks);
            seriesRepo.save(ms);

            // 用 native SQL 更新 series_id
            em.createNativeQuery("UPDATE comics SET series_id = ?1 WHERE series = ?2 AND series_id IS NULL")
                    .setParameter(1, ms.getId())
                    .setParameter(2, seriesName)
                    .executeUpdate();
            em.createNativeQuery("UPDATE ebooks SET series_id = ?1 WHERE series = ?2 AND series_id IS NULL")
                    .setParameter(1, ms.getId())
                    .setParameter(2, seriesName)
                    .executeUpdate();

            log.debug("Migrated series '{}' (id={}, type={})", seriesName, ms.getId(), ms.getMediaType());
        }

        log.info("MediaSeries migration complete: {} series migrated", allSeries.size());
    }

    private String determineMediaType(String seriesName, List<Comic> comics, List<Ebook> ebooks) {
        boolean hasComics = comics.stream().anyMatch(c -> seriesName.equals(c.getSeries()));
        boolean hasEbooks = ebooks.stream().anyMatch(e -> seriesName.equals(e.getSeries()));
        if (hasComics && hasEbooks) return "both";
        if (hasComics) return "comic";
        return "ebook";
    }

    private void enrichFromExistingData(MediaSeries ms, String seriesName, List<Comic> comics, List<Ebook> ebooks) {
        List<Comic> seriesComics = comics.stream()
                .filter(c -> seriesName.equals(c.getSeries()))
                .toList();

        for (Comic comic : seriesComics) {
            if (comic.getMetadataSourceId() != null) {
                ms.setMetadataSource(comic.getMetadataSource());
                ms.setMetadataSourceId(comic.getMetadataSourceId());
                ms.setAuthor(comic.getAuthor());
                ms.setGenre(comic.getGenre());
                ms.setOriginalTitle(comic.getOriginalTitle());
                ms.setRating(comic.getRating());
                ms.setYear(comic.getYear());
                ms.setDescription(comic.getSeriesSummary());
                ms.setSerializationStart(comic.getSerializationStart());
                return;
            }
        }

        List<Ebook> seriesEbooks = ebooks.stream()
                .filter(e -> seriesName.equals(e.getSeries()))
                .toList();

        for (Ebook ebook : seriesEbooks) {
            if (ebook.getBangumiId() != null) {
                ms.setMetadataSource("bangumi");
                ms.setMetadataSourceId(ebook.getBangumiId());
                ms.setAuthor(ebook.getAuthor());
                ms.setGenre(ebook.getGenre());
                ms.setYear(ebook.getYear());
                return;
            }
        }

        if (!seriesComics.isEmpty()) {
            Comic first = seriesComics.get(0);
            ms.setAuthor(first.getAuthor());
            ms.setGenre(first.getGenre());
            ms.setOriginalTitle(first.getOriginalTitle());
            ms.setRating(first.getRating());
            ms.setYear(first.getYear());
            ms.setDescription(first.getSeriesSummary());
            ms.setSerializationStart(first.getSerializationStart());
        } else if (!seriesEbooks.isEmpty()) {
            Ebook first = seriesEbooks.get(0);
            ms.setAuthor(first.getAuthor());
            ms.setGenre(first.getGenre());
            ms.setYear(first.getYear());
        }
    }
}
