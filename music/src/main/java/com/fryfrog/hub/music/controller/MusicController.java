package com.fryfrog.hub.music.controller;

import com.fryfrog.hub.common.dto.ApiResponse;
import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.security.UserContext;
import com.fryfrog.hub.common.exception.ForbiddenException;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.common.util.MediaUrlSigner;
import com.fryfrog.hub.music.dto.MusicAlbumDTO;
import com.fryfrog.hub.music.dto.MusicArtistDTO;
import com.fryfrog.hub.music.dto.MusicLibraryGroupDTO;
import com.fryfrog.hub.music.dto.MusicSongDTO;
import com.fryfrog.hub.music.model.MusicAlbum;
import com.fryfrog.hub.music.model.MusicArtist;
import com.fryfrog.hub.music.model.MusicBookmark;
import com.fryfrog.hub.music.model.MusicPlayQueue;
import com.fryfrog.hub.music.model.MusicPlaylist;
import com.fryfrog.hub.music.model.MusicSong;
import com.fryfrog.hub.music.repository.MusicAlbumRepository;
import com.fryfrog.hub.music.repository.MusicArtistRepository;
import com.fryfrog.hub.music.service.MusicFavoriteService;
import com.fryfrog.hub.music.service.MusicPlayStatService;
import com.fryfrog.hub.music.service.MusicPlayStateService;
import com.fryfrog.hub.music.service.MusicPlaylistService;
import com.fryfrog.hub.music.service.MusicQueryService;
import com.fryfrog.hub.music.service.MusicScanService;
import com.fryfrog.hub.music.service.MusicStreamService;
import com.fryfrog.hub.music.service.MusicTagReaderService;
import com.fryfrog.hub.music.service.MusicOrganizeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "音乐管理", description = "音乐浏览、播放、歌词与收藏接口")
public class MusicController {

    private final MusicQueryService queryService;
    private final MusicFavoriteService favoriteService;
    private final MusicPlayStatService playStatService;
    private final MusicPlaylistService playlistService;
    private final MusicPlayStateService playStateService;
    private final MusicStreamService streamService;
    private final MusicScanService scanService;
    private final MusicOrganizeService organizeService;
    private final MediaLibraryService mediaLibraryService;
    private final UserService userService;
    private final MusicArtistRepository artistRepository;
    private final MusicAlbumRepository albumRepository;
    private final MusicTagReaderService tagReaderService;

    private void requireAdmin(HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        if (!userService.isAdmin(userId)) {
            throw new ForbiddenException("需要管理员权限");
        }
    }

    // ── 首页音乐模式 ──

    @GetMapping("/home")
    @Operation(summary = "音乐首页", description = "按当前用户可见媒体库分组返回专辑与歌手（首页音乐模式用）")
    public ResponseEntity<ApiResponse<List<MusicLibraryGroupDTO>>> getHome(HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        List<Long> allowedIds = mediaLibraryService.getAllowableLibraryIds();
        List<MediaLibrary> musicLibraries = mediaLibraryService.getEnabledLibraries().stream()
                .filter(MediaLibrary::isMusicType)
                .filter(lib -> allowedIds.contains(lib.getId()))
                .toList();

        List<MusicLibraryGroupDTO> groups = musicLibraries.stream().map(lib -> {
            List<MusicAlbum> albums = albumRepository.findByLibraryIdInOrderByTitleAsc(List.of(lib.getId()));
            List<MusicArtist> artists = artistRepository.findByLibraryIdInOrderByNameAsc(List.of(lib.getId()));
            return MusicLibraryGroupDTO.builder()
                    .libraryId(lib.getId())
                    .libraryName(lib.getName())
                    .libraryPath(lib.getPath())
                    .albums(toAlbumDTOs(albums, userId))
                    .artists(artists.stream()
                            .map(a -> toArtistDTO(a, favoriteService.isStarred(userId, MusicFavoriteService.TYPE_ARTIST, a.getId())))
                            .toList())
                    .albumCount(albums.size())
                    .artistCount(artists.size())
                    .build();
        }).toList();

        return ResponseEntity.ok(ApiResponse.success(groups));
    }

