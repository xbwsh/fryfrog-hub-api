package com.fryfrog.hub.video.repository;

import com.fryfrog.hub.video.model.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    Optional<Favorite> findByUserIdAndContentTypeAndContentId(Long userId, String contentType, Long contentId);

    List<Favorite> findByUserIdAndContentTypeAndContentIdIn(Long userId, String contentType, Collection<Long> contentIds);

    List<Favorite> findByUserIdAndContentType(Long userId, String contentType);

    void deleteByUserIdAndContentTypeAndContentId(Long userId, String contentType, Long contentId);

    void deleteByContentTypeAndContentId(String contentType, Long contentId);

    void deleteByContentTypeAndContentIdIn(String contentType, Collection<Long> contentIds);
}