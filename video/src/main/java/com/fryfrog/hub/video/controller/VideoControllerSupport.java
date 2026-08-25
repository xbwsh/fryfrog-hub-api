package com.fryfrog.hub.video.controller;

import com.fryfrog.hub.common.dto.PageResponse;
import com.fryfrog.hub.common.exception.ForbiddenException;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.security.UserContext;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.video.dto.VideoDTO;
import com.fryfrog.hub.video.model.Favorite;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.WatchProgress;
import com.fryfrog.hub.video.service.FavoriteService;
import com.fryfrog.hub.video.service.NfoService;
import com.fryfrog.hub.video.service.WatchProgressService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 视频控制器共享逻辑：权限校验、视频 DTO 组装与分页转换。
 * 供 VideoController / VideoStreamController / VideoImageController / VideoScrapeController 复用。
 */
@Component
@RequiredArgsConstructor
public class VideoControllerSupport {

    private final NfoService nfoService;
    private final WatchProgressService watchProgressService;
    private final FavoriteService favoriteService;
    private final MediaLibraryService mediaLibraryService;
    private final UserService userService;

    public void requireAdmin(HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        if (!userService.isAdmin(userId)) {
            throw new ForbiddenException("需要管理员权限");
        }
    }

    public void requireLibraryVisible(Long libraryId, Long resourceId, String resourceName) {
        if (!mediaLibraryService.isVisibleToCurrentUser(libraryId)) {
            throw new ResourceNotFoundException(resourceName, "id", resourceId);
        }
    }

    public boolean isLibraryVisibleToCurrentUser(Long libraryId) {
        return mediaLibraryService.isVisibleToCurrentUser(libraryId);
    }

    public VideoDTO toDTO(Video video, HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        WatchProgress progress = watchProgressService.getProgress(userId, video.getId());
        boolean favorite = favoriteService.statusMap(userId, Favorite.TYPE_VIDEO, List.of(video.getId()))
                .getOrDefault(video.getId(), false);
        return toDTO(video, progress, favorite);
    }

    public VideoDTO toDTO(Video video, WatchProgress progress, boolean favorite) {
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        String baseName = nfoService.getBaseName(video.getFileName());
        boolean hasNfo = Files.exists(videoDir.resolve(baseName + ".nfo"));
        boolean hasPoster = Files.exists(videoDir.resolve(baseName + "-poster.jpg"));
        boolean hasFanart = Files.exists(videoDir.resolve(baseName + "-fanart.jpg"));
        boolean hasMetadataDir = Files.exists(nfoService.getMetadataDir(video));

        VideoDTO dto = VideoDTO.fromEntity(video, hasNfo, hasPoster, hasFanart, hasMetadataDir, favorite);

        if (progress != null) {
            dto.setWatchPosition(progress.getPositionSeconds());
            dto.setWatched(progress.getCompleted());
            if (progress.getDurationSeconds() != null && progress.getDurationSeconds() > 0) {
                dto.setWatchProgressPercent(progress.getPositionSeconds() / progress.getDurationSeconds() * 100);
            }
        }

        return dto;
    }

    public PageResponse<VideoDTO> toPageDTO(List<Video> videos, int page, int size, long total, long userId) {
        if (videos.isEmpty()) {
            return PageResponse.of(List.of(), page, size, total);
        }
        List<Long> videoIds = videos.stream().map(Video::getId).toList();
        Map<Long, WatchProgress> progressMap = watchProgressService.getProgressByVideoIds(userId, videoIds);
        Map<Long, Boolean> favMap = favoriteService.statusMap(userId, Favorite.TYPE_VIDEO, videoIds);
        List<VideoDTO> dtos = videos.stream()
                .map(v -> toDTO(v, progressMap.get(v.getId()), favMap.getOrDefault(v.getId(), false)))
                .collect(Collectors.toList());
        return PageResponse.of(dtos, page, size, total);
    }
}