    // ── 歌手 ──

    @GetMapping("/artists")
    @Operation(summary = "获取歌手列表", description = "返回当前用户可见音乐库中的歌手")
    public ResponseEntity<ApiResponse<List<MusicArtistDTO>>> getArtists(HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        List<MusicArtist> artists = queryService.getAllArtists();
        List<Long> ids = artists.stream().map(MusicArtist::getId).toList();
        var starred = favoriteService.starredIds(userId, MusicFavoriteService.TYPE_ARTIST, ids);
        return ResponseEntity.ok(ApiResponse.success(artists.stream()
                .map(a -> toArtistDTO(a, starred.contains(a.getId()))).toList()));
    }

    @GetMapping("/artists/{id:\\d+}")
    @Operation(summary = "获取歌手详情", description = "返回歌手及其专辑列表")
    public ResponseEntity<ApiResponse<MusicArtistDTO>> getArtist(
            @Parameter(description = "歌手ID") @PathVariable Long id,
            HttpServletRequest request) {
        MusicArtist artist = requireArtist(id);
        long userId = UserContext.currentUserId(request);
        MusicArtistDTO dto = toArtistDTO(artist, favoriteService.isStarred(userId, MusicFavoriteService.TYPE_ARTIST, id));
        dto.setAlbums(toAlbumDTOs(queryService.getAlbumsByArtist(id), userId));
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    // ── 专辑 ──

    @GetMapping("/albums")
    @Operation(summary = "获取专辑列表")
    public ResponseEntity<ApiResponse<List<MusicAlbumDTO>>> getAlbums(HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        return ResponseEntity.ok(ApiResponse.success(toAlbumDTOs(queryService.getAllAlbums(), userId)));
    }

    @GetMapping("/albums/{id:\\d+}")
    @Operation(summary = "获取专辑详情", description = "返回专辑及其曲目")
    public ResponseEntity<ApiResponse<MusicAlbumDTO>> getAlbum(
            @Parameter(description = "专辑ID") @PathVariable Long id,
            HttpServletRequest request) {
        MusicAlbum album = requireAlbum(id);
        long userId = UserContext.currentUserId(request);
        return ResponseEntity.ok(ApiResponse.success(toAlbumDTO(album, queryService.getSongsByAlbum(id), userId)));
    }

    @GetMapping("/albums/{id:\\d+}/songs")
    @Operation(summary = "获取专辑曲目")
    public ResponseEntity<ApiResponse<List<MusicSongDTO>>> getAlbumSongs(
            @Parameter(description = "专辑ID") @PathVariable Long id,
            HttpServletRequest request) {
        requireAlbum(id);
        long userId = UserContext.currentUserId(request);
        return ResponseEntity.ok(ApiResponse.success(toSongDTOs(queryService.getSongsByAlbum(id), userId)));
    }

    // ── 单曲 ──

    @GetMapping("/songs")
    @Operation(summary = "搜索单曲", description = "按标题/歌手/专辑关键词搜索，或传 genre 按流派过滤")
    public ResponseEntity<ApiResponse<List<MusicSongDTO>>> searchSongs(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String genre,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        List<MusicSong> songs;
        if (genre != null && !genre.isBlank()) {
            songs = queryService.getSongsByGenre(genre, limit);
        } else if (q != null && !q.isBlank()) {
            songs = queryService.searchSongs(q, limit);
        } else {
            songs = queryService.getAllSongs(limit);
        }
        return ResponseEntity.ok(ApiResponse.success(toSongDTOs(songs, userId)));
    }

    @GetMapping("/songs/{id:\\d+}")
    @Operation(summary = "获取单曲详情")
    public ResponseEntity<ApiResponse<MusicSongDTO>> getSong(
            @Parameter(description = "单曲ID") @PathVariable Long id,
            HttpServletRequest request) {
        MusicSong song = requireSong(id);
        long userId = UserContext.currentUserId(request);
        return ResponseEntity.ok(ApiResponse.success(toSongDTO(song, userId)));
    }

    @GetMapping("/songs/{id:\\d+}/stream")
    @Operation(summary = "音频流播放", description = "支持 Range 请求")
    public void streamSong(
            @Parameter(description = "单曲ID") @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            HttpServletResponse response) throws Exception {
        MusicSong song = requireSong(id);
        streamService.stream(response, song, rangeHeader, false);
    }

    @GetMapping("/songs/{id:\\d+}/lyrics")
    @Operation(summary = "获取歌词", description = "优先返回音频内嵌歌词，否则返回同目录 .lrc")
    public ResponseEntity<Resource> getLyrics(@Parameter(description = "单曲ID") @PathVariable Long id) {
        MusicSong song = requireSong(id);
        if (song.getLyricsContent() != null && !song.getLyricsContent().isBlank()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(new ByteArrayResource(song.getLyricsContent().getBytes(StandardCharsets.UTF_8)));
        }
        if (song.getLyricsPath() == null || !Files.exists(Paths.get(song.getLyricsPath()))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(new FileSystemResource(Paths.get(song.getLyricsPath()).toFile()));
    }

    @GetMapping("/songs/{id:\\d+}/cover")
    @Operation(summary = "获取单曲封面", description = "返回所属专辑的封面（直读音频内嵌图）")
    public ResponseEntity<Resource> getSongCover(@Parameter(description = "单曲ID") @PathVariable Long id) {
        MusicSong song = requireSong(id);
        // 优先目录型 coverArtPath 的存量（若仍指向真实文件）；否则直读该曲文件内嵌图
        if (song.getAlbum() != null && song.getAlbum().getCoverArtPath() != null
                && Files.exists(Paths.get(song.getAlbum().getCoverArtPath()))) {
            return coverFileResponse(Paths.get(song.getAlbum().getCoverArtPath()));
        }
        return embeddedCoverResponse(song.getFilePath());
    }

    @GetMapping("/albums/{id:\\d+}/cover")
    @Operation(summary = "获取专辑封面", description = "直读音频内嵌封面")
    public ResponseEntity<Resource> getAlbumCover(@Parameter(description = "专辑ID") @PathVariable Long id) {
        MusicAlbum album = requireAlbum(id);
        if (album.getCoverArtPath() != null && Files.exists(Paths.get(album.getCoverArtPath()))) {
            return coverFileResponse(Paths.get(album.getCoverArtPath()));
        }
        // 专辑无目录封面 → 取该专辑任一曲目的内嵌图直读
        List<MusicSong> songs = queryService.getSongsByAlbum(id);
        for (MusicSong s : songs) {
            ResponseEntity<Resource> resp = embeddedCoverResponse(s.getFilePath());
            if (resp.getStatusCode().is2xxSuccessful()) return resp;
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/artists/{id:\\d+}/cover")
    @Operation(summary = "获取歌手图片")
    public ResponseEntity<Resource> getArtistCover(@Parameter(description = "歌手ID") @PathVariable Long id) {
        MusicArtist artist = requireArtist(id);
        if (artist.getCoverArtPath() == null || !Files.exists(Paths.get(artist.getCoverArtPath()))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(Paths.get(artist.getCoverArtPath()).toFile()));
    }

    // ── 流派 ──

    @GetMapping("/genres")
    @Operation(summary = "获取流派列表")
    public ResponseEntity<ApiResponse<List<String>>> getGenres() {
        return ResponseEntity.ok(ApiResponse.success(queryService.getAllGenres()));
    }

    // ── 收藏 / 评分 ──

    @PutMapping("/{type:songs|albums|artists}/{id:\\d+}/star")
    @Operation(summary = "设置收藏状态", description = "type=songs/albums/artists")
    public ResponseEntity<ApiResponse<Void>> setStar(
            @Parameter(description = "类型: songs/albums/artists") @PathVariable String type,
            @Parameter(description = "目标ID") @PathVariable Long id,
            @Parameter(description = "收藏状态") @RequestParam boolean status,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        favoriteService.setStar(userId, starType(type), id, status);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/{type:songs|albums|artists}/{id:\\d+}/rating")
    @Operation(summary = "设置评分", description = "rating 1-5，0 清除")
    public ResponseEntity<ApiResponse<Void>> setRating(
            @Parameter(description = "类型: songs/albums/artists") @PathVariable String type,
            @Parameter(description = "目标ID") @PathVariable Long id,
            @Parameter(description = "评分 1-5") @RequestParam int rating,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        favoriteService.setRating(userId, starType(type), id, rating);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── 播放列表 ──

    @GetMapping("/playlists")
    @Operation(summary = "获取播放列表", description = "返回当前用户的播放列表（含公开列表）")
    public ResponseEntity<ApiResponse<List<MusicPlaylist>>> getPlaylists(HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        return ResponseEntity.ok(ApiResponse.success(playlistService.getPlaylists(userId)));
    }

    @GetMapping("/playlists/{id:\\d+}")
    @Operation(summary = "获取播放列表详情", description = "返回播放列表及其曲目")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPlaylist(
            @Parameter(description = "播放列表ID") @PathVariable Long id,
            HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        MusicPlaylist playlist = playlistService.getPlaylist(id);
        if (!playlistService.canRead(playlist, userId)) {
            throw new ResourceNotFoundException("MusicPlaylist", "id", id);
        }
        List<MusicSongDTO> songs = playlistService.getEntries(id).stream()
                .map(e -> queryService.getSong(e.getSong().getId()))
                .filter(java.util.Objects::nonNull)
                .map(s -> toSongDTO(s, userId))
                .toList();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", playlist.getId());
        result.put("name", playlist.getName());
        result.put("comment", playlist.getComment());
        result.put("public", Boolean.TRUE.equals(playlist.getIsPublic()));
        result.put("createdAt", playlist.getCreatedAt());
        result.put("songs", songs);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/playlists")
    @Operation(summary = "创建播放列表", description = "body: {name, comment, public, songIds}")
    public ResponseEntity<ApiResponse<MusicPlaylist>> createPlaylist(
            @RequestBody com.fryfrog.hub.music.dto.MusicPlaylistRequest request,
            HttpServletRequest req) {
        long userId = UserContext.currentUserId(req);
        MusicPlaylist playlist = playlistService.createPlaylist(
                userId, request.getName(), request.getComment(),
                Boolean.TRUE.equals(request.getIsPublic()), request.getSongIds());
        return ResponseEntity.ok(ApiResponse.success(playlist));
    }

    @PutMapping("/playlists/{id:\\d+}")
    @Operation(summary = "更新播放列表", description = "body 支持 name/comment/public/songIdsToAdd/songIndexesToRemove")
    public ResponseEntity<ApiResponse<MusicPlaylist>> updatePlaylist(
            @Parameter(description = "播放列表ID") @PathVariable Long id,
            @RequestBody com.fryfrog.hub.music.dto.MusicPlaylistUpdateRequest request,
            HttpServletRequest req) {
        long userId = UserContext.currentUserId(req);
        MusicPlaylist playlist = playlistService.getPlaylist(id);
        if (!playlistService.canWrite(playlist, userId)) {
            throw new ResourceNotFoundException("MusicPlaylist", "id", id);
        }
        if (request.getName() != null || request.getComment() != null || request.getIsPublic() != null) {
            playlistService.updatePlaylist(id, request.getName(), request.getComment(), request.getIsPublic());
        }
        if (request.getSongIdsToAdd() != null) {
            playlistService.addSongs(playlist, request.getSongIdsToAdd());
        }
        if (request.getSongIndexesToRemove() != null) {
            playlistService.removeSongs(playlist, request.getSongIndexesToRemove());
        }
        return ResponseEntity.ok(ApiResponse.success(playlistService.getPlaylist(id)));
    }

    @DeleteMapping("/playlists/{id:\\d+}")
    @Operation(summary = "删除播放列表")
    public ResponseEntity<ApiResponse<Void>> deletePlaylist(
            @Parameter(description = "播放列表ID") @PathVariable Long id,
            HttpServletRequest req) {
        long userId = UserContext.currentUserId(req);
        MusicPlaylist playlist = playlistService.getPlaylist(id);
        if (!playlistService.canWrite(playlist, userId)) {
            throw new ResourceNotFoundException("MusicPlaylist", "id", id);
        }
        playlistService.deletePlaylist(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── 播放记录 / 队列 / 书签 ──

    @PostMapping("/scrobble")
    @Operation(summary = "登记播放", description = "body: {songId, submission, time}；submission=true 累加播放次数")
    public ResponseEntity<ApiResponse<Void>> scrobble(
            @RequestBody com.fryfrog.hub.music.dto.MusicScrobbleRequest request,
            HttpServletRequest req) {
        long userId = UserContext.currentUserId(req);
        boolean submission = request.getSubmission() == null || request.getSubmission();
        playStatService.scrobble(userId, request.getSongId(), request.getTime(), submission);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/play-queue")
    @Operation(summary = "获取播放队列")
    public ResponseEntity<ApiResponse<MusicPlayQueue>> getPlayQueue(HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        return ResponseEntity.ok(ApiResponse.success(playStateService.getPlayQueue(userId)));
    }

    @PutMapping("/play-queue")
    @Operation(summary = "保存播放队列", description = "body: {songIds, currentSongId, positionSeconds}")
    public ResponseEntity<ApiResponse<MusicPlayQueue>> savePlayQueue(
            @RequestBody com.fryfrog.hub.music.dto.MusicPlayQueueRequest request,
            HttpServletRequest req) {
        long userId = UserContext.currentUserId(req);
        MusicPlayQueue queue = playStateService.savePlayQueue(userId, request.getSongIds(),
                request.getCurrentSongId(), request.getPositionSeconds(), System.currentTimeMillis());
        return ResponseEntity.ok(ApiResponse.success(queue));
    }

    @GetMapping("/bookmarks")
    @Operation(summary = "获取书签")
    public ResponseEntity<ApiResponse<List<MusicBookmark>>> getBookmarks(HttpServletRequest request) {
        long userId = UserContext.currentUserId(request);
        return ResponseEntity.ok(ApiResponse.success(playStateService.getBookmarks(userId)));
    }

    @PostMapping("/bookmarks")
    @Operation(summary = "创建书签", description = "body: {songId, positionSeconds, comment}")
    public ResponseEntity<ApiResponse<MusicBookmark>> createBookmark(
            @RequestBody com.fryfrog.hub.music.dto.MusicBookmarkRequest request,
            HttpServletRequest req) {
        long userId = UserContext.currentUserId(req);
        return ResponseEntity.ok(ApiResponse.success(playStateService.createBookmark(
                userId, request.getSongId(), request.getPositionSeconds(), request.getComment())));
    }

    @DeleteMapping("/bookmarks/{songId:\\d+}")
    @Operation(summary = "删除书签")
    public ResponseEntity<ApiResponse<Void>> deleteBookmark(
            @Parameter(description = "单曲ID") @PathVariable Long songId,
            HttpServletRequest req) {
        long userId = UserContext.currentUserId(req);
        playStateService.deleteBookmark(userId, songId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── 扫描 ──

    @PostMapping("/organize")
    @Operation(summary = "整理音乐文件", description = "按歌手/专辑/曲目整理已扫描音乐；默认仅预览")
    public ResponseEntity<ApiResponse<Map<String, Object>>> organize(
            @RequestParam Long libraryId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            HttpServletRequest request) {
        requireAdmin(request);
        if (!mediaLibraryService.isVisibleToCurrentUser(libraryId)) {
            throw new ResourceNotFoundException("MediaLibrary", "id", libraryId);
        }
        return ResponseEntity.ok(ApiResponse.success(organizeService.organize(libraryId, dryRun)));
    }

    @PostMapping("/scan")
    @Operation(summary = "扫描音乐资源库", description = "扫描指定 MUSIC 资源库（异步执行），不传 libraryId 时扫描全部")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scan(
            @Parameter(description = "资源库ID，可选") @RequestParam(required = false) Long libraryId,
            HttpServletRequest request) {
        requireAdmin(request);
        if (libraryId != null && !mediaLibraryService.isVisibleToCurrentUser(libraryId)) {
            throw new ResourceNotFoundException("MediaLibrary", "id", libraryId);
        }
        List<MediaLibrary> libraries = libraryId != null
                ? List.of(mediaLibraryService.getLibraryById(libraryId))
                : mediaLibraryService.getVisibleLibraries().stream().filter(MediaLibrary::isMusicType).toList();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", "started");
        result.put("libraryCount", libraries.size());
        Thread.startVirtualThread(() -> {
            for (MediaLibrary lib : libraries) {
                try {
                    scanService.scanAndSave(lib.getPath(), lib.getId());
                } catch (Exception e) {
                    log.warn("[MusicScan] Failed library '{}': {}", lib.getName(), e.getMessage());
                }
            }
        });
        return ResponseEntity.ok(ApiResponse.success("扫描任务已启动", result));
    }

    // ── DTO 构建 ──

    private MusicArtistDTO toArtistDTO(MusicArtist artist, boolean starred) {
        MusicArtistDTO dto = new MusicArtistDTO();
        dto.setId(artist.getId());
        dto.setName(artist.getName());
        dto.setSortName(artist.getSortName());
        if (artist.getCoverArtPath() != null && Files.exists(Paths.get(artist.getCoverArtPath()))) {
            dto.setCoverUrl(MediaUrlSigner.sign("/api/v1/music/artists/" + artist.getId() + "/cover"));
        }
        dto.setAlbumCount(queryService.getAlbumsByArtist(artist.getId()).size());
        dto.setStarred(starred);
        return dto;
    }

    private List<MusicAlbumDTO> toAlbumDTOs(List<MusicAlbum> albums, long userId) {
        List<Long> ids = albums.stream().map(MusicAlbum::getId).toList();
        var starred = favoriteService.starredIds(userId, MusicFavoriteService.TYPE_ALBUM, ids);
        var ratings = favoriteService.ratingMap(userId, MusicFavoriteService.TYPE_ALBUM, ids);
        return albums.stream().map(a -> {
            MusicAlbumDTO dto = toAlbumDTO(a, List.of(), userId);
            dto.setStarred(starred.contains(a.getId()));
            dto.setRating(ratings.get(a.getId()));
            return dto;
        }).toList();
    }

    private MusicAlbumDTO toAlbumDTO(MusicAlbum album, List<MusicSong> songs, long userId) {
        MusicAlbumDTO dto = new MusicAlbumDTO();
        dto.setId(album.getId());
        dto.setTitle(album.getTitle());
        dto.setArtistName(album.getArtistName());
        dto.setArtistId(album.getArtist() != null ? album.getArtist().getId() : null);
        dto.setYear(album.getYear());
        dto.setGenre(album.getGenre());
        if (album.getCoverArtPath() != null && Files.exists(Paths.get(album.getCoverArtPath()))) {
            dto.setCoverUrl(MediaUrlSigner.sign("/api/v1/music/albums/" + album.getId() + "/cover"));
        }
        dto.setTrackCount(album.getTrackCount());
        dto.setDurationSeconds(songs.stream().mapToInt(s -> s.getDurationSeconds() == null ? 0 : s.getDurationSeconds().intValue()).sum());
        dto.setStarred(favoriteService.isStarred(userId, MusicFavoriteService.TYPE_ALBUM, album.getId()));
        dto.setRating(favoriteService.getRating(userId, MusicFavoriteService.TYPE_ALBUM, album.getId()));
        if (!songs.isEmpty()) {
            dto.setSongs(toSongDTOs(songs, userId));
        }
        return dto;
    }

    private List<MusicSongDTO> toSongDTOs(List<MusicSong> songs, long userId) {
        List<Long> ids = songs.stream().map(MusicSong::getId).toList();
        var starred = favoriteService.starredIds(userId, MusicFavoriteService.TYPE_SONG, ids);
        var ratings = favoriteService.ratingMap(userId, MusicFavoriteService.TYPE_SONG, ids);
        var playCounts = playStatService.playCountMap(userId, ids);
        return songs.stream().map(s -> {
            MusicSongDTO dto = toSongDTO(s, userId);
            dto.setStarred(starred.contains(s.getId()));
            dto.setRating(ratings.get(s.getId()));
            dto.setPlayCount(playCounts.getOrDefault(s.getId(), 0));
            return dto;
        }).toList();
    }

    private MusicSongDTO toSongDTO(MusicSong song, long userId) {
        MusicSongDTO dto = new MusicSongDTO();
        dto.setId(song.getId());
        dto.setTitle(song.getTitle());
        dto.setArtistName(song.getArtistName());
        dto.setAlbumName(song.getAlbumName());
        dto.setArtistId(song.getArtist() != null ? song.getArtist().getId() : null);
        dto.setAlbumId(song.getAlbum() != null ? song.getAlbum().getId() : null);
        dto.setTrackNumber(song.getTrackNumber());
        dto.setDiscNumber(song.getDiscNumber());
        dto.setDurationSeconds(song.getDurationSeconds());
        dto.setFormat(song.getFormat());
        dto.setBitRate(song.getBitRate());
        dto.setGenre(song.getGenre());
        dto.setYear(song.getYear());
        dto.setFileSize(song.getFileSize());
        dto.setStreamUrl(MediaUrlSigner.sign("/api/v1/music/songs/" + song.getId() + "/stream"));
        if (song.getAlbum() != null && song.getAlbum().getCoverArtPath() != null
                && Files.exists(Paths.get(song.getAlbum().getCoverArtPath()))) {
            dto.setCoverUrl(MediaUrlSigner.sign("/api/v1/music/songs/" + song.getId() + "/cover"));
        }
        if ((song.getLyricsContent() != null && !song.getLyricsContent().isBlank())
                || (song.getLyricsPath() != null && Files.exists(Paths.get(song.getLyricsPath())))) {
            dto.setLyricsUrl(MediaUrlSigner.sign("/api/v1/music/songs/" + song.getId() + "/lyrics"));
        }
        return dto;
    }

    // ── 工具 ──

    /** 封面文件响应：按扩展名推断 Content-Type（支持 jpg/png/webp），缺失返回 404 */
    private ResponseEntity<Resource> coverFileResponse(Path path) {
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        String name = path.getFileName().toString().toLowerCase();
        MediaType type = MediaType.IMAGE_JPEG;
        if (name.endsWith(".png")) type = MediaType.IMAGE_PNG;
        else if (name.endsWith(".webp")) type = MediaType.parseMediaType("image/webp");
        return ResponseEntity.ok().contentType(type)
                .body(new FileSystemResource(path.toFile()));
    }

    /** 直读音频文件内嵌封面；无内嵌图返回 404。 */
    private ResponseEntity<Resource> embeddedCoverResponse(String filePath) {
        if (filePath == null) return ResponseEntity.notFound().build();
        var art = tagReaderService.readEmbeddedArtwork(new java.io.File(filePath));
        if (art == null || art.data() == null || art.data().length == 0) {
            return ResponseEntity.notFound().build();
        }
        String ext = art.extension();
        MediaType type = switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "gif" -> MediaType.parseMediaType("image/gif");
            default -> MediaType.IMAGE_JPEG;
        };
        return ResponseEntity.ok().contentType(type)
                .body(new ByteArrayResource(art.data()));
    }

    private static String starType(String type) {
        return switch (type) {
            case "songs" -> MusicFavoriteService.TYPE_SONG;
            case "albums" -> MusicFavoriteService.TYPE_ALBUM;
            case "artists" -> MusicFavoriteService.TYPE_ARTIST;
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }

    private MusicSong requireSong(Long id) {
        MusicSong song = queryService.getSong(id);
        if (song == null) throw new ResourceNotFoundException("MusicSong", "id", id);
        return song;
    }

    private MusicAlbum requireAlbum(Long id) {
        MusicAlbum album = queryService.getAlbum(id);
        if (album == null) throw new ResourceNotFoundException("MusicAlbum", "id", id);
        return album;
    }

    private MusicArtist requireArtist(Long id) {
        MusicArtist artist = queryService.getArtist(id);
        if (artist == null) throw new ResourceNotFoundException("MusicArtist", "id", id);
        return artist;
    }
}
