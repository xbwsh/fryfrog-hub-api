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
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class MusicMetadataService {

    private final MusicTrackRepository repository;

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

    public MusicTrack extractAndSaveMetadata(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }

            String absolutePath = file.getAbsolutePath();
            MusicTrack existing = repository.findByFilePath(absolutePath).orElse(null);

            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTagOrCreateDefault();

            MusicTrack track = existing != null ? existing : MusicTrack.builder().build();

            track.setTitle(tag.getFirst(FieldKey.TITLE));
            track.setArtist(tag.getFirst(FieldKey.ARTIST));
            track.setAlbum(tag.getFirst(FieldKey.ALBUM));
            track.setAlbumArtist(tag.getFirst(FieldKey.ALBUM_ARTIST));
            track.setTrackNumber(parseInteger(tag.getFirst(FieldKey.TRACK)));
            track.setDiscNumber(parseInteger(tag.getFirst(FieldKey.DISC_NO)));
            track.setYear(parseInteger(tag.getFirst(FieldKey.YEAR)));
            track.setGenre(tag.getFirst(FieldKey.GENRE));
            track.setFilePath(absolutePath);
            track.setFileName(file.getName());
            track.setFileSize(file.length());
            track.setDurationSeconds((long) audioFile.getAudioHeader().getTrackLength());
            track.setBitrateKbps(parseInteger(audioFile.getAudioHeader().getBitRate()));
            track.setFormat(audioFile.getAudioHeader().getFormat());

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
