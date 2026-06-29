package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.service.SystemSettingService;
import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.model.Playlist;
import com.fryfrog.hub.music.model.PlaylistTrack;
import com.fryfrog.hub.music.repository.MusicTrackRepository;
import com.fryfrog.hub.music.repository.PlaylistRepository;
import com.fryfrog.hub.music.repository.PlaylistTrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class MusicMetadataService {

    private final MusicTrackRepository repository;
    private final PlaylistRepository playlistRepository;
    private final PlaylistTrackRepository playlistTrackRepository;
    private final MusicScrapeService scrapeService;
    private final SystemSettingService settingService;
    private final QQMusicService qqMusicService;
    private final NetEaseLyricsService netEaseLyricsService;

    @Value("${hub.music.root-paths:./media-library/music}")
    private String rootPathsConfig;

    public List<String> getRootPaths() {
        return Arrays.stream(rootPathsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    public String getFirstRootPath() {
        List<String> paths = getRootPaths();
        return paths.isEmpty() ? "./media-library/music" : paths.get(0);
    }

    public boolean isAutoWriteback() {
        return settingService.getBoolean("hub.music.auto-writeback", true);
    }

    public boolean isUseFolderStructure() {
        return settingService.getBoolean("hub.music.use-folder-structure", true);
    }

    public String getDefaultArtist() {
        return settingService.getValue("hub.music.default-artist", "");
    }

    public boolean isAutoScrape() {
        return settingService.getBoolean("hub.music.scrape.auto-scrape", false);
    }

    private static final Set<String> SUPPORTED_FORMATS = Set.of("mp3", "flac", "ogg", "wav", "aac", "m4a");

    public List<MusicTrack> getAllTracks() {
        return repository.findAll();
    }

    public MusicTrack getTrackById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MusicTrack", "id", id));
    }

    public List<MusicTrack> searchByTitle(String title) {
        return repository.findByTitleContainingIgnoreCase(title);
    }

    public List<MusicTrack> searchByArtist(String artist) {
        return repository.findByArtistContainingIgnoreCase(artist);
    }

    public MusicTrack setFavorite(Long id, boolean status) {
        MusicTrack track = getTrackById(id);
        track.setFavorite(status);
        return repository.save(track);
    }

    public List<MusicTrack> getFavorites() {
        return repository.findByFavoriteTrue();
    }

    @Transactional
    public MusicTrack recordPlay(Long id) {
        MusicTrack track = getTrackById(id);
        track.setPlayCount((track.getPlayCount() != null ? track.getPlayCount() : 0) + 1);
        track.setLastPlayedAt(LocalDateTime.now());
        return repository.save(track);
    }

    public List<MusicTrack> getRecentlyPlayed() {
        return repository.findByLastPlayedAtIsNotNullOrderByLastPlayedAtDesc();
    }

    public List<MusicTrack> getMostPlayed() {
        return repository.findByPlayCountGreaterThanOrderByPlayCountDesc(0);
    }

    public List<MusicTrack> getRecentlyAdded() {
        return repository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    public Map<String, List<MusicTrack>> getRecommendations() {
        Map<String, List<MusicTrack>> recommendations = new LinkedHashMap<>();

        List<MusicTrack> favorites = repository.findByFavoriteTrue();
        if (!favorites.isEmpty()) {
            Set<String> favArtists = favorites.stream()
                    .map(MusicTrack::getArtist)
                    .filter(a -> a != null && !a.isBlank())
                    .collect(Collectors.toSet());
            List<MusicTrack> similar = favArtists.stream()
                    .flatMap(a -> repository.findByArtist(a).stream())
                    .filter(t -> !favorites.contains(t))
                    .distinct()
                    .limit(20)
                    .toList();
            if (!similar.isEmpty()) {
                recommendations.put("猜你喜欢", similar);
            }
        }

        List<MusicTrack> hot = repository.findByPlayCountGreaterThanOrderByPlayCountDesc(0);
        if (!hot.isEmpty()) {
            recommendations.put("热门歌曲", hot.stream().limit(20).toList());
        }

        List<MusicTrack> unplayed = repository.findUnplayedTracks();
        if (!unplayed.isEmpty()) {
            recommendations.put("新歌发现", unplayed.stream().limit(20).toList());
        }

        List<String> genres = repository.findDistinctGenres();
        for (String genre : genres) {
            List<MusicTrack> genreTracks = repository.findByGenre(genre);
            if (genreTracks.size() >= 3) {
                recommendations.put(genre, genreTracks.stream().limit(10).toList());
            }
        }

        return recommendations;
    }

    public String scrapeArtistImage(Long trackId) {
        MusicTrack track = getTrackById(trackId);
        String artist = track.getArtist();
        if (artist == null || artist.isBlank()) {
            return null;
        }

        String safeArtist = artist.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        Path artistDir = Paths.get(getFirstRootPath(), safeArtist);
        Path artistImagePath = artistDir.resolve("artist.jpg");

        if (Files.exists(artistImagePath)) {
            return artistImagePath.toAbsolutePath().toString();
        }

        String imageUrl = null;
        try {
            imageUrl = qqMusicService.searchArtistImage(artist);
        } catch (Exception e) {
            log.debug("QQ音乐歌手图片获取失败 / QQ Music artist image failed: {}", e.getMessage());
        }

        if (imageUrl == null) {
            try {
                imageUrl = netEaseLyricsService.searchArtistImage(artist);
            } catch (Exception e) {
                log.debug("网易云歌手图片获取失败 / NetEase artist image failed: {}", e.getMessage());
            }
        }

        if (imageUrl == null) {
            log.info("未找到歌手图片 / No artist image found for: {}", artist);
            return null;
        }

        try {
            if (!Files.exists(artistDir)) {
                Files.createDirectories(artistDir);
            }
            try (InputStream in = new URL(imageUrl).openStream()) {
                Files.copy(in, artistImagePath);
                log.info("歌手图片下载成功 / Artist image saved for: {} -> {}", artist, artistImagePath);
                return artistImagePath.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            log.warn("歌手图片下载失败 / Failed to download artist image for {}: {}", artist, e.getMessage());
            return null;
        }
    }

    public String getArtistImagePath(Long trackId) {
        MusicTrack track = getTrackById(trackId);
        String artist = track.getArtist();
        if (artist == null || artist.isBlank()) {
            return null;
        }
        String safeArtist = artist.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        Path artistImagePath = Paths.get(getFirstRootPath(), safeArtist, "artist.jpg");
        return Files.exists(artistImagePath) ? artistImagePath.toAbsolutePath().toString() : null;
    }

    @Transactional
    public int reorganizeAllTracks() {
        int moved = 0;
        List<MusicTrack> tracks = repository.findAll();
        String firstRootPath = getFirstRootPath();

        for (MusicTrack track : tracks) {
            try {
                Path audioPath = Paths.get(track.getFilePath());
                if (!Files.exists(audioPath)) {
                    continue;
                }

                String artist = (track.getArtist() != null && !track.getArtist().isBlank())
                        ? track.getArtist() : "未知歌手";
                String album = (track.getAlbum() != null && !track.getAlbum().isBlank())
                        ? track.getAlbum() : "未知专辑";
                String safeArtist = sanitizeFileName(artist);
                String safeAlbum = sanitizeFileName(album);

                Path targetDir = Paths.get(firstRootPath, safeArtist, safeAlbum);
                Path targetFile = targetDir.resolve(track.getFileName());

                if (audioPath.equals(targetFile)) {
                    continue;
                }

                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }

                if (Files.exists(targetFile)) {
                    log.warn("目标文件已存在，跳过 / Target file exists, skipping: {}", targetFile);
                    continue;
                }

                Files.move(audioPath, targetFile);
                track.setFilePath(targetFile.toAbsolutePath().toString());
                repository.save(track);
                moved++;
                log.info("整理完成 / Moved: {} -> {}", audioPath, targetFile);

                Path sourceDir = audioPath.getParent();
                moveAuxiliaryFiles(sourceDir, targetDir, track.getFileName());
                cleanEmptyDirectory(sourceDir);
            } catch (Exception e) {
                log.warn("整理失败 / Failed to reorganize: {} - {}", track.getTitle(), e.getMessage());
            }
        }

        log.info("整理完成，共移动 {} 首歌曲 / Reorganize complete, moved {} tracks", moved, moved);
        return moved;
    }

    private void moveAuxiliaryFiles(Path sourceDir, Path targetDir, String audioFileName) {
        if (!Files.exists(sourceDir) || !Files.isDirectory(sourceDir)) {
            return;
        }

        String baseName = audioFileName.contains(".")
                ? audioFileName.substring(0, audioFileName.lastIndexOf('.'))
                : audioFileName;

        try (var stream = Files.list(sourceDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.endsWith(".lrc") || name.endsWith(".jpg")
                                || name.endsWith(".png") || name.endsWith(".nfo");
                    })
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.startsWith(baseName) || name.equals("cover.jpg")
                                || name.equals("cover.png");
                    })
                    .forEach(file -> {
                        try {
                            Path targetFile = targetDir.resolve(file.getFileName());
                            if (!Files.exists(targetFile)) {
                                Files.move(file, targetFile);
                                log.info("移动附属文件 / Moved auxiliary: {}", file.getFileName());
                            }
                        } catch (Exception e) {
                            log.warn("移动附属文件失败 / Failed to move auxiliary: {}", file.getFileName());
                        }
                    });
        } catch (Exception e) {
            log.warn("列出附属文件失败 / Failed to list auxiliary files: {}", e.getMessage());
        }
    }

    private void cleanEmptyDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }

        try (var stream = Files.list(dir)) {
            if (stream.findFirst().isEmpty()) {
                Files.delete(dir);
                log.info("删除空目录 / Removed empty directory: {}", dir);
            }
        } catch (Exception e) {
            // 忽略删除失败
        }
    }

    public List<Playlist> getAllPlaylists() {
        return playlistRepository.findAll();
    }

    public Playlist getPlaylistById(Long id) {
        return playlistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist", "id", id));
    }

    public Playlist createPlaylist(String name, String description) {
        Playlist playlist = new Playlist();
        playlist.setName(name);
        playlist.setDescription(description);
        return playlistRepository.save(playlist);
    }

    public Playlist updatePlaylist(Long id, String name, String description) {
        Playlist playlist = getPlaylistById(id);
        if (name != null) playlist.setName(name);
        if (description != null) playlist.setDescription(description);
        return playlistRepository.save(playlist);
    }

    public void deletePlaylist(Long id) {
        playlistRepository.deleteById(id);
    }

    public List<PlaylistTrack> getPlaylistTracks(Long playlistId) {
        return playlistTrackRepository.findByPlaylistIdOrderByPositionAsc(playlistId);
    }

    @Transactional
    public PlaylistTrack addTrackToPlaylist(Long playlistId, Long trackId) {
        Playlist playlist = getPlaylistById(playlistId);
        MusicTrack track = getTrackById(trackId);

        Optional<PlaylistTrack> existing = playlistTrackRepository.findByPlaylistIdAndTrackId(playlistId, trackId);
        if (existing.isPresent()) {
            return existing.get();
        }

        int position = playlistTrackRepository.countByPlaylistId(playlistId);
        PlaylistTrack pt = new PlaylistTrack();
        pt.setPlaylist(playlist);
        pt.setTrack(track);
        pt.setPosition(position);
        return playlistTrackRepository.save(pt);
    }

    @Transactional
    public void removeTrackFromPlaylist(Long playlistId, Long trackId) {
        playlistTrackRepository.deleteByPlaylistIdAndTrackId(playlistId, trackId);
    }

    public MusicTrack extractAndSaveMetadata(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }

            String absolutePath = file.getAbsolutePath();
            MusicTrack existing = repository.findByFilePath(absolutePath).orElse(null);
            MusicTrack track = existing != null ? existing : new MusicTrack();

            track.setFilePath(absolutePath);
            track.setFileName(file.getName());
            track.setFileSize(file.length());

            String fileName = file.getName().replaceAll("\\.[^.]+$", "");
            track.setTitle(parseTitleFromFileName(fileName));
            track.setArtist(parseArtistFromFileName(fileName));

            org.jaudiotagger.audio.AudioFile audioFile = org.jaudiotagger.audio.AudioFileIO.read(file);
            track.setDurationSeconds((long) audioFile.getAudioHeader().getTrackLength());
            track.setBitrateKbps(parseInteger(audioFile.getAudioHeader().getBitRate()));
            track.setFormat(audioFile.getAudioHeader().getFormat());

            try {
                org.jaudiotagger.tag.Tag tag = audioFile.getTagOrCreateDefault();

                String tagTitle = tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE);
                String tagArtist = tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST);
                if (tagTitle != null && !tagTitle.isBlank() && !isGarbled(tagTitle)) {
                    track.setTitle(tagTitle);
                }
                if (tagArtist != null && !tagArtist.isBlank() && !isGarbled(tagArtist)) {
                    track.setArtist(tagArtist);
                }

                track.setAlbum(tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM));
                track.setAlbumArtist(tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST));
                track.setTrackNumber(parseInteger(tag.getFirst(org.jaudiotagger.tag.FieldKey.TRACK)));
                track.setDiscNumber(parseInteger(tag.getFirst(org.jaudiotagger.tag.FieldKey.DISC_NO)));
                track.setYear(parseInteger(tag.getFirst(org.jaudiotagger.tag.FieldKey.YEAR)));
                track.setGenre(tag.getFirst(org.jaudiotagger.tag.FieldKey.GENRE));

                String lyrics = tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS);
                if (lyrics == null || lyrics.isEmpty()) {
                    lyrics = tag.getFirst("USLT");
                }
                if (lyrics != null && (lyrics.startsWith("krc1") || lyrics.contains("\ufffd"))) {
                    lyrics = null;
                }
                track.setLyrics(lyrics);

                try {
                    org.jaudiotagger.tag.images.Artwork artwork = tag.getFirstArtwork();
                    if (artwork != null) {
                        byte[] data = artwork.getBinaryData();
                        String ext = guessImageExtension(data);
                        Path coverPath = Paths.get(absolutePath).getParent().resolve("cover" + ext);
                        Files.write(coverPath, data);
                        track.setCoverArtPath(coverPath.toAbsolutePath().toString());
                        track.setCoverSource("embedded");
                    }
                } catch (Exception e) {
                    log.warn("提取封面失败 / Failed to extract cover art from: {}", file.getName(), e);
                }
            } catch (Exception e) {
                log.debug("标签读取失败，仅从文件名解析 / Tag read failed for {}, parsing from filename: {}", file.getName(), e.getMessage());
            }

            if (track.getCoverArtPath() == null) {
                Path parentDir = Paths.get(absolutePath).getParent();
                for (String coverName : List.of("cover.jpg", "cover.jpeg", "cover.png", "cover.webp")) {
                    Path coverFile = parentDir.resolve(coverName);
                    if (Files.exists(coverFile)) {
                        track.setCoverArtPath(coverFile.toAbsolutePath().toString());
                        break;
                    }
                }
            }

            inferFromFolderStructure(track, file);

            Path targetDir = organizeTrackFolder(track);

            if (track.getArtist() != null && !track.getArtist().isBlank()) {
                String safeArtist = track.getArtist().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                Path artistImagePath = Paths.get(getFirstRootPath(), safeArtist, "artist.jpg");
                if (!Files.exists(artistImagePath)) {
                    try {
                        String imageUrl = qqMusicService.searchArtistImage(track.getArtist());
                        if (imageUrl == null) {
                            imageUrl = netEaseLyricsService.searchArtistImage(track.getArtist());
                        }
                        if (imageUrl != null) {
                            try (InputStream in = new URL(imageUrl).openStream()) {
                                Files.copy(in, artistImagePath);
                                log.debug("歌手图片下载成功 / Artist image saved for: {}", track.getArtist());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("歌手图片获取失败 / Failed to get artist image for {}: {}", track.getArtist(), e.getMessage());
                    }
                }
            }

            return repository.save(track);
        } catch (Exception e) {
            log.error("提取元数据失败 / Failed to extract metadata from: {}", filePath, e);
            throw new RuntimeException("Failed to extract metadata: " + e.getMessage(), e);
        }
    }

    private Path organizeTrackFolder(MusicTrack track) {
        try {
            String firstRootPath = getFirstRootPath();
            Path audioPath = Paths.get(track.getFilePath());
            String artist = (track.getArtist() != null && !track.getArtist().isBlank())
                    ? track.getArtist() : "未知歌手";
            String album = (track.getAlbum() != null && !track.getAlbum().isBlank())
                    ? track.getAlbum() : "未知专辑";
            Path targetDir = Paths.get(firstRootPath, sanitizeFileName(artist), sanitizeFileName(album));

            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            Path targetFile = targetDir.resolve(track.getFileName());
            if (!audioPath.equals(targetFile) && Files.exists(audioPath) && !Files.exists(targetFile)) {
                Files.move(audioPath, targetFile);
                track.setFilePath(targetFile.toAbsolutePath().toString());
            }

            return targetDir;
        } catch (Exception e) {
            log.warn("整理文件夹失败 / Failed to organize folder for {}: {}", track.getFileName(), e.getMessage());
            return Paths.get(track.getFilePath()).getParent();
        }
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    @Transactional
    public int cleanupInvalidRecords() {
        int removed = 0;
        int pageNum = 0;
        final int pageSize = 100;
        org.springframework.data.domain.Page<MusicTrack> page;

        do {
            page = repository.findAll(org.springframework.data.domain.PageRequest.of(pageNum++, pageSize));
            List<Long> idsToDelete = new ArrayList<>();
            for (MusicTrack track : page.getContent()) {
                if (track.getFilePath() == null || !Files.exists(Paths.get(track.getFilePath()))) {
                    log.debug("Removing invalid record: {} (path: {})", track.getTitle(), track.getFilePath());
                    idsToDelete.add(track.getId());
                }
            }
            if (!idsToDelete.isEmpty()) {
                repository.deleteAllByIdInBatch(idsToDelete);
                removed += idsToDelete.size();
            }
        } while (page.hasNext());

        if (removed > 0) {
            log.info("Music cleanup completed: removed {} invalid records", removed);
        }
        return removed;
    }

    public void scanFromRoot() {
        scanDirectory(getFirstRootPath());
    }

    public void scanDirectory(String directoryPath) {
        try {
            cleanupInvalidRecords();
            Path dir = Paths.get(directoryPath);
            if (!Files.isDirectory(dir)) {
                throw new IllegalArgumentException("Not a directory: " + directoryPath);
            }

            try (Stream<Path> paths = Files.walk(dir)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase();
                            String extension = name.substring(name.lastIndexOf('.') + 1);
                            return SUPPORTED_FORMATS.contains(extension);
                        })
                        .forEach(path -> {
                            try {
                                String absolutePath = path.toAbsolutePath().toString();

                                if (repository.findByFilePath(absolutePath).isPresent()) {
                                    log.debug("Skipping already indexed: {}", path.getFileName());
                                    return;
                                }

                                MusicTrack saved = extractAndSaveMetadata(absolutePath);

                                if (saved.getFilePath() != null && !saved.getFilePath().equals(absolutePath)) {
                                    repository.findByFilePath(saved.getFilePath()).ifPresent(existing -> {
                                        if (!existing.getId().equals(saved.getId())) {
                                            repository.delete(existing);
                                            log.info("Removed duplicate record: {}", existing.getTitle());
                                        }
                                    });
                                }

                                log.debug("Indexed: {}", path.getFileName());
                            } catch (Exception e) {
                                log.warn("Failed to index: {}", path.getFileName(), e);
                            }
                        });
            }
        } catch (Exception e) {
            log.error("Failed to scan directory: {}", directoryPath);
            throw new RuntimeException("Failed to scan directory: " + e.getMessage(), e);
        }

        reorganizeAllTracks();
    }

    public String parseTitleFromFileName(String fileName) {
        String[] parts = fileName.split("\\s*-\\s*", 2);
        return parts.length > 1 ? parts[1].trim() : fileName.trim();
    }

    public String parseArtistFromFileName(String fileName) {
        String[] parts = fileName.split("\\s*-\\s*", 2);
        return parts.length > 1 ? parts[0].trim() : "";
    }

    private boolean isGarbled(String text) {
        if (text == null || text.isBlank()) return false;
        long mojibakeCount = text.chars()
                .filter(c -> c >= 0x00C0 && c <= 0x00FF)
                .count();
        return mojibakeCount > text.length() * 0.3;
    }

    private void inferFromFolderStructure(MusicTrack track, File file) {
        if (!isUseFolderStructure()) return;

        Path musicRoot = Paths.get(getFirstRootPath()).toAbsolutePath();
        Path parent = file.toPath().getParent();
        Path grandparent = parent != null ? parent.getParent() : null;

        boolean isUnderRoot = getRootPaths().stream()
                .anyMatch(root -> file.toPath().toAbsolutePath().startsWith(Paths.get(root).toAbsolutePath()));
        if (!isUnderRoot) return;

        if (track.getArtist() == null || track.getArtist().isBlank()) {
            if (grandparent != null && !grandparent.toAbsolutePath().equals(musicRoot)
                    && grandparent.getParent() != null
                    && grandparent.getParent().toAbsolutePath().equals(musicRoot)) {
                track.setArtist(parent.getFileName().toString());
            }
        }

        if ((track.getAlbum() == null || track.getAlbum().isBlank()) && parent != null) {
            String parentName = parent.getFileName().toString();
            if (!parentName.equals(track.getArtist())) {
                Path parentParent = parent.getParent();
                if (parentParent == null || !parentParent.toAbsolutePath().equals(musicRoot)) {
                    track.setAlbum(parentName);
                }
            }
        }

        if ((track.getArtist() == null || track.getArtist().isBlank()) && getDefaultArtist() != null && !getDefaultArtist().isBlank()) {
            track.setArtist(getDefaultArtist());
        }
    }

    private void saveLyricsFile(File audioFile, String lyrics) {
        try {
            Path lyricsPath = audioFile.toPath().resolveSibling(
                    audioFile.getName().replaceAll("\\.[^.]+$", ".lrc"));
            if (!lyrics.endsWith("\n")) {
                lyrics += "\n";
            }
            Files.writeString(lyricsPath, lyrics);
        } catch (IOException e) {
            log.warn("Failed to save lyrics file for: {}", audioFile.getName(), e);
        }
    }

    private String saveCoverFile(File audioFile, byte[] coverData) {
        try {
            Path coverDir = audioFile.toPath().getParent();
            String ext = guessImageExtension(coverData);
            Path coverPath = coverDir.resolve("cover" + ext);
            Files.write(coverPath, coverData);
            return coverPath.toAbsolutePath().toString();
        } catch (IOException e) {
            log.warn("Failed to save cover file for: {}", audioFile.getName(), e);
            return null;
        }
    }

    private String guessImageExtension(byte[] data) {
        if (data == null || data.length < 4) {
            return ".jpg";
        }
        if (data[0] == (byte) 0x89 && data[1] == (byte) 0x50 && data[2] == (byte) 0x4E && data[3] == (byte) 0x47) {
            return ".png";
        }
        if (data[0] == (byte) 0x52 && data[1] == (byte) 0x49 && data[2] == (byte) 0x46 && data[3] == (byte) 0x46) {
            return ".webp";
        }
        if (data[0] == (byte) 0x47 && data[1] == (byte) 0x49 && data[2] == (byte) 0x46) {
            return ".gif";
        }
        return ".jpg";
    }

    private void writeMetadataToFile(File file, MusicTrack track) {
        try {
            org.jaudiotagger.audio.AudioFile audioFile = org.jaudiotagger.audio.AudioFileIO.read(file);
            org.jaudiotagger.tag.Tag tag = audioFile.getTagOrCreateDefault();

            boolean changed = false;

            if (isDifferent(tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE), track.getTitle())) {
                tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, track.getTitle());
                changed = true;
            }
            if (isDifferent(tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST), track.getArtist())) {
                tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, track.getArtist());
                changed = true;
            }
            if (isDifferent(tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM), track.getAlbum())) {
                tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM, track.getAlbum());
                changed = true;
            }
            if (isDifferent(tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST), track.getAlbumArtist())) {
                tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST, track.getAlbumArtist());
                changed = true;
            }

            String fileTrack = tag.getFirst(org.jaudiotagger.tag.FieldKey.TRACK);
            String dbTrack = track.getTrackNumber() != null ? String.valueOf(track.getTrackNumber()) : null;
            if (isDifferent(fileTrack, dbTrack)) {
                tag.setField(org.jaudiotagger.tag.FieldKey.TRACK, dbTrack);
                changed = true;
            }

            String fileDisc = tag.getFirst(org.jaudiotagger.tag.FieldKey.DISC_NO);
            String dbDisc = track.getDiscNumber() != null ? String.valueOf(track.getDiscNumber()) : null;
            if (isDifferent(fileDisc, dbDisc)) {
                tag.setField(org.jaudiotagger.tag.FieldKey.DISC_NO, dbDisc);
                changed = true;
            }

            String fileYear = tag.getFirst(org.jaudiotagger.tag.FieldKey.YEAR);
            String dbYear = track.getYear() != null ? String.valueOf(track.getYear()) : null;
            if (isDifferent(fileYear, dbYear)) {
                tag.setField(org.jaudiotagger.tag.FieldKey.YEAR, dbYear);
                changed = true;
            }
            if (isDifferent(tag.getFirst(org.jaudiotagger.tag.FieldKey.GENRE), track.getGenre())) {
                tag.setField(org.jaudiotagger.tag.FieldKey.GENRE, track.getGenre());
                changed = true;
            }

            if (changed) {
                audioFile.commit();
                log.info("Wrote metadata to file: {}", file.getName());
            }
        } catch (Exception e) {
            log.warn("Failed to write metadata to file: {}", file.getName(), e);
        }
    }

    private boolean isDifferent(String fileValue, String dbValue) {
        String fv = (fileValue == null || fileValue.isBlank()) ? null : fileValue.trim();
        String dv = (dbValue == null || dbValue.isBlank()) ? null : dbValue.trim();
        if (fv == null && dv == null) return false;
        if (fv == null || dv == null) return true;
        return !fv.equals(dv);
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            if (value.contains("/")) {
                value = value.split("/")[0];
            }
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
