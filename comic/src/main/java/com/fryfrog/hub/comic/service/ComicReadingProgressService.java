package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.model.ComicReadingProgress;
import com.fryfrog.hub.comic.repository.ComicReadingProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComicReadingProgressService {

    private final ComicReadingProgressRepository repository;
    private final ComicMetadataService comicService;

    private static final double COMPLETED_THRESHOLD = 0.9;

    public ComicReadingProgress getProgress(Long comicId) {
        return repository.findByComicId(comicId).orElse(null);
    }

    @Transactional
    public ComicReadingProgress saveProgress(Long comicId, Integer currentPage, Integer totalPages) {
        Comic comic = comicService.getComicById(comicId);

        ComicReadingProgress progress = repository.findByComicId(comicId).orElse(new ComicReadingProgress());
        progress.setComic(comic);
        progress.setCurrentPage(currentPage);
        progress.setTotalPages(totalPages);

        if (totalPages != null && totalPages > 0) {
            progress.setCompleted((double) currentPage / totalPages >= COMPLETED_THRESHOLD);
        }

        ComicReadingProgress saved = repository.save(progress);
        log.info("Saved reading progress for comic {}: page {} / {}", comicId, currentPage, totalPages);
        return saved;
    }

    @Transactional
    public void deleteProgress(Long comicId) {
        repository.findByComicId(comicId).ifPresent(repository::delete);
    }
}
