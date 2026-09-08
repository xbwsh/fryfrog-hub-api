package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.comic.model.Comic;
import com.fryfrog.hub.comic.model.ComicChapter;
import com.fryfrog.hub.comic.model.ComicProgress;
import com.fryfrog.hub.comic.repository.ComicChapterRepository;
import com.fryfrog.hub.comic.repository.ComicProgressRepository;
import com.fryfrog.hub.comic.repository.ComicRepository;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComicProgressService {

    private static final double COMPLETED_THRESHOLD = 95.0;

    private final ComicProgressRepository progressRepository;
    private final ComicRepository comicRepository;
    private final ComicChapterRepository chapterRepository;

    public ComicProgress getProgress(Long userId, Long comicId) {
        return progressRepository.findByUserIdAndComic_Id(userId, comicId).orElse(null);
    }

    /** comicId → 进度（列表页批量取用） */
    public Map<Long, ComicProgress> getProgressByComicIds(Long userId, Collection<Long> comicIds) {
        if (comicIds == null || comicIds.isEmpty()) return Map.of();
        return progressRepository.findByUserIdAndComic_IdIn(userId, comicIds).stream()
                .collect(Collectors.toMap(p -> p.getComic().getId(), p -> p, (a, b) -> a));
    }

    /** 进度换算全局百分比：已完成卷页数 + 当前卷内页占比，除以总页数。 */
    public double progressPercent(ComicProgress progress, List<ComicChapter> chapters) {
        long totalPages = chapters.stream()
                .map(ComicChapter::getPageCount)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue).sum();
        if (totalPages <= 0) return 0;
        if (progress == null || progress.getChapterIndex() == null) return 0;

        long done = 0;
        for (ComicChapter chapter : chapters) {
            if (chapter.getChapterIndex() < progress.getChapterIndex()) {
                if (chapter.getPageCount() != null) done += chapter.getPageCount();
                continue;
            }
            if (chapter.getChapterIndex() == progress.getChapterIndex()) {
                int pages = chapter.getPageCount() != null ? chapter.getPageCount() : 0;
                int page = progress.getPageIndex() != null ? progress.getPageIndex() : 0;
                done += Math.min(pages, page + 1);
            }
            break;
        }
        return Math.min(100, done * 100.0 / totalPages);
    }

    @Transactional
    public ComicProgress updatePosition(Long userId, Long comicId, Integer chapterIndex, Integer pageIndex) {
        Comic comic = comicRepository.findById(comicId)
                .orElseThrow(() -> new ResourceNotFoundException("Comic", "id", comicId));

        ComicProgress progress = progressRepository.findByUserIdAndComic_Id(userId, comicId)
                .orElseGet(ComicProgress::new);
        progress.setUserId(userId);
        progress.setComic(comic);
        if (chapterIndex != null) progress.setChapterIndex(chapterIndex);
        if (pageIndex != null) progress.setPageIndex(pageIndex);

        autoDetectCompleted(progress, comicId);
        ComicProgress saved = progressRepository.save(progress);
        log.debug("[ComicProgress] user={} comic={} chapter={} page={}", userId, comicId,
                progress.getChapterIndex(), progress.getPageIndex());
        return saved;
    }

    @Transactional
    public ComicProgress setCompleted(Long userId, Long comicId, boolean completed) {
        Comic comic = comicRepository.findById(comicId)
                .orElseThrow(() -> new ResourceNotFoundException("Comic", "id", comicId));

        ComicProgress progress = progressRepository.findByUserIdAndComic_Id(userId, comicId)
                .orElseGet(() -> ComicProgress.builder().userId(userId).comic(comic).build());
        progress.setCompleted(completed);
        if (completed && progress.getChapterIndex() == null) {
            progress.setChapterIndex(0);
            progress.setPageIndex(0);
        }
        return progressRepository.save(progress);
    }

    @Transactional
    public void deleteProgress(Long userId, Long comicId) {
        progressRepository.findByUserIdAndComic_Id(userId, comicId)
                .ifPresent(progressRepository::delete);
    }

    /** 读到最后一卷最后一页视为读完。 */
    private void autoDetectCompleted(ComicProgress progress, Long comicId) {
        if (progress.getChapterIndex() == null || progress.getPageIndex() == null) return;
        List<ComicChapter> chapters = chapterRepository.findByComic_IdOrderByChapterIndexAsc(comicId);
        if (chapters.isEmpty()) return;

        ComicChapter last = chapters.get(chapters.size() - 1);
        boolean onLastChapter = progress.getChapterIndex() >= last.getChapterIndex();
        int lastPage = last.getPageCount() != null ? last.getPageCount() : 0;
        if (onLastChapter && lastPage > 0
                && progress.getPageIndex() >= lastPage - Math.max(1, lastPage / 20)) {
            progress.setCompleted(true);
        }
    }
}
