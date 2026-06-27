package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.service.ScrapeProgressService;
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
    private final NetEaseLyricsService netEaseLyricsService;
    private final QQMusicService qqMusicService;
    private final MusicBrainzService musicBrainzService;
    private final SystemSettingService settingService;
    private final ScrapeProgressService scrapeProgressService;

    @Value("${hub.music.root-paths:./media-library/music}")
    private String rootPathsConfig;

    public boolean isScrapeEnabled() {
        return settingService.getBoolean("hub.music.scrape.enabled", true);
    }

    private boolean isLyricsFallback() {
        return settingService.getBoolean("hub.music.scrape.lyrics-fallback", true);
    }

    private boolean isCoverFallback() {
        return settingService.getBoolean("hub.music.scrape.cover-fallback", true);
    }

    public MusicTrack scrapeTrack(Long trackId) {
        MusicTrack track = repository.findById(trackId)
                .orElseThrow(() -> new IllegalArgumentException("Track not found: " + trackId));
        return scrapeTrack(track);
    }

    @Transactional
    public MusicTrack scrapeTrack(MusicTrack track) {
        if (!isScrapeEnabled()) {
            log.info("刮削功能已禁用 / Scraping is disabled");
            return track;
        }

        log.debug("开始刮削 / Scraping track: {} - {}", track.getArtist(), track.getTitle());

        if (isLyricsFallback()) {
            scrapeLyrics(track);
        }

        if (isCoverFallback()) {
            scrapeCover(track);
        }

        enrichMetadata(track);

        track.setScrapeStatus("scraped");
        return repository.save(track);
    }

    @Transactional
    public List<MusicTrack> scrapeAll() {
        List<MusicTrack> tracks = repository.findAll();
        List<MusicTrack> needScrape = tracks.stream().filter(this::needsScraping).toList();
        int scraped = 0;

        scrapeProgressService.start("music", needScrape.size());

        for (MusicTrack track : needScrape) {
            try {
                scrapeProgressService.updateItem("music", track.getTitle(), "processing", null);
                scrapeTrack(track);
                scraped++;
                scrapeProgressService.updateItem("music", track.getTitle(), "completed", null);
                Thread.sleep(500);
            } catch (Exception e) {
                log.warn("刮削失败 / Failed to scrape track {}: {}", track.getTitle(), e.getMessage());
                scrapeProgressService.updateItem("music", track.getTitle(), "failed", e.getMessage());
            }
        }

        scrapeProgressService.finish("music");
        if (scraped > 0) {
            log.info("批量刮削完成 / Scrape completed: {}/{} tracks scraped", scraped, tracks.size());
        } else {
            log.debug("批量刮削完成，无需刮削 / Scrape completed: 0/{} tracks need scraping", tracks.size());
        }
        return repository.findAll();
    }

    public boolean needsScraping(MusicTrack track) {
        return needsLyrics(track) || needsCover(track);
    }

    private boolean needsLyrics(MusicTrack track) {
        Path audioPath = Paths.get(track.getFilePath());
        Path lrcPath = audioPath.getParent().resolve(
                audioPath.getFileName().toString().replaceAll("\\.[^.]+$", ".lrc"));

        // 优先检查本地 .lrc 文件是否存在
        if (Files.exists(lrcPath)) {
            return false;
        }

        // 检查数据库中是否有歌词
        if (track.getLyrics() != null && !track.getLyrics().isBlank()) {
            return false;
        }

        return true;
    }

    private boolean needsCover(MusicTrack track) {
        if (track.getCoverArtPath() != null && !track.getCoverArtPath().isBlank()) {
            Path coverPath = Paths.get(track.getCoverArtPath());
            if (Files.exists(coverPath)) {
                return false;
            }
        }
        Path audioPath = Paths.get(track.getFilePath());
        Path coverPath = audioPath.getParent().resolve("cover.jpg");
        return !Files.exists(coverPath);
    }

    private boolean needsMetadata(MusicTrack track) {
        if (track.getMusicBrainzId() == null || track.getMusicBrainzId().isBlank()) {
            return true;
        }
        if (track.getGenre() == null || track.getGenre().isBlank()) {
            return true;
        }
        if (track.getArtistImage() == null || track.getArtistImage().isBlank()) {
            return true;
        }
        return false;
    }

    private void scrapeLyrics(MusicTrack track) {
        String artist = track.getArtist();
        String title = track.getTitle();

        // 优先从QQ音乐获取歌词 / Try QQ Music first
        try {
            String lyrics = qqMusicService.searchLyrics(artist, title);
            if (lyrics != null && !lyrics.isBlank()) {
                track.setLyrics(lyrics);
                track.setLyricsSource("qqmusic");
                saveLyricsFile(track, lyrics);
                log.info("从QQ音乐获取歌词成功 / Lyrics found from QQ Music: {} - {}", artist, title);
                return;
            }
            log.info("QQ音乐未找到歌词 / No lyrics from QQ Music: {} - {}", artist, title);
        } catch (Exception e) {
            log.warn("QQ音乐歌词获取失败 / QQ Music lyrics failed for {} - {}: {}", artist, title, e.getMessage());
        }

        // 回退到网易云 / Fallback to NetEase
        try {
            String lyrics = netEaseLyricsService.searchLyrics(artist, title);
            if (lyrics != null && !lyrics.isBlank()) {
                track.setLyrics(lyrics);
                track.setLyricsSource("netease");
                saveLyricsFile(track, lyrics);
                log.info("从网易云获取歌词成功 / Lyrics found from NetEase: {} - {}", artist, title);
                return;
            }
        } catch (Exception e) {
            log.warn("网易云歌词获取失败 / NetEase lyrics failed for {} - {}: {}", artist, title, e.getMessage());
        }

        log.info("未找到歌词 / No lyrics found for: {} - {}", artist, title);
    }

    private void saveLyricsFile(MusicTrack track, String lyrics) {
        try {
            Path targetDir = organizeTrackFolder(track);
            Path lrcPath = targetDir.resolve(
                    track.getFileName().replaceAll("\\.[^.]+$", ".lrc"));
            Files.writeString(lrcPath, lyrics);
            log.debug("保存歌词文件 / Saved lyrics file: {}", lrcPath);
        } catch (Exception e) {
            log.warn("保存歌词文件失败 / Failed to save lyrics file for: {}", track.getFileName(), e.getMessage());
        }
    }

    private void scrapeCover(MusicTrack track) {
        String artist = track.getArtist();
        String title = track.getTitle();

        // 优先从QQ音乐获取封面 / Try QQ Music first
        try {
            String coverUrl = qqMusicService.searchCoverUrl(artist, title);
            if (coverUrl != null) {
                String coverPath = downloadCover(coverUrl, track);
                if (coverPath != null) {
                    track.setCoverArtPath(coverPath);
                    track.setCoverSource("qqmusic");
                    log.info("从QQ音乐获取封面成功 / Cover found from QQ Music: {} - {}", artist, title);
                    return;
                }
            }
            log.info("QQ音乐未找到封面 / No cover from QQ Music: {} - {}", artist, title);
        } catch (Exception e) {
            log.warn("QQ音乐封面获取失败 / QQ Music cover failed for {} - {}: {}", artist, title, e.getMessage());
        }

        // 回退到网易云 / Fallback to NetEase
        try {
            String coverUrl = netEaseLyricsService.searchCoverUrl(artist, title);
            if (coverUrl != null) {
                String coverPath = downloadCover(coverUrl, track);
                if (coverPath != null) {
                    track.setCoverArtPath(coverPath);
                    track.setCoverSource("netease");
                    log.info("从网易云获取封面成功 / Cover found from NetEase: {} - {}", artist, title);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("网易云封面获取失败 / NetEase cover failed for {} - {}: {}", artist, title, e.getMessage());
        }

        log.debug("未找到封面 / No cover found for: {} - {}", artist, title);
    }

    private void enrichMetadata(MusicTrack track) {
        try {
            musicBrainzService.enrichTrack(track);

            if (track.getArtistImage() == null || track.getArtistImage().isBlank()) {
                scrapeArtistImage(track);
            }

            if (track.getArtistImage() != null && !track.getArtistImage().isBlank()
                    && track.getArtistImage().startsWith("http")) {
                String localPath = downloadArtistImage(track.getArtistImage(), track);
                if (localPath != null) {
                    track.setArtistImage(localPath);
                }
            }
        } catch (Exception e) {
            log.warn("元数据增强失败 / Metadata enrichment failed for {} - {}: {}", track.getArtist(), track.getTitle(), e.getMessage());
        }
    }

    private void scrapeArtistImage(MusicTrack track) {
        String artist = track.getArtist();
        if (artist == null || artist.isBlank()) {
            return;
        }

        try {
            String coverUrl = qqMusicService.searchArtistImage(artist);
            if (coverUrl != null) {
                track.setArtistImage(coverUrl);
                log.info("从QQ音乐获取歌手图片成功 / Artist image found from QQ Music: {}", artist);
                return;
            }
            log.info("QQ音乐未找到歌手图片 / No artist image from QQ Music: {}", artist);
        } catch (Exception e) {
            log.warn("QQ音乐歌手图片获取失败 / QQ Music artist image failed for {}: {}", artist, e.getMessage());
        }

        try {
            String coverUrl = netEaseLyricsService.searchArtistImage(artist);
            if (coverUrl != null) {
                track.setArtistImage(coverUrl);
                log.info("从网易云获取歌手图片成功 / Artist image found from NetEase: {}", artist);
                return;
            }
            log.info("网易云未找到歌手图片 / No artist image from NetEase: {}", artist);
        } catch (Exception e) {
            log.warn("网易云歌手图片获取失败 / NetEase artist image failed for {}: {}", artist, e.getMessage());
        }
    }

    private String downloadArtistImage(String imageUrl, MusicTrack track) {
        try {
            Path targetDir = organizeTrackFolder(track);
            Path artistImagePath = targetDir.resolve("artist.jpg");

            if (Files.exists(artistImagePath)) {
                return artistImagePath.toAbsolutePath().toString();
            }

            try (InputStream in = new URL(imageUrl).openStream()) {
                Files.copy(in, artistImagePath);
                return artistImagePath.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            log.warn("下载歌手图片失败 / Failed to download artist image: {}", e.getMessage());
            return null;
        }
    }

    private Path organizeTrackFolder(MusicTrack track) {
        try {
            String firstRootPath = rootPathsConfig.split(",")[0].trim();
            Path audioPath = Paths.get(track.getFilePath());
            String folderName = track.getArtist() + " - " + track.getTitle();
            Path targetDir = Paths.get(firstRootPath, folderName);

            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            Path targetFile = targetDir.resolve(track.getFileName());
            if (!audioPath.equals(targetFile) && Files.exists(audioPath) && !Files.exists(targetFile)) {
                Files.move(audioPath, targetFile);
                track.setFilePath(targetFile.toAbsolutePath().toString());
                log.debug("移动歌曲文件到 / Moved audio to: {}", targetDir);
            }

            return targetDir;
        } catch (Exception e) {
            log.warn("整理文件夹失败 / Failed to organize folder for {}: {}", track.getFileName(), e.getMessage());
            return Paths.get(track.getFilePath()).getParent();
        }
    }

    private String downloadCover(String imageUrl, MusicTrack track) {
        try {
            Path targetDir = organizeTrackFolder(track);
            Path coverPath = targetDir.resolve("cover.jpg");

            if (Files.exists(coverPath)) {
                return coverPath.toAbsolutePath().toString();
            }

            try (InputStream in = new URL(imageUrl).openStream()) {
                Files.copy(in, coverPath);
                return coverPath.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            log.warn("下载封面失败 / Failed to download cover: {}", e.getMessage());
            return null;
        }
    }

    public long countPendingScrape() {
        return repository.findAll().stream()
                .filter(this::needsScraping)
                .count();
    }
}
