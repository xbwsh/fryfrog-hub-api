package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.music.dto.LyricsResult;
import com.fryfrog.hub.music.dto.MusicBrainzResult;
import com.fryfrog.hub.music.metadata.MusicMetadataProviderManager;
import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.repository.MusicTrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class MusicMetadataService {

    private final MusicTrackRepository repository;
    private final MusicMetadataProviderManager metadataProviderManager;
    private final LyricsScrapingService lyricsScrapingService;

    @Value("${hub.music.root-path}")
    private String rootPath;

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

    public MusicTrack extractAndSaveMetadata(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }

            String absolutePath = file.getAbsolutePath();
            MusicTrack existing = repository.findByFilePath(absolutePath).orElse(null);

            org.jaudiotagger.audio.AudioFile audioFile = org.jaudiotagger.audio.AudioFileIO.read(file);
            org.jaudiotagger.tag.Tag tag = audioFile.getTagOrCreateDefault();

            MusicTrack track = existing != null ? existing : new MusicTrack();

            track.setTitle(tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE));
            track.setArtist(tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST));
            track.setAlbum(tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM));
            track.setAlbumArtist(tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST));
            track.setTrackNumber(parseInteger(tag.getFirst(org.jaudiotagger.tag.FieldKey.TRACK)));
            track.setDiscNumber(parseInteger(tag.getFirst(org.jaudiotagger.tag.FieldKey.DISC_NO)));
            track.setYear(parseInteger(tag.getFirst(org.jaudiotagger.tag.FieldKey.YEAR)));
            track.setGenre(tag.getFirst(org.jaudiotagger.tag.FieldKey.GENRE));
            track.setFilePath(absolutePath);
            track.setFileName(file.getName());
            track.setFileSize(file.length());
            track.setDurationSeconds((long) audioFile.getAudioHeader().getTrackLength());
            track.setBitrateKbps(parseInteger(audioFile.getAudioHeader().getBitRate()));
            track.setFormat(audioFile.getAudioHeader().getFormat());

            String lyrics = tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS);
            if (lyrics == null || lyrics.isEmpty()) {
                lyrics = tag.getFirst("USLT");
            }
            track.setLyrics(lyrics);

            try {
                org.jaudiotagger.tag.images.Artwork artwork = tag.getFirstArtwork();
                if (artwork != null) {
                    Path coverDir = Paths.get(rootPath, ".cache", "covers");
                    Files.createDirectories(coverDir);
                    String coverFileName = file.getName().replaceAll("\\.[^.]+$", ".jpg");
                    Path coverPath = coverDir.resolve(coverFileName);
                    Files.write(coverPath, artwork.getBinaryData());
                    track.setCoverArtPath(coverPath.toAbsolutePath().toString());
                }
            } catch (Exception e) {
                log.warn("Failed to extract cover art from: {}", file.getName(), e);
            }

            return repository.save(track);
        } catch (Exception e) {
            log.error("Failed to extract metadata from: {}", filePath, e);
            throw new RuntimeException("Failed to extract metadata: " + e.getMessage(), e);
        }
    }

    public void scanDirectory(String directoryPath) {
        try {
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
                                extractAndSaveMetadata(path.toString());
                                log.info("Indexed: {}", path.getFileName());
                            } catch (Exception e) {
                                log.warn("Failed to index: {}", path.getFileName(), e);
                            }
                        });
            }
        } catch (Exception e) {
            log.error("Failed to scan directory: {}", directoryPath, e);
            throw new RuntimeException("Failed to scan directory: " + e.getMessage(), e);
        }
    }

    public MusicTrack scrapeAndSave(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }

            MusicTrack track = extractAndSaveMetadata(filePath);

            String title = track.getTitle();
            String artist = track.getArtist();

            if (title == null || title.isBlank()) {
                title = track.getFileName().replaceAll("\\.[^.]+$", "");
            }

            MusicBrainzResult scraped = metadataProviderManager.scrapeWithFallback(title, artist, track.getAlbum());

            if (scraped != null) {
                if (scraped.getAlbum() != null && track.getAlbum() == null) {
                    track.setAlbum(scraped.getAlbum());
                }
                if (scraped.getYear() != null && track.getYear() == null) {
                    track.setYear(scraped.getYear());
                }
                if (scraped.getGenre() != null && track.getGenre() == null) {
                    track.setGenre(scraped.getGenre());
                }
                if (scraped.getTrackNumber() != null && track.getTrackNumber() == null) {
                    track.setTrackNumber(scraped.getTrackNumber());
                }

                if (scraped.getCoverUrl() != null && track.getCoverArtPath() == null) {
                    try {
                        String coverPath = downloadCover(scraped.getCoverUrl(), file.getName());
                        track.setCoverArtPath(coverPath);
                    } catch (Exception e) {
                        log.warn("Failed to download cover for: {}", file.getName(), e);
                    }
                }
            }

            if (track.getLyrics() == null || track.getLyrics().isBlank()) {
                LyricsResult lyricsResult = lyricsScrapingService.searchExact(title, artist);
                if (lyricsResult != null) {
                    track.setLyrics(lyricsResult.getLyrics());
                }
            }

            return repository.save(track);
        } catch (Exception e) {
            log.error("Failed to scrape metadata from: {}", filePath, e);
            throw new RuntimeException("Failed to scrape metadata: " + e.getMessage(), e);
        }
    }

    private String downloadCover(String imageUrl, String fileName) throws IOException {
        Path coverDir = Paths.get(rootPath, ".cache", "covers");
        Files.createDirectories(coverDir);

        String coverFileName = fileName.replaceAll("[^a-zA-Z0-9.\\-]", "_") + "_cover.jpg";
        Path coverPath = coverDir.resolve(coverFileName);

        if (Files.exists(coverPath)) {
            return coverPath.toAbsolutePath().toString();
        }

        try (InputStream in = new URL(imageUrl).openStream()) {
            Files.copy(in, coverPath, StandardCopyOption.REPLACE_EXISTING);
        }

        return coverPath.toAbsolutePath().toString();
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