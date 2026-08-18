package com.fryfrog.hub.music.subsonic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;

import java.util.List;

/**
 * Subsonic API 响应模型（兼容 Navidrome / Subsonic v1.16.1）。
 * <p>
 * 同一组 DTO 同时输出 JSON 与 XML：JSON 用 ObjectMapper（WRAP_ROOT_VALUE），
 * XML 用 XmlMapper；字段用 {@code @JacksonXmlProperty(isAttribute=true)} 标记属性。
 */
@JsonRootName("subsonic-response")
@JacksonXmlRootElement(localName = "subsonic-response")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubsonicModel {

    @JsonPropertyOrder({
            "status", "version", "type", "serverVersion", "openSubsonic",
            "license", "musicFolders", "indexes", "artists", "artist", "album",
            "song", "randomSongs", "songsByGenre", "albumList", "searchResult2",
            "searchResult3", "topSongs", "similarSongs",
            "genres", "nowPlaying", "starred2", "starred", "playlists", "playlist",
            "directory", "lyrics", "bookmarks", "playQueue", "user", "users", "scanStatus",
            "error"
    })
    @JsonRootName("subsonic-response")
    @JacksonXmlRootElement(localName = "subsonic-response")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Envelope {
        @JacksonXmlProperty(isAttribute = true)
        public String status;
        @JacksonXmlProperty(isAttribute = true)
        public String version;
        @JacksonXmlProperty(isAttribute = true)
        public String type;
        @JacksonXmlProperty(isAttribute = true)
        public String serverVersion;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean openSubsonic;

        public License license;
        public MusicFolders musicFolders;
        public Indexes indexes;
        public Artists artists;
        public Artist artist;
        public Album album;
        public Child song;
        public Songs randomSongs;
        public Songs songsByGenre;
        public AlbumList albumList;
        public SearchResult searchResult2;
        public SearchResult searchResult3;
        public Songs topSongs;
        public Songs similarSongs;
        public Directory directory;
        public Genres genres;
        public NowPlaying nowPlaying;
        public Starred starred2;
        public Starred starred;
        public Playlists playlists;
        public Playlist playlist;
        public Lyrics lyrics;
        public Bookmarks bookmarks;
        public PlayQueue playQueue;
        public User user;
        public Users users;
        public ScanStatus scanStatus;
        public Error error;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Error {
        @JacksonXmlProperty(isAttribute = true)
        public int code;
        @JacksonXmlProperty(isAttribute = true)
        public String message;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class License {
        @JacksonXmlProperty(isAttribute = true)
        public boolean valid;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MusicFolder {
        @JacksonXmlProperty(isAttribute = true)
        public String id;
        @JacksonXmlProperty(isAttribute = true)
        public String name;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MusicFolders {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<MusicFolder> musicFolder;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Index {
        @JacksonXmlProperty(isAttribute = true)
        public String name;
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Artist> artist;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Indexes {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Index> index;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Artists {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Index> index;
    }

    /** 歌手（ID3 浏览 getArtists/getArtist 用）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Artist {
        @JacksonXmlProperty(isAttribute = true)
        public String id;
        @JacksonXmlProperty(isAttribute = true)
        public String name;
        @JacksonXmlProperty(isAttribute = true)
        public String coverArt;
        @JacksonXmlProperty(isAttribute = true)
        public Integer albumCount;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean starred;
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Album> album;
    }

    /** 专辑（getAlbum/getArtist 内嵌 album 列表/getAlbumList 用）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Album {
        @JacksonXmlProperty(isAttribute = true)
        public String id;
        @JacksonXmlProperty(isAttribute = true)
        public String name;
        @JacksonXmlProperty(isAttribute = true)
        public String artist;
        @JacksonXmlProperty(isAttribute = true)
        public String artistId;
        @JacksonXmlProperty(isAttribute = true)
        public String coverArt;
        @JacksonXmlProperty(isAttribute = true)
        public Integer songCount;
        @JacksonXmlProperty(isAttribute = true)
        public Integer duration;
        @JacksonXmlProperty(isAttribute = true)
        public Integer year;
        @JacksonXmlProperty(isAttribute = true)
        public String genre;
        @JacksonXmlProperty(isAttribute = true)
        public Integer playCount;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean starred;
        @JacksonXmlProperty(isAttribute = true)
        public Integer userRating;
        @JacksonXmlProperty(isAttribute = true)
        public Long created;
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Child> song;
    }

    /** 单曲/目录子项（getSong/getMusicDirectory/search/playlist 内嵌 entry 用）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Child {
        @JacksonXmlProperty(isAttribute = true)
        public String id;
        @JacksonXmlProperty(isAttribute = true)
        public String parent;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean isDir;
        @JacksonXmlProperty(isAttribute = true)
        public String title;
        @JacksonXmlProperty(isAttribute = true)
        public String album;
        @JacksonXmlProperty(isAttribute = true)
        public String artist;
        @JacksonXmlProperty(isAttribute = true)
        public Integer track;
        @JacksonXmlProperty(isAttribute = true)
        public Integer discNumber;
        @JacksonXmlProperty(isAttribute = true)
        public Integer year;
        @JacksonXmlProperty(isAttribute = true)
        public String genre;
        @JacksonXmlProperty(isAttribute = true)
        public String coverArt;
        @JacksonXmlProperty(isAttribute = true)
        public Long size;
        @JacksonXmlProperty(isAttribute = true)
        public String contentType;
        @JacksonXmlProperty(isAttribute = true)
        public String suffix;
        @JacksonXmlProperty(isAttribute = true)
        public Integer duration;
        @JacksonXmlProperty(isAttribute = true)
        public Integer bitRate;
        @JacksonXmlProperty(isAttribute = true)
        public Integer playCount;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean starred;
        @JacksonXmlProperty(isAttribute = true)
        public Integer userRating;
        @JacksonXmlProperty(isAttribute = true)
        public Long created;
        @JacksonXmlProperty(isAttribute = true)
        public String artistId;
        @JacksonXmlProperty(isAttribute = true)
        public String albumId;
        @JacksonXmlProperty(isAttribute = true)
        public String path;
        @JacksonXmlProperty(isAttribute = true)
        public String type;
        // 以下为 nowPlaying / bookmark 扩展
        @JacksonXmlProperty(isAttribute = true)
        public String username;
        @JacksonXmlProperty(isAttribute = true)
        public Integer minutesAgo;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Songs {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Child> song;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AlbumList {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Album> album;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SearchResult {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Artist> artist;
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Album> album;
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Child> song;
    }

    /** 目录浏览（getMusicDirectory）：模拟 /Artist/Album 目录树。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Directory {
        @JacksonXmlProperty(isAttribute = true)
        public String id;
        @JacksonXmlProperty(isAttribute = true)
        public String name;
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Child> child;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Genre {
        @JacksonXmlProperty(isAttribute = true)
        public Integer songCount;
        @JacksonXmlProperty(isAttribute = true)
        public Integer albumCount;
        @JacksonXmlText
        public String name;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Genres {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Genre> genre;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NowPlaying {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Child> entry;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Starred {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Artist> artist;
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Album> album;
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Child> song;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Playlist {
        @JacksonXmlProperty(isAttribute = true)
        public String id;
        @JacksonXmlProperty(isAttribute = true)
        public String name;
        @JacksonXmlProperty(isAttribute = true)
        public String comment;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean isPublic;
        @JacksonXmlProperty(isAttribute = true)
        public String owner;
        @JacksonXmlProperty(isAttribute = true)
        public Integer songCount;
        @JacksonXmlProperty(isAttribute = true)
        public Integer duration;
        @JacksonXmlProperty(isAttribute = true)
        public Long created;
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Child> entry;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Playlists {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Playlist> playlist;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Lyrics {
        @JacksonXmlProperty(isAttribute = true)
        public String artist;
        @JacksonXmlProperty(isAttribute = true)
        public String title;
        @JacksonXmlText
        public String value;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Bookmark {
        @JacksonXmlProperty(isAttribute = true)
        public String username;
        @JacksonXmlProperty(isAttribute = true)
        public Integer position;
        @JacksonXmlProperty(isAttribute = true)
        public String comment;
        @JacksonXmlProperty(isAttribute = true)
        public Long created;
        @JacksonXmlProperty(isAttribute = true)
        public Long changed;
        public Child entry;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Bookmarks {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Bookmark> bookmark;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PlayQueue {
        @JacksonXmlProperty(isAttribute = true)
        public String current;
        @JacksonXmlProperty(isAttribute = true)
        public Long position;
        @JacksonXmlProperty(isAttribute = true)
        public String username;
        @JacksonXmlProperty(isAttribute = true)
        public Long changed;
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<Child> entry;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class User {
        @JacksonXmlProperty(isAttribute = true)
        public String username;
        @JacksonXmlProperty(isAttribute = true)
        public String email;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean adminRole;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean streamRole;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean downloadRole;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean coverArtRole;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean commentRole;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean podcastRole;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean shareRole;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean jukeboxRole;
        @JacksonXmlProperty(isAttribute = true)
        public Boolean scrobblingEnabled;
        @JacksonXmlProperty(isAttribute = true)
        public Integer maxBitRate;
        @JacksonXmlProperty(isAttribute = true)
        public String folder;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Users {
        @JacksonXmlElementWrapper(useWrapping = false)
        public List<User> user;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScanStatus {
        @JacksonXmlProperty(isAttribute = true)
        public boolean scanning;
        @JacksonXmlProperty(isAttribute = true)
        public int count;
        @JacksonXmlProperty(isAttribute = true)
        public Long lastScan;
        @JacksonXmlProperty(isAttribute = true)
        public Integer folderCount;
    }
}