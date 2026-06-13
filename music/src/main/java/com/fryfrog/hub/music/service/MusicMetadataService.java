package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.repository.MusicTrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.*;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MusicMetadataService {

    private final MusicTrackRepository repository;

    @Value("${hub.music.root-path}")
    private String rootPath;

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

    public MusicTrack extractAndSaveMetadata(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }

            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTagOrCreateDefault();

            MusicTrack track = MusicTrack.builder()
                    .title(tag.getFirst(FieldKey.TITLE))
                    .artist(tag.getFirst(FieldKey.ARTIST))
                    .album(tag.getFirst(FieldKey.ALBUM))
                    .albumArtist(tag.getFirst(FieldKey.ALBUM_ARTIST))
                    .trackNumber(parseInteger(tag.getFirst(FieldKey.TRACK)))
                    .discNumber(parseInteger(tag.getFirst(FieldKey.DISC_NO)))
                    .year(parseInteger(tag.getFirst(FieldKey.YEAR)))
                    .genre(tag.getFirst(FieldKey.GENRE))
                    .filePath(file.getAbsolutePath())
                    .fileName(file.getName())
                    .fileSize(file.length())
                    .durationSeconds((long) audioFile.getAudioHeader().getTrackLength())
                    .bitrateKbps(parseInteger(audioFile.getAudioHeader().getBitRate()))
                    .format(audioFile.getAudioHeader().getFormat())
                    .build();

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

            String supportedFormats = "mp3,flac,ogg,wav,aac,m4a";
            List<String> formats = List.of(supportedFormats.split(","));

            Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return formats.stream().anyMatch(name::endsWith);
                    })
                    .forEach(path -> {
                        try {
                            extractAndSaveMetadata(path.toString());
                            log.info("Indexed: {}", path.getFileName());
                        } catch (Exception e) {
                            log.warn("Failed to index: {}", path.getFileName(), e);
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to scan directory: {}", directoryPath, e);
            throw new RuntimeException("Failed to scan directory: " + e.getMessage(), e);
        }
    }

    public MusicTrack updateTrack(Long id, MusicTrack updatedTrack) {
        MusicTrack existing = getTrackById(id);
        existing.setTitle(updatedTrack.getTitle());
        existing.setArtist(updatedTrack.getArtist());
        existing.setAlbum(updatedTrack.getAlbum());
        existing.setAlbumArtist(updatedTrack.getAlbumArtist());
        existing.setTrackNumber(updatedTrack.getTrackNumber());
        existing.setDiscNumber(updatedTrack.getDiscNumber());
        existing.setYear(updatedTrack.getYear());
        existing.setGenre(updatedTrack.getGenre());
        return repository.save(existing);
    }

    public void deleteTrack(Long id) {
        MusicTrack track = getTrackById(id);
        repository.delete(track);
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
