package com.fryfrog.hub.music.subsonic;

import com.fryfrog.hub.common.model.MediaLibrary;
import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.service.MediaLibraryService;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.music.model.MusicAlbum;
import com.fryfrog.hub.music.model.MusicArtist;
import com.fryfrog.hub.music.model.MusicBookmark;
import com.fryfrog.hub.music.model.MusicPlayQueue;
import com.fryfrog.hub.music.model.MusicPlaylist;
import com.fryfrog.hub.music.model.MusicSong;
import com.fryfrog.hub.music.repository.MusicAlbumRepository;
import com.fryfrog.hub.music.repository.MusicArtistRepository;
import com.fryfrog.hub.music.repository.MusicSongRepository;
import com.fryfrog.hub.music.service.MusicFavoriteService;
import com.fryfrog.hub.music.service.MusicPlayStatService;
import com.fryfrog.hub.music.service.MusicPlaylistService;
import com.fryfrog.hub.music.service.MusicPlayStateService;
import com.fryfrog.hub.music.service.MusicQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static com.fryfrog.hub.music.subsonic.SubsonicApiException.ERROR_NOT_FOUND;
import static com.fryfrog.hub.music.subsonic.SubsonicModel.*;

/**
 * Subsonic 协议业务逻辑：DB 实体 → Subsonic DTO 映射与列表构建。
 */
@Service
@RequiredArgsConstructor
public class SubsonicApiService {

    private static final Random RANDOM = ThreadLocalRandom.current();

    private final MusicQueryService queryService;
    private final MusicFavoriteService favoriteService;
    private final MusicPlayStatService playStatService;
    private final MusicPlaylistService playlistService;
    private final MusicPlayStateService playStateService;
    private final MusicArtistRepository artistRepository;
    private final MusicAlbumRepository albumRepository;
    private final MusicSongRepository songRepository;
    private final MediaLibraryService mediaLibraryService;
    private final UserService userService;

    // ── 基础映射 ──

    public Artist toArtist(MusicArtist artist, long userId) {
        Artist dto = new Artist();
        dto.id = SubsonicIds.artist(artist.getId());
        dto.name = artist.getName();
        dto.coverArt = artist.getCoverArtPath() != null ? SubsonicIds.artist(artist.getId()) : null;
        dto.albumCount = albumRepository.findByArtist_IdOrderByYearAsc(artist.getId()).size();
        dto.starred = favoriteService.isStarred(userId, MusicStarType.ARTIST, artist.getId());
        return dto;
    }

    public Artist toArtistDetail(MusicArtist artist, long userId) {
        Artist dto = toArtist(artist, userId);
        List<MusicAlbum> albums = queryService.getAlbumsByArtist(artist.getId());
        Set<Long> starred = favoriteService.starredIds(userId, MusicStarType.ALBUM,
                albums.stream().map(MusicAlbum::getId).toList());
        Map<Long, Integer> ratings = favoriteService.ratingMap(userId, MusicStarType.ALBUM,
                albums.stream().map(MusicAlbum::getId).toList());
        dto.album = albums.stream().map(a -> toAlbum(a, starred.contains(a.getId()),
                ratings.get(a.getId()))).toList();
        return dto;
    }

    public Album toAlbum(MusicAlbum album, long userId) {
        boolean starred = favoriteService.isStarred(userId, MusicStarType.ALBUM, album.getId());
        Integer rating = favoriteService.getRating(userId, MusicStarType.ALBUM, album.getId());
        return toAlbum(album, starred, rating);
    }

    public Album toAlbum(MusicAlbum album, boolean starred, Integer rating) {
        Album dto = new Album();
        dto.id = SubsonicIds.album(album.getId());
        dto.name = album.getTitle();
        dto.artist = album.getArtistName();
        dto.artistId = album.getArtist() != null ? SubsonicIds.artist(album.getArtist().getId()) : null;
        dto.coverArt = album.getCoverArtPath() != null ? SubsonicIds.album(album.getId()) : null;
        dto.songCount = album.getTrackCount();
        dto.duration = totalDuration(album.getId());
        dto.year = album.getYear();
        dto.genre = album.getGenre();
        dto.starred = starred;
        dto.userRating = rating;
        dto.created = album.getCreatedAt() != null ? album.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
        return dto;
    }

