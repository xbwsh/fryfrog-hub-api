package com.fryfrog.hub.comic.repository;

import com.fryfrog.hub.comic.model.Comic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComicRepository extends JpaRepository<Comic, Long> {

    List<Comic> findByTitleContainingIgnoreCase(String title);

    List<Comic> findByAuthorContainingIgnoreCase(String author);

    Optional<Comic> findByFilePath(String filePath);

    List<Comic> findByFavoriteTrue();

    List<Comic> findByMetadataSourceIdIsNullOrderByVolumeAsc();

    List<Comic> findBySeriesRef_Id(Long seriesId);

    @Query(value = "SELECT * FROM comics WHERE series_id IS NULL AND series IS NOT NULL AND series <> ''", nativeQuery = true)
    List<Comic> findUnboundWithSeriesName();
}