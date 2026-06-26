package com.fryfrog.hub.comic.repository;

import com.fryfrog.hub.comic.model.ComicCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComicCharacterRepository extends JpaRepository<ComicCharacter, Long> {

    List<ComicCharacter> findByComicId(Long comicId);

    void deleteByComicId(Long comicId);

    @Query("SELECT c FROM ComicCharacter c WHERE c.comicId IN (SELECT cm.id FROM Comic cm WHERE LOWER(cm.series) = LOWER(:series))")
    List<ComicCharacter> findBySeries(@Param("series") String series);
}
