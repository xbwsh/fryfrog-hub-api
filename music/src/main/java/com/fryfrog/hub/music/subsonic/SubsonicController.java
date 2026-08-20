package com.fryfrog.hub.music.subsonic;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.security.UserContext;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.ScrapeProgressService;
import com.fryfrog.hub.music.model.MusicAlbum;
import com.fryfrog.hub.music.model.MusicArtist;
import com.fryfrog.hub.music.model.MusicPlaylist;
import com.fryfrog.hub.music.model.MusicSong;
import com.fryfrog.hub.music.service.MusicFavoriteService;
import com.fryfrog.hub.music.service.MusicPlayStatService;
import com.fryfrog.hub.music.service.MusicPlayStateService;
import com.fryfrog.hub.music.service.MusicPlaylistService;
import com.fryfrog.hub.music.service.MusicQueryService;
import com.fryfrog.hub.music.service.MusicScanService;
import com.fryfrog.hub.music.service.MusicStreamService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.fryfrog.hub.music.subsonic.SubsonicApiException.ERROR_NOT_FOUND;
import static com.fryfrog.hub.music.subsonic.SubsonicModel.*;

/**
 * Subsonic REST API 兼容端点（Navidrome 兼容）。统一入口 /rest/{method}[.view]，
 * 支持 GET/POST，参数经 query/form，响应按 f 参数输出 xml/json/jsonp。
 */
@RestController
@RequestMapping("/rest")
@RequiredArgsConstructor
@Slf4j
public class SubsonicController {

    public static final String API_VERSION = "1.16.1";
    public static final String SERVER_VERSION = "0.1.0";
    public static final String SERVER_TYPE = "fryfrog-hub";