    public Album toAlbumDetail(MusicAlbum album, long userId) {
        Album dto = toAlbum(album, userId);
        List<MusicSong> songs = queryService.getSongsByAlbum(album.getId());
        List<Long> songIds = songs.stream().map(MusicSong::getId).toList();
        Set<Long> starred = favoriteService.starredIds(userId, MusicStarType.SONG, songIds);
        Map<Long, Integer> ratings = favoriteService.ratingMap(userId, MusicStarType.SONG, songIds);
        Map<Long, Integer> playCounts = playStatService.playCountMap(userId, songIds);
        dto.song = songs.stream()
                .map(s -> toSong(s, starred.contains(s.getId()), ratings.get(s.getId()), playCounts.getOrDefault(s.getId(), 0)))
                .toList();
        dto.songCount = dto.song.size();
        return dto;
    }

    public Child toSong(MusicSong song, long userId) {
        List<Long> ids = List.of(song.getId());
        boolean starred = favoriteService.isStarred(userId, MusicStarType.SONG, song.getId());
        Integer rating = favoriteService.getRating(userId, MusicStarType.SONG, song.getId());
        int playCount = playStatService.getPlayCount(userId, song.getId());
        return toSong(song, starred, rating, playCount);
    }

    public Child toSong(MusicSong song, boolean starred, Integer rating, int playCount) {
        Child dto = new Child();
        dto.id = SubsonicIds.song(song.getId());
        dto.parent = song.getAlbum() != null ? SubsonicIds.album(song.getAlbum().getId()) : null;
        dto.isDir = false;
        dto.title = song.getTitle();
        dto.album = song.getAlbumName();
        dto.artist = song.getArtistName();
        dto.track = song.getTrackNumber();
        dto.discNumber = song.getDiscNumber();
        dto.year = song.getYear();
        dto.genre = song.getGenre();
        dto.coverArt = song.getAlbum() != null && song.getAlbum().getCoverArtPath() != null
                ? SubsonicIds.album(song.getAlbum().getId()) : null;
        dto.size = song.getFileSize();
        dto.contentType = mimeType(song.getFormat());
        dto.suffix = song.getFormat() != null ? song.getFormat().toLowerCase() : null;
        dto.duration = song.getDurationSeconds() != null ? song.getDurationSeconds().intValue() : null;
        dto.bitRate = song.getBitRate();
        dto.playCount = playCount;
        dto.starred = starred;
        dto.userRating = rating;
        dto.artistId = song.getArtist() != null ? SubsonicIds.artist(song.getArtist().getId()) : null;
        dto.albumId = song.getAlbum() != null ? SubsonicIds.album(song.getAlbum().getId()) : null;
        dto.path = song.getFilePath();
        dto.type = "music";
        dto.created = song.getCreatedAt() != null ? song.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
        return dto;
    }

    /** 目录浏览子项：专辑→isDir=true 的子项。 */
    public Child toDirChild(MusicAlbum album, long userId) {
        Child dto = new Child();
        dto.id = SubsonicIds.album(album.getId());
        dto.parent = album.getArtist() != null ? SubsonicIds.artist(album.getArtist().getId()) : null;
        dto.isDir = true;
        dto.title = album.getTitle();
        dto.album = album.getTitle();
        dto.artist = album.getArtistName();
        dto.artistId = album.getArtist() != null ? SubsonicIds.artist(album.getArtist().getId()) : null;
        dto.coverArt = album.getCoverArtPath() != null ? SubsonicIds.album(album.getId()) : null;
        dto.year = album.getYear();
        dto.genre = album.getGenre();
        dto.type = "album";
        return dto;
    }

