package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.model.ComicReadingProgress;
import com.fryfrog.hub.comic.repository.ComicReadingProgressRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComicReadingProgressService {

    private final ComicReadingProgressRepository repository;
    private final ComicMetadataService comicService;

    @PersistenceContext
    private EntityManager entityManager;

    private static final double COMPLETED_THRESHOLD = 0.9;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ComicReadingProgress getProgress(Long comicId) {
        // 使用新的事务，确保读取最新数据
        return repository.findByComicId(comicId).orElse(null);
    }

    @Transactional
    @Retryable(
        value = {CannotAcquireLockException.class, JpaSystemException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
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
