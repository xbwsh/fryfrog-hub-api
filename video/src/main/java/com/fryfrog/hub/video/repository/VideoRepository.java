package com.fryfrog.hub.video.repository;

import com.fryfrog.hub.video.model.Video;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {

    List<Video> findByTitleContainingIgnoreCase(String title);

    Page<Video> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    List<Video> findByDirectorContainingIgnoreCase(String director);

    Page<Video> findByDirectorContainingIgnoreCase(String director, Pageable pageable);

    List<Video> findByGenreContainingIgnoreCase(String genre);

    Optional<Video> findByFilePath(String filePath);

    /** 收藏表（favorites）关联查询用 */
    List<Video> findByIdIn(Collection<Long> ids);

    List<Video> findByTmdbIdIsNull();

    @Query("SELECT v FROM Video v WHERE (v.tmdbId IS NULL OR v.metadataUpdatedAt IS NULL)")
    List<Video> findUnscrapedVideos();

    Optional<Video> findByTmdbId(Long tmdbId);

    List<Video> findAllByTmdbId(Long tmdbId);

    List<Video> findByMediaType(String mediaType);

    List<Video> findByLibraryId(Long libraryId);

    @Query("SELECT COUNT(v) FROM Video v WHERE v.series.id = :seriesId")
    long countBySeriesId(@Param("seriesId") Long seriesId);

    Optional<Video> findByFileName(String fileName);

    List<Video> findBySeries(com.fryfrog.hub.video.model.VideoSeries series);

    List<Video> findByFilePathContaining(String path);

    List<Video> findBySeriesIsNullOrderByTitleAsc();

    Page<Video> findBySeriesIsNull(Pageable pageable);

    /** 某资源库下的独立视频（无系列归属），按标题排序分页。 */
    Page<Video> findBySeriesIsNullAndLibraryIdOrderByTitleAsc(Long libraryId, Pageable pageable);

    long countBySeriesIsNullAndLibraryId(Long libraryId);

    /** 未归属任何库的独立视频（libraryId 为空），按标题排序分页。 */
    Page<Video> findBySeriesIsNullAndLibraryIdIsNullOrderByTitleAsc(Pageable pageable);

    long countBySeriesIsNullAndLibraryIdIsNull();

    @Query("SELECT COUNT(v) FROM Video v WHERE v.series IS NULL")
    long countBySeriesIsNull();

    @Query("SELECT v FROM Video v WHERE v.libraryId IS NULL OR v.libraryId IN :enabledIds")
    List<Video> findAllByEnabledLibraries(@Param("enabledIds") List<Long> enabledIds);

    // ==================== 受限用户（仅授权库内的内容，libraryId 为空不可见） ====================

    @Query("SELECT v FROM Video v WHERE v.libraryId IN :allowedIds")
    List<Video> findAllByAllowedLibraries(@Param("allowedIds") List<Long> allowedIds);

    @Query("SELECT v FROM Video v WHERE v.libraryId IN :allowedIds")
    Page<Video> findAllByAllowedLibraries(@Param("allowedIds") List<Long> allowedIds, Pageable pageable);

    @Query("SELECT v FROM Video v WHERE LOWER(v.title) LIKE LOWER(CONCAT('%', :title, '%')) AND v.libraryId IN :allowedIds")
    List<Video> findByTitleContainingIgnoreCaseAndAllowedLibraries(@Param("title") String title, @Param("allowedIds") List<Long> allowedIds);

    @Query("SELECT v FROM Video v WHERE LOWER(v.title) LIKE LOWER(CONCAT('%', :title, '%')) AND v.libraryId IN :allowedIds")
    Page<Video> findByTitleContainingIgnoreCaseAndAllowedLibraries(@Param("title") String title, @Param("allowedIds") List<Long> allowedIds, Pageable pageable);

    @Query("SELECT v FROM Video v WHERE LOWER(v.director) LIKE LOWER(CONCAT('%', :director, '%')) AND v.libraryId IN :allowedIds")
    List<Video> findByDirectorContainingIgnoreCaseAndAllowedLibraries(@Param("director") String director, @Param("allowedIds") List<Long> allowedIds);

    @Query("SELECT v FROM Video v WHERE LOWER(v.director) LIKE LOWER(CONCAT('%', :director, '%')) AND v.libraryId IN :allowedIds")
    Page<Video> findByDirectorContainingIgnoreCaseAndAllowedLibraries(@Param("director") String director, @Param("allowedIds") List<Long> allowedIds, Pageable pageable);

    @Query("SELECT v FROM Video v WHERE v.series IS NULL AND v.libraryId IN :allowedIds")
    Page<Video> findBySeriesIsNullAndAllowedLibraries(@Param("allowedIds") List<Long> allowedIds, Pageable pageable);

    @Query("SELECT COUNT(v) FROM Video v WHERE v.series IS NULL AND v.libraryId IN :allowedIds")
    long countBySeriesIsNullAndAllowedLibraries(@Param("allowedIds") List<Long> allowedIds);

    @Query("SELECT v FROM Video v WHERE LOWER(v.title) LIKE LOWER(CONCAT('%', :title, '%')) AND (v.libraryId IS NULL OR v.libraryId IN :enabledIds)")
    List<Video> findByTitleContainingIgnoreCaseAndEnabledLibraries(@Param("title") String title, @Param("enabledIds") List<Long> enabledIds);

    @Query("SELECT v FROM Video v WHERE LOWER(v.title) LIKE LOWER(CONCAT('%', :title, '%')) AND (v.libraryId IS NULL OR v.libraryId IN :enabledIds)")
    Page<Video> findByTitleContainingIgnoreCaseAndEnabledLibraries(@Param("title") String title, @Param("enabledIds") List<Long> enabledIds, Pageable pageable);

    @Query("SELECT v FROM Video v WHERE LOWER(v.director) LIKE LOWER(CONCAT('%', :director, '%')) AND (v.libraryId IS NULL OR v.libraryId IN :enabledIds)")
    List<Video> findByDirectorContainingIgnoreCaseAndEnabledLibraries(@Param("director") String director, @Param("enabledIds") List<Long> enabledIds);

    @Query("SELECT v FROM Video v WHERE LOWER(v.director) LIKE LOWER(CONCAT('%', :director, '%')) AND (v.libraryId IS NULL OR v.libraryId IN :enabledIds)")
    Page<Video> findByDirectorContainingIgnoreCaseAndEnabledLibraries(@Param("director") String director, @Param("enabledIds") List<Long> enabledIds, Pageable pageable);

    @Query("SELECT v FROM Video v WHERE v.tmdbId IS NULL AND (v.libraryId IS NULL OR v.libraryId IN :enabledIds)")
    List<Video> findByTmdbIdIsNullAndEnabledLibraries(@Param("enabledIds") List<Long> enabledIds);

    @Query("SELECT v FROM Video v WHERE v.series IS NULL AND (v.libraryId IS NULL OR v.libraryId IN :enabledIds)")
    List<Video> findBySeriesIsNullAndEnabledLibraries(@Param("enabledIds") List<Long> enabledIds);

    @Query("SELECT v FROM Video v WHERE v.series IS NULL AND (v.libraryId IS NULL OR v.libraryId IN :enabledIds)")
    Page<Video> findBySeriesIsNullAndEnabledLibraries(@Param("enabledIds") List<Long> enabledIds, Pageable pageable);

    @Query("SELECT COUNT(v) FROM Video v WHERE v.series IS NULL AND (v.libraryId IS NULL OR v.libraryId IN :enabledIds)")
    long countBySeriesIsNullAndEnabledLibraries(@Param("enabledIds") List<Long> enabledIds);

    // ==================== 收藏（JOIN Favorite 表，避免内存分页） ====================

    @Query("SELECT v FROM Video v JOIN Favorite f ON v.id = f.contentId " +
           "WHERE f.userId = :userId AND f.contentType = :contentType " +
           "AND v.libraryId IN :allowedIds ORDER BY v.title")
    Page<Video> findFavoritesByUser(@Param("userId") Long userId,
                                    @Param("contentType") String contentType,
                                    @Param("allowedIds") List<Long> allowedIds,
                                    Pageable pageable);

    // ==================== 按条件批量查询（避免 findAll + 内存过滤） ====================

    @Query("SELECT v FROM Video v WHERE v.tmdbId IS NOT NULL AND v.mediaType IS NOT NULL " +
           "AND v.libraryId IN :libraryIds")
    List<Video> findByTmdbBoundAndLibraryIds(@Param("libraryIds") List<Long> libraryIds);
}
