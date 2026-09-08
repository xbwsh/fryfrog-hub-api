package com.fryfrog.hub.ebook.service;

import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.model.EbookProgress;
import com.fryfrog.hub.ebook.repository.EbookProgressRepository;
import com.fryfrog.hub.ebook.repository.EbookRepository;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookProgressService {

    private static final double COMPLETED_THRESHOLD = 95.0;

    private final EbookProgressRepository progressRepository;
    private final EbookRepository bookRepository;

    public EbookProgress getProgress(Long userId, Long bookId) {
        return progressRepository.findByUserIdAndEbook_Id(userId, bookId).orElse(null);
    }

    /** bookId → 进度（列表页批量取用） */
    public Map<Long, EbookProgress> getProgressByBookIds(Long userId, Collection<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return Map.of();
        return progressRepository.findByUserIdAndEbook_IdIn(userId, bookIds).stream()
                .collect(Collectors.toMap(p -> p.getEbook().getId(), p -> p, (a, b) -> a));
    }

    @Transactional
    public EbookProgress updatePosition(Long userId, Long bookId, Double positionPercent, Integer chapterIndex) {
        Ebook book = bookRepository.findById(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Ebook", "id", bookId));

        EbookProgress progress = progressRepository.findByUserIdAndEbook_Id(userId, bookId)
                .orElseGet(EbookProgress::new);
        progress.setUserId(userId);
        progress.setEbook(book);
        if (positionPercent != null) {
            progress.setPositionPercent(Math.max(0, Math.min(100, positionPercent)));
        }
        if (chapterIndex != null) progress.setChapterIndex(chapterIndex);

        if (progress.getPositionPercent() != null
                && progress.getPositionPercent() >= COMPLETED_THRESHOLD) {
            progress.setCompleted(true);
        }
        EbookProgress saved = progressRepository.save(progress);
        log.debug("[EbookProgress] user={} book={} percent={} chapter={}", userId, bookId,
                progress.getPositionPercent(), progress.getChapterIndex());
        return saved;
    }

    @Transactional
    public EbookProgress setCompleted(Long userId, Long bookId, boolean completed) {
        Ebook book = bookRepository.findById(bookId)
                .orElseThrow(() -> new ResourceNotFoundException("Ebook", "id", bookId));

        EbookProgress progress = progressRepository.findByUserIdAndEbook_Id(userId, bookId)
                .orElseGet(() -> EbookProgress.builder()
                        .userId(userId).ebook(book).positionPercent(0d).chapterIndex(0).build());
        progress.setCompleted(completed);
        if (completed && progress.getPositionPercent() == null) {
            progress.setPositionPercent(100d);
        }
        return progressRepository.save(progress);
    }

    @Transactional
    public void deleteProgress(Long userId, Long bookId) {
        progressRepository.findByUserIdAndEbook_Id(userId, bookId)
                .ifPresent(progressRepository::delete);
    }
}
