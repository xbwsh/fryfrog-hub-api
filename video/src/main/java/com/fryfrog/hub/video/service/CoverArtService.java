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

    @Value("${hub.tmdb.image-size:original}")
    private String imageSize;

    private static final String IMAGE_BASE_URL = "https://image.tmdb.org/t/p";

    public boolean downloadPoster(Video video) {
        if (video.getPosterUrl() == null) {
            log.warn("No poster URL for video: {}", video.getTitle());
            return false;
        }

        return downloadImage(video.getPosterUrl(), nfoService.getPosterPath(video));
    }

    public boolean downloadFanart(Video video) {
        if (video.getBackdropUrl() == null) {
            log.warn("No backdrop URL for video: {}", video.getTitle());
            return false;
        }

        return downloadImage(video.getBackdropUrl(), nfoService.getFanartPath(video));
    }

    public boolean downloadAllCovers(Video video) {
        boolean posterOk = downloadPoster(video);
        boolean fanartOk = downloadFanart(video);

        if (posterOk || fanartOk) {
            updateVideoCoverPaths(video, posterOk, fanartOk);
        }

        return posterOk || fanartOk;
    }

    private boolean downloadImage(String imageUrl, Path targetPath) {
        try {
            if (Files.exists(targetPath)) {
                log.info("Cover already exists: {}", targetPath);
                return true;
            }

            String fullUrl = imageUrl.startsWith("http") ? imageUrl : IMAGE_BASE_URL + "/" + imageSize + imageUrl;

            log.info("Downloading cover: {} -> {}", fullUrl, targetPath);

            Resource resource = restTemplate.getForObject(fullUrl, Resource.class);
            if (resource == null) {
                log.error("Failed to download cover from: {}", fullUrl);
                return false;
            }

            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Downloaded cover: {}", targetPath);
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
    }

    public Resource getCoverArt(Long videoId, com.fryfrog.hub.video.repository.VideoRepository repository) {
        Video video = repository.findById(videoId).orElse(null);
        if (video == null) {
            return null;
        }

        Path posterPath = nfoService.getPosterPath(video);
        if (Files.exists(posterPath)) {
            return new FileSystemResource(posterPath.toFile());
        }

        if (video.getCoverArtPath() != null) {
            Path coverPath = Paths.get(video.getCoverArtPath());
            if (Files.exists(coverPath)) {
                return new FileSystemResource(coverPath.toFile());
            }
        }

        return null;
    }
}
