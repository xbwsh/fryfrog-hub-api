package com.fryfrog.hub.comic.repository;

import com.fryfrog.hub.comic.model.ComicReadingProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ComicReadingProgressRepository extends JpaRepository<ComicReadingProgress, Long> {

    Optional<ComicReadingProgress> findByComicId(Long comicId);
}
