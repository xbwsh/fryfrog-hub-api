package com.fryfrog.hub.comic.repository;

import com.fryfrog.hub.comic.model.ComicProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ComicProgressRepository extends JpaRepository<ComicProgress, Long> {

    @EntityGraph(attributePaths = "comic")
    Optional<ComicProgress> findByUserIdAndComic_Id(Long userId, Long comicId);

    List<ComicProgress> findByUserIdAndComic_IdIn(Long userId, Collection<Long> comicIds);

    void deleteByUserIdAndComic_Id(Long userId, Long comicId);

    void deleteByComic_Id(Long comicId);
}
