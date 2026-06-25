package com.fryfrog.hub.comic.repository;

import com.fryfrog.hub.comic.model.ComicCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComicCharacterRepository extends JpaRepository<ComicCharacter, Long> {

    List<ComicCharacter> findByComicId(Long comicId);

    void deleteByComicId(Long comicId);
}
