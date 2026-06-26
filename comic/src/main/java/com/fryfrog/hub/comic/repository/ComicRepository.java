package com.fryfrog.hub.comic.repository;

import com.fryfrog.hub.comic.model.Comic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComicRepository extends JpaRepository<Comic, Long> {

    List<Comic> findByTitleContainingIgnoreCase(String title);

    List<Comic> findByAuthorContainingIgnoreCase(String author);

    List<Comic> findBySeriesContainingIgnoreCase(String series);

    Optional<Comic> findByFilePath(String filePath);

    List<Comic> findByFavoriteTrue();

    List<Comic> findByMetadataSourceIdIsNullOrderByVolumeAsc();

    List<Comic> findBySeriesIgnoreCase(String series);
}