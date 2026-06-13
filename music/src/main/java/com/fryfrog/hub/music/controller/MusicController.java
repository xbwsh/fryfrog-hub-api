package com.fryfrog.hub.music.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.music.model.MusicTrack;
import com.fryfrog.hub.music.service.MusicMetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
public class MusicController {

    private final MusicMetadataService service;

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
        service.scanDirectory(path);
        return ResponseEntity.ok(ApiResponse.success("Scan completed", path));
    }

    @PostMapping("/metadata")
    public ResponseEntity<ApiResponse<MusicTrack>> extractMetadata(@RequestParam String filePath) {
        return ResponseEntity.ok(ApiResponse.success(service.extractAndSaveMetadata(filePath)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<MusicTrack>> updateTrack(
            @PathVariable Long id,
            @RequestBody MusicTrack track) {
        return ResponseEntity.ok(ApiResponse.success(service.updateTrack(id, track)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTrack(@PathVariable Long id) {
        service.deleteTrack(id);
        return ResponseEntity.ok(ApiResponse.success("Track deleted", null));
    }
}
