package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.music.dto.SearchResult;
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
    private final MusicMetadataProviderManager providerManager;

    @Value("${hub.music.root-path}")
    private String rootPath;

    @Value("${hub.music.auto-writeback:false}")
    private boolean autoWriteback;

    @Value("${hub.music.use-folder-structure:true}")
    private boolean useFolderStructure;

    @Value("${hub.music.default-artist:}")
    private String defaultArtist;

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

            inferFromFolderStructure(track, file);

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
                                if (autoWriteback) {
                                    scrapeAndSave(path.toString());
                                } else {
                                    extractAndSaveMetadata(path.toString());
                                }
                                log.info("Indexed: {}", path.getFileName());
                            } catch (Exception e) {
                                log.warn("Failed to index: {}", path.getFileName(), e);
                            }
                        });
            }
        } catch (Exception e) {
            log.error("Failed to scan directory: {}", directoryPath);
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
                title = file.getName().replaceAll("\\.[^.]+$", "");
            }
            if (artist == null || artist.isBlank()) {
                artist = defaultArtist;
            }

            MusicMetadataProviderManager.ProviderResult result =
                    providerManager.scrape(artist, title, track.getAlbum());

            if (result.searchResult() != null) {
                if (track.getAlbum() == null && result.searchResult().getAlbum() != null) {
                    track.setAlbum(result.searchResult().getAlbum());
                }
            }

            if (result.lyrics() != null && !result.lyrics().isBlank()) {
                track.setLyrics(result.lyrics());
                saveLyricsFile(file, result.lyrics());
                log.info("Got lyrics for: {} - {}", artist, title);
            } else {
                log.warn("No lyrics found for: {} - {}", artist, title);
            }

            if (result.coverData() != null) {
                String coverPath = saveCoverFile(file, result.coverData());
                track.setCoverArtPath(coverPath);
                log.info("Got cover for: {} - {}", artist, title);
            }

            MusicTrack saved = repository.save(track);

            if (autoWriteback) {
                writeMetadataToFile(file, saved);
            }

            return saved;
        } catch (Exception e) {
            log.error("Failed to scrape metadata from: {}", filePath, e);
            throw new RuntimeException("Failed to scrape metadata: " + e.getMessage(), e);
        }
    }

    private void inferFromFolderStructure(MusicTrack track, File file) {
        if (!useFolderStructure) return;

        Path musicRoot = Paths.get(rootPath).toAbsolutePath();
        Path parent = file.toPath().getParent();
        Path grandparent = parent != null ? parent.getParent() : null;

        if (track.getArtist() == null || track.getArtist().isBlank()) {
            if (grandparent != null && !grandparent.toAbsolutePath().equals(musicRoot)) {
                track.setArtist(grandparent.getFileName().toString());
            } else if (parent != null && grandparent != null && grandparent.toAbsolutePath().equals(musicRoot)) {
                track.setArtist(parent.getFileName().toString());
            }
        }

        if ((track.getAlbum() == null || track.getAlbum().isBlank()) && parent != null) {
            String parentName = parent.getFileName().toString();
            if (!parentName.equals(track.getArtist())) {
                track.setAlbum(parentName);
            }
        }

        if ((track.getArtist() == null || track.getArtist().isBlank()) && defaultArtist != null && !defaultArtist.isBlank()) {
            track.setArtist(defaultArtist);
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
            Path coverPath = coverDir.resolve("cover.jpg");
            Files.write(coverPath, coverData);
            return coverPath.toAbsolutePath().toString();
        } catch (IOException e) {
            log.warn("Failed to save cover file for: {}", audioFile.getName(), e);
            return null;
        }
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
            if (isDifferent(tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS), track.getLyrics())) {
                tag.setField(org.jaudiotagger.tag.FieldKey.LYRICS, track.getLyrics());
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