    private final SubsonicAuthService authService;
    private final SubsonicApiService api;
    private final SubsonicRenderer renderer;
    private final MusicQueryService queryService;
    private final MusicFavoriteService favoriteService;
    private final MusicPlaylistService playlistService;
    private final MusicPlayStateService playStateService;
    private final MusicPlayStatService playStatService;
    private final MusicStreamService streamService;
    private final MusicScanService scanService;
    private final MediaLibraryService mediaLibraryService;
    private final ScrapeProgressService progressService;

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST}, value = "/{method:.+}")
    public void handle(@org.springframework.web.bind.annotation.PathVariable("method") String rawMethod,
                       HttpServletRequest request, HttpServletResponse response) throws Exception {
        String method = rawMethod.endsWith(".view") ? rawMethod.substring(0, rawMethod.length() - 5) : rawMethod;
        String format = request.getParameter("f");
        String callback = request.getParameter("callback");
        try {
            if (isBinary(method)) {
                handleBinary(method, request, response);
                return;
            }
            Envelope envelope = dispatch(method, request);
            writeEnvelope(response, envelope, format, callback);
        } catch (SubsonicApiException e) {
            log.debug("[Subsonic] {} failed: code={} message={}", method, e.getCode(), e.getMessage());
            writeEnvelope(response, errorEnvelope(e), format, callback);
        } catch (Exception e) {
            log.warn("[Subsonic] {} error: {}", method, e.getMessage(), e);
            writeEnvelope(response, errorEnvelope(new SubsonicApiException(0, e.getMessage())), format, callback);
        }
    }

    // ── 认证 ──

    private User requireUser(HttpServletRequest request) {
        return authService.authenticate(
                request.getParameter("u"),
                request.getParameter("p"),
                request.getParameter("t"),
                request.getParameter("s"));
    }

    private long userIdOf(User user) {
        return user != null ? user.getId() : UserContext.ANONYMOUS_ID;
    }

    private String usernameOf(User user) {
        return user != null ? user.getUsername() : "anonymous";
    }

    // ── 分发 ──

    private Envelope dispatch(String method, HttpServletRequest request) {
        User user = requireUser(request);
        request.setAttribute(UserContext.USER_ID_ATTR, user.getId());
        long userId = userIdOf(user);
        String username = usernameOf(user);

        Envelope env = ok();
        switch (method) {
            case "ping": break;
            case "getLicense": env.license = new License(); env.license.valid = true; break;
            case "getMusicFolders": env.musicFolders = api.getMusicFolders(userId); break;
            case "getArtists": env.artists = api.getArtists(userId); break;
            case "getIndexes": env.indexes = api.getIndexes(userId); break;
            case "getArtist": env.artist = handleGetArtist(request, userId); break;
            case "getAlbum": env.album = handleGetAlbum(request, userId); break;
            case "getSong": env.song = handleGetSong(request, userId); break;
            case "getMusicDirectory": env.directory = handleGetMusicDirectory(request, userId); break;
            case "getAlbumList":
            case "getAlbumList2":
                env.albumList = api.getAlbumList2(
                        request.getParameter("type"),
                        intParam(request, "size", 10),
                        intParam(request, "offset", 0),
                        intParam(request, "fromYear", null),
                        intParam(request, "toYear", null),
                        request.getParameter("genre"),
                        userId);
                break;
            case "getRandomSongs":
                env.randomSongs = api.getRandomSongs(
                        intParam(request, "size", 10),
                        request.getParameter("genre"),
                        intParam(request, "fromYear", null),
                        intParam(request, "toYear", null),
                        userId);
                break;
            case "getSongsByGenre":
                env.songsByGenre = api.getSongsByGenre(
                        request.getParameter("genre"),
                        intParam(request, "count", 10),
                        intParam(request, "offset", 0),
                        userId);
                break;
            case "getGenres": env.genres = api.getGenres(); break;
            case "search":
            case "search2":
                env.searchResult2 = api.search2(
                        request.getParameter("query") != null ? request.getParameter("query") : request.getParameter("any"),
                        intParam(request, "artistCount", 20),
                        intParam(request, "albumCount", 20),
                        intParam(request, "songCount", 20),
                        userId);
                env.searchResult3 = api.search3(
                        request.getParameter("query") != null ? request.getParameter("query") : request.getParameter("any"),
                        intParam(request, "artistCount", 20),
                        intParam(request, "albumCount", 20),
                        intParam(request, "songCount", 20),
                        userId);
                break;
            case "search3":
                env.searchResult3 = api.search3(
                        request.getParameter("query"),
                        intParam(request, "artistCount", 20),
                        intParam(request, "albumCount", 20),
                        intParam(request, "songCount", 20),
                        userId);
                break;
            case "getNowPlaying": env.nowPlaying = handleNowPlaying(userId); break;
            case "getStarred":
            case "getStarred2": {
                Starred starred = api.getStarred2(userId);
                env.starred = starred;
                env.starred2 = starred;
                break;
            }
            case "getPlaylists": env.playlists = api.getPlaylists(userId); break;
            case "getPlaylist":
                env.playlist = api.getPlaylist(userId, requiredSongParam(request, "id", SubsonicIds::parsePlaylist));
                break;
            case "createPlaylist": env.playlist = handleCreatePlaylist(request, userId); break;
            case "updatePlaylist": handleUpdatePlaylist(request, userId); break;
            case "deletePlaylist":
                playlistService.deletePlaylist(requiredSongParam(request, "id", SubsonicIds::parsePlaylist));
                break;
            case "star": handleStar(request, userId, true); break;
            case "unstar": handleStar(request, userId, false); break;
            case "setRating": handleSetRating(request, userId); break;
            case "scrobble": handleScrobble(request, userId, username); break;
            case "getBookmarks": env.bookmarks = api.getBookmarks(userId, username); break;
            case "createBookmark": handleCreateBookmark(request, userId); break;
            case "deleteBookmark":
                playStateService.deleteBookmark(userId, requiredSongParam(request, "id", SubsonicIds::parseSongLenient));
                break;
            case "getPlayQueue": env.playQueue = api.getPlayQueue(userId, username); break;
            case "savePlayQueue": handleSavePlayQueue(request, userId); break;
            case "getUser":
            case "getUsers": {
                Users users = new Users();
                users.user = List.of(api.getUser(user));
                env.user = users.user.get(0);
                env.users = users;
                break;
            }
            case "getScanStatus": env.scanStatus = handleScanStatus(); break;
            case "startScan": handleStartScan(); break;
            case "getLyrics": env.lyrics = handleGetLyrics(request, userId); break;
            case "getTopSongs": env.topSongs = new Songs(); env.topSongs.song = List.of(); break;
            case "getSimilarSongs":
            case "getSimilarSongs2": env.similarSongs = new Songs(); env.similarSongs.song = List.of(); break;
            case "getArtistInfo":
            case "getArtistInfo2":
            case "getAlbumInfo":
            case "getAlbumInfo2":
            case "getVideos":
            case "getVideoInfo":
                break;
            default:
                throw new SubsonicApiException(0, "Method not implemented: " + method);
        }
        return env;
    }

    private void handleBinary(String method, HttpServletRequest request, HttpServletResponse response) throws Exception {
        User user = requireUser(request);
        request.setAttribute(UserContext.USER_ID_ATTR, user.getId());
        long userId = userIdOf(user);

        switch (method) {
            case "stream" -> {
                MusicSong song = queryService.getSong(requiredSongParam(request, "id", SubsonicIds::parseSongLenient));
                if (song == null) throw new SubsonicApiException(ERROR_NOT_FOUND, "Song not found");
                playStatService.registerNowPlaying(userId, usernameOf(user), clientName(request), song.getId(), System.currentTimeMillis());
                streamService.stream(response, song, request.getHeader(HttpHeaders.RANGE), false);
            }
            case "download" -> {
                MusicSong song = queryService.getSong(requiredSongParam(request, "id", SubsonicIds::parseSongLenient));
                if (song == null) throw new SubsonicApiException(ERROR_NOT_FOUND, "Song not found");
                streamService.stream(response, song, null, true);
            }
            case "getCoverArt" -> handleCoverArt(request, response);
            case "getAvatar" -> handleAvatar(request, response);
            case "hls" -> throw new SubsonicApiException(0, "HLS not supported");
            default -> throw new SubsonicApiException(0, "Method not implemented: " + method);
        }
    }

    // ── 浏览 ──

    private Artist handleGetArtist(HttpServletRequest request, long userId) {
        Long artistId = SubsonicIds.parseArtist(request.getParameter("id"));
        MusicArtist artist = artistId != null ? queryService.getArtist(artistId) : null;
        if (artist == null) {
            // 兼容 album/song id → 定位所属歌手
            MusicSong song = queryService.getSong(SubsonicIds.parseSongLenient(request.getParameter("id")));
            if (song != null && song.getArtist() != null) {
                artist = queryService.getArtist(song.getArtist().getId());
            }
        }
        if (artist == null) throw new SubsonicApiException(ERROR_NOT_FOUND, "Artist not found");
        return api.toArtistDetail(artist, userId);
    }

    private Album handleGetAlbum(HttpServletRequest request, long userId) {
        Long albumId = SubsonicIds.parseAlbum(request.getParameter("id"));
        MusicAlbum album = albumId != null ? queryService.getAlbum(albumId) : null;
        if (album == null) {
            MusicSong song = queryService.getSong(SubsonicIds.parseSongLenient(request.getParameter("id")));
            if (song != null && song.getAlbum() != null) {
                album = queryService.getAlbum(song.getAlbum().getId());
            }
        }
        if (album == null) throw new SubsonicApiException(ERROR_NOT_FOUND, "Album not found");
        return api.toAlbumDetail(album, userId);
    }

    private Child handleGetSong(HttpServletRequest request, long userId) {
        MusicSong song = queryService.getSong(SubsonicIds.parseSongLenient(request.getParameter("id")));
        if (song == null) throw new SubsonicApiException(ERROR_NOT_FOUND, "Song not found");
        return api.toSong(song, userId);
    }

    private Directory handleGetMusicDirectory(HttpServletRequest request, long userId) {
        String id = request.getParameter("id");
        Long artistId = SubsonicIds.parseArtist(id);
        Long albumId = SubsonicIds.parseAlbum(id);
        Directory dir = new Directory();
        if (artistId != null) {
            MusicArtist artist = queryService.getArtist(artistId);
            if (artist == null) throw new SubsonicApiException(ERROR_NOT_FOUND, "Directory not found");
            dir.id = SubsonicIds.artist(artistId);
            dir.name = artist.getName();
            dir.child = queryService.getAlbumsByArtist(artistId).stream()
                    .map(a -> api.toDirChild(a, userId)).collect(java.util.stream.Collectors.toList());
        } else if (albumId != null) {
            MusicAlbum album = queryService.getAlbum(albumId);
            if (album == null) throw new SubsonicApiException(ERROR_NOT_FOUND, "Directory not found");
            dir.id = SubsonicIds.album(albumId);
            dir.name = album.getTitle();
            dir.child = queryService.getSongsByAlbum(albumId).stream()
                    .map(s -> api.toSong(s, userId)).collect(java.util.stream.Collectors.toList());
        } else {
            throw new SubsonicApiException(ERROR_NOT_FOUND, "Directory not found");
        }
        return dir;
    }

    private NowPlaying handleNowPlaying(long userId) {
        NowPlaying dto = new NowPlaying();
        dto.entry = new ArrayList<>();
        for (MusicPlayStatService.NowPlaying np : playStatService.getNowPlaying()) {
            Child entry = api.toSong(np.song(), userId);
            entry.username = np.username();
            entry.minutesAgo = (int) ((System.currentTimeMillis() - np.startedAtMillis()) / 60000);
            dto.entry.add(entry);
        }
        return dto;
    }

    // ── 标注（star/rating/scrobble）──

    private void handleStar(HttpServletRequest request, long userId, boolean starred) {
        String[] ids = request.getParameterValues("id");
        if (ids != null) {
            for (String id : ids) {
                Long songId = SubsonicIds.parseSong(id);
                if (songId != null) {
                    favoriteService.setStar(userId, SubsonicApiService.MusicStarType.SONG, songId, starred);
                    continue;
                }
                Long albumId = SubsonicIds.parseAlbum(id);
                if (albumId != null) {
                    favoriteService.setStar(userId, SubsonicApiService.MusicStarType.ALBUM, albumId, starred);
                    continue;
                }
                Long artistId = SubsonicIds.parseArtist(id);
                if (artistId != null) {
                    favoriteService.setStar(userId, SubsonicApiService.MusicStarType.ARTIST, artistId, starred);
                }
            }
        }
        String[] albumIds = request.getParameterValues("albumId");
        if (albumIds != null) {
            for (String albumId : albumIds) {
                Long id = SubsonicIds.parseAlbum(albumId);
                if (id != null) favoriteService.setStar(userId, SubsonicApiService.MusicStarType.ALBUM, id, starred);
            }
        }
        String[] artistIds = request.getParameterValues("artistId");
        if (artistIds != null) {
            for (String artistId : artistIds) {
                Long id = SubsonicIds.parseArtist(artistId);
                if (id != null) favoriteService.setStar(userId, SubsonicApiService.MusicStarType.ARTIST, id, starred);
            }
        }
    }

    private void handleSetRating(HttpServletRequest request, long userId) {
        String id = request.getParameter("id");
        int rating = intParam(request, "rating", 0);
        Long songId = SubsonicIds.parseSong(id);
        if (songId != null) {
            favoriteService.setRating(userId, SubsonicApiService.MusicStarType.SONG, songId, rating);
            return;
        }
        Long albumId = SubsonicIds.parseAlbum(id);
        if (albumId != null) {
            favoriteService.setRating(userId, SubsonicApiService.MusicStarType.ALBUM, albumId, rating);
            return;
        }
        Long artistId = SubsonicIds.parseArtist(id);
        if (artistId != null) {
            favoriteService.setRating(userId, SubsonicApiService.MusicStarType.ARTIST, artistId, rating);
        }
    }

    private void handleScrobble(HttpServletRequest request, long userId, String username) {
        String[] ids = request.getParameterValues("id");
        String[] times = request.getParameterValues("time");
        boolean submission = !"false".equalsIgnoreCase(request.getParameter("submission"));
        if (ids == null) return;
        for (int i = 0; i < ids.length; i++) {
            Long songId = SubsonicIds.parseSongLenient(ids[i]);
            if (songId == null) continue;
            Long time = times != null && i < times.length && times[i] != null && !times[i].isBlank()
                    ? Long.parseLong(times[i]) : null;
            playStatService.scrobble(userId, songId, time, submission);
        }
        if (!submission && ids.length > 0) {
            Long songId = SubsonicIds.parseSongLenient(ids[0]);
            if (songId != null) {
                playStatService.registerNowPlaying(userId, username, clientName(request), songId, System.currentTimeMillis());
            }
        }
    }

    // ── 播放列表 ──

    private Playlist handleCreatePlaylist(HttpServletRequest request, long userId) {
        Long playlistId = SubsonicIds.parsePlaylist(request.getParameter("playlistId"));
        String name = request.getParameter("name");
        List<Long> songIds = songParams(request, "songId");

        MusicPlaylist playlist;
        if (playlistId != null) {
            playlist = playlistService.getPlaylist(playlistId);
            if (!playlistService.canWrite(playlist, userId)) {
                throw new SubsonicApiException(ERROR_NOT_FOUND, "Playlist not found");
            }
            if (name != null) playlist.setName(name);
            playlistService.replaceSongs(playlist, songIds);
            playlist = playlistService.updatePlaylist(playlistId, name, null, null);
        } else {
            playlist = playlistService.createPlaylist(userId, name != null ? name : "New Playlist", null, false, songIds);
        }
        return api.getPlaylist(userId, playlist.getId());
    }

    private void handleUpdatePlaylist(HttpServletRequest request, long userId) {
        Long playlistId = requiredSongParam(request, "playlistId", SubsonicIds::parsePlaylist);
        MusicPlaylist playlist = playlistService.getPlaylist(playlistId);
        if (!playlistService.canWrite(playlist, userId)) {
            throw new SubsonicApiException(ERROR_NOT_FOUND, "Playlist not found");
        }
        String name = request.getParameter("name");
        String comment = request.getParameter("comment");
        Boolean isPublic = request.getParameter("public") == null ? null : "true".equalsIgnoreCase(request.getParameter("public"));
        if (name != null || comment != null || isPublic != null) {
            playlistService.updatePlaylist(playlistId, name, comment, isPublic);
        }
        List<Long> toAdd = songParams(request, "songIdToAdd");
        if (!toAdd.isEmpty()) {
            playlistService.addSongs(playlist, toAdd);
        }
        String[] toRemove = request.getParameterValues("songIndexToRemove");
        if (toRemove != null) {
            playlistService.removeSongs(playlist, java.util.Arrays.stream(toRemove)
                    .map(Integer::parseInt).toList());
        }
    }

    // ── 书签 / 队列 ──

    private void handleCreateBookmark(HttpServletRequest request, long userId) {
        Long songId = requiredSongParam(request, "id", SubsonicIds::parseSongLenient);
        Double position = request.getParameter("position") == null ? null : Double.parseDouble(request.getParameter("position"));
        playStateService.createBookmark(userId, songId, position, request.getParameter("comment"));
    }

    private void handleSavePlayQueue(HttpServletRequest request, long userId) {
        List<Long> songIds = songParams(request, "id");
        Long current = SubsonicIds.parseSongLenient(request.getParameter("current"));
        Double position = request.getParameter("position") == null ? null : Double.parseDouble(request.getParameter("position"));
        playStateService.savePlayQueue(userId, songIds, current, position, System.currentTimeMillis());
    }

    // ── 歌词 / 封面 ──

    private Lyrics handleGetLyrics(HttpServletRequest request, long userId) {
        Lyrics lyrics = new Lyrics();
        MusicSong song = queryService.getSong(SubsonicIds.parseSongLenient(request.getParameter("id")));
        if (song == null) {
            String artist = request.getParameter("artist");
            String title = request.getParameter("title");
            if (artist != null && title != null) {
                song = queryService.searchSongs(title, 1).stream()
                        .filter(s -> artist.equalsIgnoreCase(s.getArtistName()))
                        .findFirst().orElse(null);
            }
        }
        if (song == null) return lyrics;
        lyrics.artist = song.getArtistName();
        lyrics.title = song.getTitle();
        if (song.getLyricsContent() != null && !song.getLyricsContent().isBlank()) {
            lyrics.value = song.getLyricsContent();
        } else if (song.getLyricsPath() != null && Files.exists(Paths.get(song.getLyricsPath()))) {
            try {
                lyrics.value = Files.readString(Paths.get(song.getLyricsPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("Failed to read lyrics {}: {}", song.getLyricsPath(), e.getMessage());
            }
        }
        return lyrics;
    }

    private void handleCoverArt(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String id = request.getParameter("id");
        String coverPath = resolveCoverPath(id);
        if (coverPath == null || !Files.exists(Paths.get(coverPath))) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Path path = Paths.get(coverPath);
        response.setContentType(MediaType.IMAGE_JPEG_VALUE);
        response.setContentLengthLong(path.toFile().length());
        try (var is = Files.newInputStream(path); var os = response.getOutputStream()) {
            is.transferTo(os);
        }
    }

    private void handleAvatar(HttpServletRequest request, HttpServletResponse response) throws Exception {
        // 无 Gravatar 配置：返回一个占位头像
        byte[] placeholder = com.fryfrog.hub.common.util.PlaceholderImageGenerator.generate("♫", 128, 128);
        response.setContentType(MediaType.IMAGE_JPEG_VALUE);
        response.getOutputStream().write(placeholder);
    }

    private String resolveCoverPath(String id) {
        if (id == null) return null;
        Long songId = SubsonicIds.parseSong(id);
        if (songId != null) {
            MusicSong song = queryService.getSong(songId);
            if (song == null) return null;
            return song.getAlbum() != null ? song.getAlbum().getCoverArtPath() : null;
        }
        Long albumId = SubsonicIds.parseAlbum(id);
        if (albumId != null) {
            MusicAlbum album = queryService.getAlbum(albumId);
            return album != null ? album.getCoverArtPath() : null;
        }
        Long artistId = SubsonicIds.parseArtist(id);
        if (artistId != null) {
            MusicArtist artist = queryService.getArtist(artistId);
            return artist != null ? artist.getCoverArtPath() : null;
        }
        Long bare = parseLongOrNull(id);
        if (bare != null) {
            MusicSong song = queryService.getSong(bare);
            if (song == null) return null;
            return song.getAlbum() != null ? song.getAlbum().getCoverArtPath() : null;
        }
        return null;
    }

    // ── 扫描 ──

    private ScanStatus handleScanStatus() {
        ScanStatus status = new ScanStatus();
        status.scanning = false;
        status.count = 0;
        status.lastScan = null;
        status.folderCount = (int) mediaLibraryService.getVisibleLibraries().stream()
                .filter(MediaLibrary::isMusicType).count();
        return status;
    }

    private void handleStartScan() {
        List<MediaLibrary> musicLibraries = mediaLibraryService.getEnabledLibraries().stream()
                .filter(MediaLibrary::isMusicType).toList();
        Thread.startVirtualThread(() -> {
            for (MediaLibrary lib : musicLibraries) {
                try {
                    scanService.scanAndSave(lib.getPath(), lib.getId());
                } catch (Exception e) {
                    log.warn("[Subsonic startScan] Failed to scan '{}': {}", lib.getName(), e.getMessage());
                }
            }
        });
    }

    // ── 信封 ──

    private Envelope ok() {
        Envelope env = new Envelope();
        env.status = "ok";
        env.version = API_VERSION;
        env.type = SERVER_TYPE;
        env.serverVersion = SERVER_VERSION;
        env.openSubsonic = true;
        return env;
    }

    private Envelope errorEnvelope(SubsonicApiException e) {
        Envelope env = new Envelope();
        env.status = "failed";
        env.version = API_VERSION;
        env.type = SERVER_TYPE;
        env.serverVersion = SERVER_VERSION;
        env.openSubsonic = true;
        SubsonicModel.Error error = new SubsonicModel.Error();
        error.code = e.getCode();
        error.message = e.getMessage();
        env.error = error;
        return env;
    }

    private void writeEnvelope(HttpServletResponse response, Envelope envelope, String format, String callback) throws Exception {
        response.setContentType(SubsonicRenderer.contentType(format));
        response.getWriter().write(renderer.render(envelope, format, callback));
    }

    // ── 参数解析 ──

    private static boolean isBinary(String method) {
        return "stream".equals(method) || "download".equals(method)
                || "getCoverArt".equals(method) || "getAvatar".equals(method)
                || "hls".equals(method);
    }

    private static Integer intParam(HttpServletRequest request, String name, Integer defaultValue) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || !value.matches("\\d+")) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface IdParser {
        Long parse(String id);
    }

    private static Long requiredSongParam(HttpServletRequest request, String name, IdParser parser) {
        String value = request.getParameter(name);
        Long id = value != null ? parser.parse(value) : null;
        if (id == null) {
            throw new SubsonicApiException(SubsonicApiException.ERROR_MISSING_PARAM, "Required parameter '" + name + "' is missing or invalid");
        }
        return id;
    }

    private static List<Long> songParams(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        List<Long> ids = new ArrayList<>();
        if (values == null) return ids;
        for (String value : values) {
            Long id = SubsonicIds.parseSongLenient(value);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static String clientName(HttpServletRequest request) {
        String client = request.getParameter("c");
        return client != null && !client.isBlank() ? client : "subsonic";
    }
}
