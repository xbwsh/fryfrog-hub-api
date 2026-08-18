package com.fryfrog.hub.music.subsonic;

/**
 * Subsonic 层 ID 编解码：`ar-<id>`/`al-<id>`/`tr-<id>`/`pl-<id>`。
 * 前缀区分类型避免不同表 Long 自增 ID 冲突。
 */
public final class SubsonicIds {

    public static final String PREFIX_ARTIST = "ar-";
    public static final String PREFIX_ALBUM = "al-";
    public static final String PREFIX_SONG = "tr-";
    public static final String PREFIX_PLAYLIST = "pl-";

    private SubsonicIds() {
    }

    public static String artist(long id) {
        return PREFIX_ARTIST + id;
    }

    public static String album(long id) {
        return PREFIX_ALBUM + id;
    }

    public static String song(long id) {
        return PREFIX_SONG + id;
    }

    public static String playlist(long id) {
        return PREFIX_PLAYLIST + id;
    }

    public static Long parseArtist(String id) {
        return parse(id, PREFIX_ARTIST);
    }

    public static Long parseAlbum(String id) {
        return parse(id, PREFIX_ALBUM);
    }

    public static Long parseSong(String id) {
        return parse(id, PREFIX_SONG);
    }

    public static Long parsePlaylist(String id) {
        return parse(id, PREFIX_PLAYLIST);
    }

    private static Long parse(String id, String prefix) {
        if (id == null || !id.startsWith(prefix)) return null;
        try {
            return Long.parseLong(id.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 兼容裸数字 ID（客户端可能直接把 song id 传回来）。 */
    public static Long parseSongLenient(String id) {
        Long songId = parseSong(id);
        if (songId != null) return songId;
        if (id != null && id.matches("\\d+")) return Long.parseLong(id);
        return null;
    }

    public static String coverArtForSong(long songId) {
        return song(songId);
    }

    public static String coverArtForAlbum(long albumId) {
        return album(albumId);
    }

    public static String coverArtForArtist(long artistId) {
        return artist(artistId);
    }
}