package com.fryfrog.hub.ebook.repository;

import com.fryfrog.hub.ebook.model.Ebook;
import com.fryfrog.hub.ebook.model.EbookReadingProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EbookRepository extends JpaRepository<Ebook, Long> {

    List<Ebook> findByTitleContainingIgnoreCase(String title);

    List<Ebook> findByAuthorContainingIgnoreCase(String author);

    List<Ebook> findByGenreContainingIgnoreCase(String genre);

    Optional<Ebook> findByFilePath(String filePath);

    List<Ebook> findByFavoriteTrue();

    List<Ebook> findAllByOrderByCreatedAtDesc();

    List<Ebook> findBySeriesIgnoreCase(String series);

    @Query("SELECT e FROM Ebook e JOIN EbookReadingProgress p ON e.id = p.ebook.id ORDER BY p.updatedAt DESC")
    List<Ebook> findRecentlyRead();

    @Query("SELECT COUNT(e) FROM Ebook e")
    long countAll();

    @Query("SELECT COUNT(e) FROM Ebook e WHERE e.favorite = true")
    long countFavorites();
}
