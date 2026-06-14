package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.model.ReadingProgress;
import com.fryfrog.hub.comic.repository.ReadingProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReadingProgressService {

    private final ReadingProgressRepository repository;
    private final ComicMetadataService comicService;

    private static final double COMPLETED_THRESHOLD = 0.9;

    public ReadingProgress getProgress(Long comicId) {
        return repository.findByComicId(comicId).orElse(null);
    }

    @Transactional
    public ReadingProgress saveProgress(Long comicId, Integer currentPage, Integer totalPages) {
        Comic comic = comicService.getComicById(comicId);

        ReadingProgress progress = repository.findByComicId(comicId).orElse(new ReadingProgress());
        progress.setComic(comic);
        progress.setCurrentPage(currentPage);
        progress.setTotalPages(totalPages);

        if (totalPages != null && totalPages > 0) {
            progress.setCompleted((double) currentPage / totalPages >= COMPLETED_THRESHOLD);
        }

        ReadingProgress saved = repository.save(progress);
        log.debug("Saved reading progress for comic {}: page {} / {}", comicId, currentPage, totalPages);
        return saved;
    }

    @Transactional
    public void deleteProgress(Long comicId) {
        repository.findByComicId(comicId).ifPresent(repository::delete);
    }
}
