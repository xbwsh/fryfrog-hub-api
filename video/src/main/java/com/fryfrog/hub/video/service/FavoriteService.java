package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Favorite;
import com.fryfrog.hub.video.repository.FavoriteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FavoriteService {

    private final FavoriteRepository repository;

    @Transactional
    public void setFavorite(Long userId, String contentType, Long contentId, boolean status) {
        if (status) {
            if (!repository.findByUserIdAndContentTypeAndContentId(userId, contentType, contentId).isPresent()) {
                repository.save(Favorite.builder()
                        .userId(userId).contentType(contentType).contentId(contentId).build());
            }
        } else {
            repository.deleteByUserIdAndContentTypeAndContentId(userId, contentType, contentId);
        }
    }

    /** 返回 contentId → 是否收藏 */
    public Map<Long, Boolean> statusMap(Long userId, String contentType, Collection<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByUserIdAndContentTypeAndContentIdIn(userId, contentType, contentIds).stream()
                .collect(Collectors.toMap(Favorite::getContentId, f -> Boolean.TRUE, (a, b) -> a));
    }

    public List<Long> contentIds(Long userId, String contentType) {
        return repository.findByUserIdAndContentType(userId, contentType).stream()
                .map(Favorite::getContentId)
                .toList();
    }
}