    private Integer totalDuration(Long albumId) {
        return queryService.getSongsByAlbum(albumId).stream()
                .map(MusicSong::getDurationSeconds)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Double::intValue)
                .sum();
    }

    private static String mimeType(String format) {
        if (format == null) return "audio/mpeg";
        return switch (format.toLowerCase()) {
            case "flac" -> "audio/flac";
            case "m4a", "mp4", "alac" -> "audio/mp4";
            case "ogg", "oga" -> "audio/ogg";
            case "opus" -> "audio/opus";
            case "wav" -> "audio/wav";
            case "wma" -> "audio/x-ms-wma";
            case "aac" -> "audio/aac";
            case "webm" -> "audio/webm";
            default -> "audio/mpeg";
        };
    }

    // ── 媒体库 ──

    public MusicFolders getMusicFolders(long userId) {
        List<MediaLibrary> libraries = mediaLibraryService.getVisibleLibraries().stream()
                .filter(MediaLibrary::isMusicType)
                .toList();
        MusicFolders folders = new MusicFolders();
        folders.musicFolder = new ArrayList<>();
        for (MediaLibrary lib : libraries) {
            MusicFolder folder = new MusicFolder();
            folder.id = String.valueOf(lib.getId());
            folder.name = lib.getName();
            folders.musicFolder.add(folder);
        }
        return folders;
    }

    // ── 索引 / 歌手 ──

    public Indexes getIndexes(long userId) {
        return buildIndexes(queryService.getAllArtists(), userId);
    }

    public Artists getArtists(long userId) {
        Artists artists = new Artists();
        artists.index = buildIndexes(queryService.getAllArtists(), userId).index;
        return artists;
    }

    private Indexes buildIndexes(List<MusicArtist> all, long userId) {
        Map<String, List<Artist>> byLetter = new LinkedHashMap<>();
        for (MusicArtist artist : all) {
            String name = artist.getName();
            String letter = name == null || name.isBlank() ? "#" : name.substring(0, 1).toUpperCase();
            if (!letter.matches("[A-Z]")) letter = "#";
            byLetter.computeIfAbsent(letter, k -> new ArrayList<>()).add(toArtist(artist, userId));
        }
        Indexes indexes = new Indexes();
        indexes.index = new ArrayList<>();
        byLetter.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            Index index = new Index();
            index.name = e.getKey();
            index.artist = e.getValue();
            indexes.index.add(index);
        });
        return indexes;
    }

    // ── 专辑列表 ──

    public AlbumList getAlbumList2(String type, int size, int offset, Integer fromYear, Integer toYear, String genre, long userId) {
        List<MusicAlbum> albums = switch (type == null ? "" : type) {
            case "newest" -> albumRepository.findByLibraryIdInOrderByYearDesc(
                    queryService.allowedLibraryIds(), PageRequest.of(offset, size));
            case "alphabeticalByName" -> albumRepository.findByLibraryIdInOrderByTitleAsc(
                    queryService.allowedLibraryIds(), PageRequest.of(offset, size));
            case "alphabeticalByArtist" -> queryService.getAllAlbums().stream()
                    .sorted(Comparator.comparing(MusicAlbum::getArtistName, Comparator.nullsLast(String::compareTo)))
                    .skip(offset).limit(size).toList();
            case "frequent" -> playStatService.getNowPlaying().isEmpty() ? queryService.getAllAlbums()
                    .stream().skip(offset).limit(size).toList() : frequentAlbums(offset, size);
            case "recent" -> recentAlbums(offset, size);
            case "starred" -> starredAlbums(userId).stream().skip(offset).limit(size).toList();
            case "highest" -> queryService.getAllAlbums().stream().skip(offset).limit(size).toList();
            case "byYear" -> {
                int from = fromYear != null ? fromYear : 1900;
                int to = toYear != null ? toYear : 3000;
                yield albumRepository.findByLibraryIdInAndYearBetweenOrderByYearDesc(
                        queryService.allowedLibraryIds(), Math.min(from, to), Math.max(from, to))
                        .stream().skip(offset).limit(size).toList();
            }
            case "byGenre" -> genre == null ? List.of() :
                    albumRepository.findByLibraryIdInAndGenreIgnoreCase(queryService.allowedLibraryIds(), genre)
                            .stream().skip(offset).limit(size).toList();
            default -> queryService.getAllAlbums().stream()
                    .filter(a -> a.getCreatedAt() != null)
                    .sorted(Comparator.comparing(MusicAlbum::getCreatedAt).reversed())
                    .skip(offset).limit(size).toList();
        };

        List<Long> ids = albums.stream().map(MusicAlbum::getId).toList();
        Set<Long> starred = favoriteService.starredIds(userId, MusicStarType.ALBUM, ids);
        Map<Long, Integer> ratings = favoriteService.ratingMap(userId, MusicStarType.ALBUM, ids);
        AlbumList result = new AlbumList();
        result.album = albums.stream()
                .map(a -> toAlbum(a, starred.contains(a.getId()), ratings.get(a.getId())))
                .toList();
        return result;
    }

    private List<MusicAlbum> frequentAlbums(int offset, int size) {
        return queryService.getAllAlbums().stream()
                .skip(offset).limit(size).toList();
    }

    private List<MusicAlbum> recentAlbums(int offset, int size) {
        return queryService.getAllAlbums().stream()
                .filter(a -> a.getUpdatedAt() != null)
                .sorted(Comparator.comparing(MusicAlbum::getUpdatedAt).reversed())
                .skip(offset).limit(size).toList();
    }

    private List<MusicAlbum> starredAlbums(long userId) {
        List<Long> ids = favoriteService.starredIdsOfType(userId, MusicStarType.ALBUM).stream().toList();
        List<MusicAlbum> result = new ArrayList<>();
        for (Long id : ids) {
            MusicAlbum album = queryService.getAlbum(id);
            if (album != null) result.add(album);
        }
        return result;
    }

    // ── 随机 / 流派 ──

    public Songs getRandomSongs(int size, String genre, Integer fromYear, Integer toYear, long userId) {
        List<MusicSong> pool = queryService.getAllSongs(10000).stream()
                .filter(s -> genre == null || genre.equalsIgnoreCase(s.getGenre()))
                .filter(s -> fromYear == null || (s.getYear() != null && s.getYear() >= fromYear))
                .filter(s -> toYear == null || (s.getYear() != null && s.getYear() <= toYear))
                .toList();
        List<MusicSong> picked = new ArrayList<>();
        if (!pool.isEmpty()) {
            for (int i = 0; i < Math.min(size, pool.size()); i++) {
                picked.add(pool.get(RANDOM.nextInt(pool.size())));
            }
        }
        return toSongs(picked, userId);
    }

    public Songs getSongsByGenre(String genre, int count, int offset, long userId) {
        return toSongs(queryService.getSongsByGenre(genre, offset + count).stream()
                .skip(offset).toList(), userId);
    }

    public Genres getGenres() {
        List<String> genres = queryService.getAllGenres();
        Genres dto = new Genres();
        dto.genre = genres.stream().map(g -> {
            Genre genre = new Genre();
            genre.name = g;
            genre.songCount = songRepository.findByLibraryIdInAndGenreIgnoreCase(queryService.allowedLibraryIds(), g).size();
            genre.albumCount = albumRepository.findByLibraryIdInAndGenreIgnoreCase(queryService.allowedLibraryIds(), g).size();
            return genre;
        }).toList();
        return dto;
    }

    private Songs toSongs(List<MusicSong> songs, long userId) {
        List<Long> ids = songs.stream().map(MusicSong::getId).toList();
        Set<Long> starred = favoriteService.starredIds(userId, MusicStarType.SONG, ids);
        Map<Long, Integer> ratings = favoriteService.ratingMap(userId, MusicStarType.SONG, ids);
        Map<Long, Integer> playCounts = playStatService.playCountMap(userId, ids);
        Songs dto = new Songs();
        dto.song = songs.stream()
                .map(s -> toSong(s, starred.contains(s.getId()), ratings.get(s.getId()), playCounts.getOrDefault(s.getId(), 0)))
                .toList();
        return dto;
    }

    // ── 搜索 ──

    public SearchResult search3(String query, int artistCount, int albumCount, int songCount, long userId) {
        SearchResult result = new SearchResult();
        if (query == null || query.isBlank()) {
            return result;
        }
        result.artist = queryService.searchArtists(query, artistCount).stream()
                .map(a -> toArtist(a, userId)).toList();
        result.album = queryService.searchAlbums(query, albumCount).stream()
                .map(a -> toAlbum(a, userId)).toList();
        result.song = queryService.searchSongs(query, songCount).stream()
                .map(s -> toSong(s, userId)).toList();
        return result;
    }

    public SearchResult search2(String query, int artistCount, int albumCount, int songCount, long userId) {
        return search3(query, artistCount, albumCount, songCount, userId);
    }

    // ── 收藏 ──

    public Starred getStarred2(long userId) {
        Starred starred = new Starred();
        List<Long> artistIds = favoriteService.starredIdsOfType(userId, MusicStarType.ARTIST).stream().toList();
        starred.artist = artistIds.stream().map(queryService::getArtist).filter(java.util.Objects::nonNull)
                .map(a -> toArtist(a, userId)).toList();
        List<Long> albumIds = favoriteService.starredIdsOfType(userId, MusicStarType.ALBUM).stream().toList();
        starred.album = albumIds.stream().map(queryService::getAlbum).filter(java.util.Objects::nonNull)
                .map(a -> toAlbum(a, userId)).toList();
        List<Long> songIds = favoriteService.starredIdsOfType(userId, MusicStarType.SONG).stream().toList();
        starred.song = songIds.stream().map(queryService::getSong).filter(java.util.Objects::nonNull)
                .map(s -> toSong(s, userId)).toList();
        return starred;
    }

    // ── 播放列表 ──

    public Playlists getPlaylists(long userId) {
        Playlists dto = new Playlists();
        dto.playlist = playlistService.getPlaylists(userId).stream()
                .map(p -> toPlaylist(p, userId, false)).toList();
        return dto;
    }

    public Playlist getPlaylist(long userId, Long playlistId) {
        MusicPlaylist playlist = playlistService.getPlaylist(playlistId);
        if (!playlistService.canRead(playlist, userId)) {
            throw new SubsonicApiException(ERROR_NOT_FOUND, "Playlist not found");
        }
        return toPlaylist(playlist, userId, true);
    }

    private Playlist toPlaylist(MusicPlaylist playlist, long userId, boolean withEntries) {
        Playlist dto = new Playlist();
        dto.id = SubsonicIds.playlist(playlist.getId());
        dto.name = playlist.getName();
        dto.comment = playlist.getComment();
        dto.isPublic = Boolean.TRUE.equals(playlist.getIsPublic());
        try {
            dto.owner = userService.getUser(playlist.getUserId()).getUsername();
        } catch (Exception e) {
            dto.owner = String.valueOf(playlist.getUserId());
        }
        List<MusicSong> songs = playlistService.getEntries(playlist.getId()).stream()
                .map(e -> queryService.getSong(e.getSong().getId()))
                .filter(java.util.Objects::nonNull)
                .toList();
        dto.songCount = songs.size();
        dto.duration = songs.stream().mapToInt(s -> s.getDurationSeconds() == null ? 0 : s.getDurationSeconds().intValue()).sum();
        dto.created = playlist.getCreatedAt() != null ? playlist.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
        if (withEntries) {
            dto.entry = songs.stream().map(s -> toSong(s, userId)).toList();
        }
        return dto;
    }

    // ── 书签 / 队列 ──

    public Bookmarks getBookmarks(long userId, String username) {
        Bookmarks dto = new Bookmarks();
        dto.bookmark = new ArrayList<>();
        for (MusicBookmark b : playStateService.getBookmarks(userId)) {
            Bookmark bookmark = new Bookmark();
            bookmark.username = username;
            bookmark.position = b.getPositionSeconds() != null ? b.getPositionSeconds().intValue() : 0;
            bookmark.comment = b.getComment();
            bookmark.created = b.getCreatedAtMillis();
            bookmark.changed = b.getUpdatedAt() != null ? b.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
            bookmark.entry = toSong(b.getSong(), userId);
            dto.bookmark.add(bookmark);
        }
        return dto;
    }

    public PlayQueue getPlayQueue(long userId, String username) {
        MusicPlayQueue queue = playStateService.getPlayQueue(userId);
        if (queue == null) return null;
        PlayQueue dto = new PlayQueue();
        dto.current = queue.getCurrentSongId() != null ? SubsonicIds.song(queue.getCurrentSongId()) : null;
        dto.position = queue.getPositionSeconds() != null ? queue.getPositionSeconds().longValue() : null;
        dto.username = username;
        dto.changed = queue.getChangedAtMillis();
        dto.entry = MusicPlayStateService.parseEntryIds(queue.getEntryIds()).stream()
                .map(queryService::getSong).filter(java.util.Objects::nonNull)
                .map(s -> toSong(s, userId)).toList();
        return dto;
    }

    // ── 用户 ──

    public com.fryfrog.hub.music.subsonic.SubsonicModel.User getUser(com.fryfrog.hub.common.model.User loggedUser) {
        com.fryfrog.hub.music.subsonic.SubsonicModel.User dto = new com.fryfrog.hub.music.subsonic.SubsonicModel.User();
        dto.username = loggedUser.getUsername();
        dto.email = null;
        dto.adminRole = loggedUser.isAdmin();
        dto.streamRole = true;
        dto.downloadRole = true;
        dto.coverArtRole = true;
        dto.commentRole = true;
        dto.podcastRole = false;
        dto.shareRole = false;
        dto.jukeboxRole = false;
        dto.scrobblingEnabled = true;
        dto.maxBitRate = 0;
        Collection<Long> libs = mediaLibraryService.getAllowedLibraryIds(loggedUser.getId());
        dto.folder = libs.isEmpty() ? null : String.join(",", libs.stream().map(String::valueOf).toList());
        return dto;
    }

    /** Subsonic 用户目标类型常量（避免与实体耦合）。 */
    public static final class MusicStarType {
        public static final String SONG = "SONG";
        public static final String ALBUM = "ALBUM";
        public static final String ARTIST = "ARTIST";

        private MusicStarType() {
        }
    }
}