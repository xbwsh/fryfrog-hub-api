package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoverArtService {

    private final RestTemplate restTemplate;
    private final NfoService nfoService;

    @Value("${tmdb.image-size:original}")
    private String imageSize;

    private static final String IMAGE_BASE_URL = "https://image.tmdb.org/t/p";

    public boolean downloadAllCovers(Video video) {
        return downloadAllCovers(video, false);
    }

    public boolean downloadAllCovers(Video video, boolean force) {
        boolean posterOk = downloadPoster(video, force);
        boolean fanartOk = downloadFanart(video, force);

        if (posterOk || fanartOk) {
            updateVideoCoverPaths(video, posterOk, fanartOk);
        }

        return posterOk || fanartOk;
    }

    public boolean downloadPoster(Video video) {
        return downloadPoster(video, false);
    }

    public boolean downloadPoster(Video video, boolean force) {
        if (video.getPosterUrl() == null) {
            log.warn("No poster URL for video: {}", video.getTitle());
            return false;
        }
        return downloadImage(video.getPosterUrl(), nfoService.getPosterPath(video), force);
    }

    public boolean downloadFanart(Video video) {
        return downloadFanart(video, false);
    }

    public boolean downloadFanart(Video video, boolean force) {
        if (video.getBackdropUrl() == null) {
            log.warn("No backdrop URL for video: {}", video.getTitle());
            return false;
        }
        return downloadImage(video.getBackdropUrl(), nfoService.getFanartPath(video), force);
    }

    private boolean downloadImage(String imageUrl, Path targetPath, boolean force) {
        try {
            if (!force && Files.exists(targetPath)) {
                log.debug("Cover already exists: {}", targetPath);
                return true;
            }

            String fullUrl = imageUrl.startsWith("http") ? imageUrl : IMAGE_BASE_URL + "/" + imageSize + imageUrl;

            log.debug("Downloading cover: {} -> {}", fullUrl, targetPath);

            Resource resource = restTemplate.getForObject(fullUrl, Resource.class);
            if (resource == null) {
                log.error("Failed to download cover from: {}", fullUrl);
                return false;
            }

            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            log.debug("Downloaded cover: {}", targetPath);
            return true;
        } catch (IOException e) {
            log.error("Failed to download cover from {}: {}", imageUrl, e.getMessage(), e);
            return false;
        }
    }

    private void updateVideoCoverPaths(Video video, boolean posterOk, boolean fanartOk) {
        if (posterOk) {
            video.setCoverArtPath(nfoService.getPosterPath(video).toString());
        }
        if (fanartOk) {
            video.setBackdropLocalPath(nfoService.getFanartPath(video).toString());
        }
    }

}
