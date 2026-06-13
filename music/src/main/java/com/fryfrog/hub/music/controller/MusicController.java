package com.fryfrog.hub.music.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.music.dto.MusicTrackUpdateRequest;
import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.service.MusicMetadataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
public class MusicController {

    private final MusicMetadataService service;

    @Value("${hub.music.root-path}")
    private String rootPath;

    @GetMapping
    public ResponseEntity<ApiResponse<List<MusicTrack>>> getAllTracks() {
        return ResponseEntity.ok(ApiResponse.success(service.getAllTracks()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MusicTrack>> getTrackById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getTrackById(id)));
    }

    @GetMapping("/search/title")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> searchByTitle(@RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByTitle(q)));
    }

    @GetMapping("/search/artist")
    public ResponseEntity<ApiResponse<List<MusicTrack>>> searchByArtist(@RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(service.searchByArtist(q)));
    }

    @PostMapping("/scan")
    public ResponseEntity<ApiResponse<String>> scanDirectory(@RequestParam String path) {
        validatePath(path);
        service.scanDirectory(path);
        return ResponseEntity.ok(ApiResponse.success("Scan completed", path));
    }

    @PostMapping("/metadata")
    public ResponseEntity<ApiResponse<MusicTrack>> extractMetadata(@RequestParam String filePath) {
        validatePath(filePath);
        return ResponseEntity.ok(ApiResponse.success(service.extractAndSaveMetadata(filePath)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<MusicTrack>> updateTrack(
            @PathVariable Long id,
            @Valid @RequestBody MusicTrackUpdateRequest request) {
        MusicTrack track = MusicTrack.builder()
                .title(request.getTitle())
                .artist(request.getArtist())
                .album(request.getAlbum())
                .albumArtist(request.getAlbumArtist())
                .trackNumber(request.getTrackNumber())
                .discNumber(request.getDiscNumber())
                .year(request.getYear())
                .genre(request.getGenre())
                .build();
        return ResponseEntity.ok(ApiResponse.success(service.updateTrack(id, track)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTrack(@PathVariable Long id) {
        service.deleteTrack(id);
        return ResponseEntity.ok(ApiResponse.success("Track deleted", null));
    }

    private void validatePath(String path) {
        Path requestedPath = Paths.get(path).toAbsolutePath();
        Path allowedRoot = Paths.get(rootPath).toAbsolutePath();
        if (!requestedPath.startsWith(allowedRoot)) {
            throw new IllegalArgumentException("Path is outside allowed root: " + rootPath);
        }
    }
}
