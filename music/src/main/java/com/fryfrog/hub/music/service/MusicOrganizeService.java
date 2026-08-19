package com.fryfrog.hub.music.service;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.music.model.MusicSong;
import com.fryfrog.hub.music.repository.MusicSongRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将已扫描的音乐整理为 root/artist/album/track.ext。
 * 仅由显式 API 调用，不参与普通扫描，避免扫描时意外移动文件。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MusicOrganizeService {

    private final MusicSongRepository songRepository;
    private final MediaLibraryService mediaLibraryService;

    @Transactional
    public Map<String, Object> organize(Long libraryId, boolean dryRun) {
        MediaLibrary library = mediaLibraryService.getLibraryById(libraryId);
        if (!library.isMusicType()) {
            throw new IllegalArgumentException("指定资源库不是 MUSIC 类型");
        }

        Path root = Path.of(library.getPath()).toAbsolutePath().normalize();
        List<Map<String, Object>> items = new ArrayList<>();
        int moved = 0;
        int skipped = 0;
        int failed = 0;

        List<MusicSong> songs = songRepository.findByLibraryIdIn(List.of(libraryId)).stream()
                .sorted(Comparator.comparing(MusicSong::getFilePath, Comparator.nullsLast(String::compareTo)))
                .toList();

        for (MusicSong song : songs) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("songId", song.getId());
            result.put("title", song.getTitle());

            try {
                Path source = requireSourceInsideLibrary(song, root);
                Path target = uniqueTarget(root, song, source);
                result.put("source", source.toString());
                result.put("target", target.toString());

                if (source.equals(target)) {
                    result.put("status", "unchanged");
                    skipped++;
                } else if (dryRun) {
                    result.put("status", "planned");
                    moved++;
                } else {
                    Files.createDirectories(target.getParent());
                    move(source, target);
                    song.setFilePath(target.toString());
                    song.setFileSize(Files.size(target));
                    songRepository.save(song);
                    result.put("status", "moved");
                    moved++;
                }
            } catch (Exception e) {
                result.put("status", "failed");
                result.put("message", e.getMessage());
                failed++;
                log.warn("[MusicOrganize] Failed to organize song {}: {}", song.getId(), e.getMessage());
            }
            items.add(result);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("libraryId", libraryId);
        response.put("dryRun", dryRun);
        response.put("total", songs.size());
        response.put("movedOrPlanned", moved);
        response.put("unchanged", skipped);
        response.put("failed", failed);
        response.put("items", items);
        return response;
    }

    private Path requireSourceInsideLibrary(MusicSong song, Path root) throws IOException {
        if (song.getFilePath() == null || song.getFilePath().isBlank()) {
            throw new IOException("歌曲没有文件路径");
        }
        Path source = Path.of(song.getFilePath()).toAbsolutePath().normalize();
        if (!source.startsWith(root)) {
            throw new IOException("文件不在音乐库目录内");
        }
        if (!Files.isRegularFile(source)) {
            throw new IOException("文件不存在");
        }
        return source;
    }

    private Path uniqueTarget(Path root, MusicSong song, Path source) {
        String artist = safeName(song.getArtistName(), "未知歌手");
        String album = safeName(song.getAlbumName(), "未知专辑");
        String title = safeName(song.getTitle(), stripExtension(source.getFileName().toString()));
        String extension = extension(source.getFileName().toString());
        String track = song.getTrackNumber() != null
                ? String.format("%02d - ", song.getTrackNumber())
                : "";

        Path albumDir = root.resolve(artist).resolve(album).normalize();
        Path target = albumDir.resolve(track + title + extension).normalize();
        if (!target.startsWith(root) || target.equals(source)) return target;

        int suffix = 2;
        while (Files.exists(target)) {
            target = albumDir.resolve(track + title + " (" + suffix++ + ")" + extension).normalize();
        }
        return target;
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static String safeName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String cleaned = value
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("[\\p{Cntrl}]", "_")
                .strip();
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1).stripTrailing();
        return cleaned.isBlank() || cleaned.equals(".") || cleaned.equals("..") ? fallback : cleaned;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot).toLowerCase() : "";
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
