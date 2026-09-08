package com.fryfrog.hub.comic.repository;

import com.fryfrog.hub.comic.model.ComicChapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface ComicChapterRepository extends JpaRepository<ComicChapter, Long> {

    @EntityGraph(attributePaths = "comic")
    Optional<ComicChapter> findWithComicById(Long id);

    List<ComicChapter> findByComic_IdOrderByChapterIndexAsc(Long comicId);

    void deleteByComic_Id(Long comicId);
}
