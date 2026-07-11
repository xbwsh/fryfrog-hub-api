package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.service.SystemSettingService;
import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.repository.MusicTrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MusicScrapeService {

    private final MusicTrackRepository repository;
    private final QQMusicService qqMusicService;
    private final NetEaseLyricsService netEaseLyricsService;
    private final SystemSettingService settingService;

    @Value("${hub.music.root-paths:}")
    private String rootPathsConfig;

    public boolean isScrapeEnabled() {
        return settingService.getBoolean("scrape.auto-scrape", true);
    }

    public boolean needsScraping(MusicTrack track) {
        return needsCover(track) || needsArtistImage(track);
    }

    private boolean needsCover(MusicTrack track) {
        if (track.getCoverArtPath() != null && !track.getCoverArtPath().isBlank()) {
            Path coverPath = Paths.get(track.getCoverArtPath());
            if (Files.exists(coverPath)) {
                return false;
            }
        }
        Path audioPath = Paths.get(track.getFilePath());
        Path parentDir = audioPath.getParent();
        if (parentDir == null) {
            return false;
        }
        for (String ext : List.of("cover.jpg", "cover.jpeg", "cover.png", "cover.webp")) {
            if (Files.exists(parentDir.resolve(ext))) {
                return false;
            }
        }
        return true;
    }

    private boolean needsArtistImage(MusicTrack track) {
        if (track.getArtist() == null || track.getArtist().isBlank()) {
            return false;
        }
        String safeArtist = track.getArtist().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        Path artistImagePath = Paths.get(firstRootPath(), safeArtist, "artist.jpg");
        return !Files.exists(artistImagePath);
    }

    @Transactional
    public MusicTrack scrapeTrack(Long trackId) {
        MusicTrack track = repository.findById(trackId)
                .orElseThrow(() -> new IllegalArgumentException("Track not found: " + trackId));
        return scrapeTrack(track);
    }

    @Transactional
    public MusicTrack scrapeTrack(MusicTrack track) {
        if (!isScrapeEnabled()) {
            return track;
        }

        scrapeCover(track);
        scrapeArtistImage(track);

        return repository.save(track);
    }

    private void scrapeCover(MusicTrack track) {
        if (!needsCover(track)) {
            return;
        }

        String artist = track.getArtist();
        String title = track.getTitle();

        String coverUrl = null;
        try {
            coverUrl = qqMusicService.searchCoverUrl(artist, title);
        } catch (Exception e) {
            log.debug("QQ Music cover failed: {}", e.getMessage());
        }

        if (coverUrl == null) {
            try {
                coverUrl = netEaseLyricsService.searchCoverUrl(artist, title);
            } catch (Exception e) {
                log.debug("NetEase cover failed: {}", e.getMessage());
            }
        }

        if (coverUrl != null) {
            downloadCover(coverUrl, track);
        }
    }

    private void scrapeArtistImage(MusicTrack track) {
        if (!needsArtistImage(track)) {
            return;
        }

        String artist = track.getArtist();
        String imageUrl = null;

        try {
            imageUrl = qqMusicService.searchArtistImage(artist);
        } catch (Exception e) {
            log.debug("QQ Music artist image failed: {}", e.getMessage());
        }

        if (imageUrl == null) {
            try {
                imageUrl = netEaseLyricsService.searchArtistImage(artist);
            } catch (Exception e) {
                log.debug("NetEase artist image failed: {}", e.getMessage());
            }
        }

        if (imageUrl != null) {
            downloadArtistImage(imageUrl, artist);
        }
    }

    private void downloadCover(String imageUrl, MusicTrack track) {
        try {
            Path audioPath = Paths.get(track.getFilePath());
            Path coverPath = audioPath.getParent().resolve("cover.jpg");
            if (Files.exists(coverPath)) {
                return;
            }
            try (InputStream in = new URL(imageUrl).openStream()) {
                Files.copy(in, coverPath);
                track.setCoverArtPath(coverPath.toAbsolutePath().toString());
                track.setCoverSource("online");
            }
        } catch (Exception e) {
            log.debug("Failed to download cover: {}", e.getMessage());
        }
    }

    private void downloadArtistImage(String imageUrl, String artist) {
        try {
            String safeArtist = artist.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            Path artistDir = Paths.get(firstRootPath(), safeArtist);
            Path artistImagePath = artistDir.resolve("artist.jpg");
            if (Files.exists(artistImagePath)) {
                return;
            }
            if (!Files.exists(artistDir)) {
                Files.createDirectories(artistDir);
            }
            try (InputStream in = new URL(imageUrl).openStream()) {
                Files.copy(in, artistImagePath);
            }
        } catch (Exception e) {
            log.debug("Failed to download artist image: {}", e.getMessage());
        }
    }

    private String firstRootPath() {
        return rootPathsConfig.split(",")[0].trim();
    }
}
