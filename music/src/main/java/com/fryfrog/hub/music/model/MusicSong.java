package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "music_songs", indexes = {
    @Index(name = "idx_music_song_title", columnList = "title"),
    @Index(name = "idx_music_song_album", columnList = "album_id"),
    @Index(name = "idx_music_song_artist", columnList = "artist_id"),
    @Index(name = "idx_music_song_library", columnList = "library_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "音乐单曲")
public class MusicSong extends BaseEntity {

    @Schema(description = "歌曲名", example = "七里香")
    @Column(nullable = false)
    private String title;

    @Schema(description = "歌手名（冗余，便于查询）", example = "周杰伦")
    private String artistName;

    @Schema(description = "专辑名（冗余，便于查询）", example = "七里香")
    private String albumName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id")
    @Schema(description = "所属专辑")
    private MusicAlbum album;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artist_id")
    @Schema(description = "歌手")
    private MusicArtist artist;

    @Schema(description = "音轨号", example = "1")
    private Integer trackNumber;

    @Schema(description = "唱片号", example = "1")
    private Integer discNumber;

    @Schema(description = "时长（秒）", example = "247.0")
    private Double durationSeconds;

    @Schema(description = "文件完整路径")
    @Column(unique = true)
    private String filePath;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "容器格式", example = "FLAC")
    private String format;

    @Schema(description = "码率（kbps）", example = "950")
    private Integer bitRate;

    @Schema(description = "采样率（Hz）", example = "44100")
    private Integer sampleRate;

    @Schema(description = "流派", example = "Pop")
    private String genre;

    @Schema(description = "发行年份", example = "2004")
    private Integer year;

    @Schema(description = "歌词本地路径（.lrc）")
    private String lyricsPath;

    @Schema(description = "所属资源库ID", example = "1")
    @Column(name = "library_id")
    private Long libraryId;
}
