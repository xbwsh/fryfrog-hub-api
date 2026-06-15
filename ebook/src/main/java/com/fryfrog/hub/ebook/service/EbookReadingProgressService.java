package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.model.EbookReadingProgress;
import com.fryfrog.hub.ebook.repository.EbookReadingProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookReadingProgressService {

    private final EbookReadingProgressRepository repository;
    private final EbookService ebookService;

    private static final double COMPLETED_THRESHOLD = 0.9;

    public EbookReadingProgress getProgress(Long ebookId) {
        return repository.findByEbookId(ebookId).orElse(null);
    }

    @Transactional
    public EbookReadingProgress saveProgress(Long ebookId, Integer currentPage, Integer totalPages) {
        Ebook ebook = ebookService.getEbookById(ebookId);

        EbookReadingProgress progress = repository.findByEbookId(ebookId).orElse(new EbookReadingProgress());
        progress.setEbook(ebook);
        progress.setCurrentPage(currentPage);
        progress.setTotalPages(totalPages);

        if (totalPages != null && totalPages > 0) {
            progress.setCompleted((double) currentPage / totalPages >= COMPLETED_THRESHOLD);
        }

        EbookReadingProgress saved = repository.save(progress);
        log.debug("Saved reading progress for ebook {}: page {} / {}", ebookId, currentPage, totalPages);
        return saved;
    }

    @Transactional
    public void deleteProgress(Long ebookId) {
        repository.findByEbookId(ebookId).ifPresent(repository::delete);
    }
}
