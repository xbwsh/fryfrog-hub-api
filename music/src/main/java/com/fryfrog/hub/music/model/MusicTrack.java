package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "music_tracks", indexes = {
    @Index(name = "idx_music_title", columnList = "title"),
    @Index(name = "idx_music_artist", columnList = "artist"),
    @Index(name = "idx_music_favorite", columnList = "favorite"),
    @Index(name = "idx_music_file_path", columnList = "filePath")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "音乐曲目信息")
public class MusicTrack extends BaseEntity {

    @Schema(description = "歌曲标题", example = "多余的解释")
    @Column(nullable = false)
    private String title;

    @Schema(description = "艺术家", example = "许嵩")
    private String artist;

    @Schema(description = "专辑名称", example = "自定义")
    private String album;

    @Schema(description = "专辑艺术家")
    private String albumArtist;

    @Schema(description = "曲目编号", example = "1")
    private Integer trackNumber;

    @Schema(description = "碟片编号", example = "1")
    private Integer discNumber;

    @Schema(description = "发行年份", example = "2009")
    @Column(name = "\"year\"")
    private Integer year;

    @Schema(description = "音乐流派", example = "Pop")
    private String genre;

    @Schema(description = "文件完整路径")
    @Column(unique = true)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String filePath;

    @Schema(description = "文件名", example = "许嵩 - 多余的解释.flac")
    @Column(nullable = false)
    private String fileName;

    @Schema(description = "文件大小（字节）", example = "31749753")
    private Long fileSize;

    @Schema(description = "时长（秒）", example = "277")
    private Long durationSeconds;

    @Schema(description = "比特率（kbps）", example = "912")
    private Integer bitrateKbps;

    @Schema(description = "音频格式", example = "FLAC 16 bits")
    private String format;

    @Schema(description = "封面图片缓存路径")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String coverArtPath;

    @Schema(description = "封面来源", example = "embedded")
    private String coverSource;

    @Schema(description = "歌词文本")
    @Column(columnDefinition = "TEXT")
    private String lyrics;

    @Schema(description = "歌词来源", example = "embedded")
    private String lyricsSource;

    @Schema(description = "唱片公司", example = "Gold Typhoon")
    private String label;

    @Schema(description = "目录编号")
    private String catalogNumber;

    @Schema(description = "发行日期", example = "2009-01-01")
    private String releaseDate;

    @Schema(description = "MusicBrainz Recording ID")
    private String musicBrainzId;

    @Schema(description = "艺术家图片URL")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String artistImage;

    @Schema(description = "艺术家简介")
    @Column(columnDefinition = "TEXT")
    private String artistBio;

    @Schema(description = "刮削状态", example = "scraped")
    @Column(name = "scrape_status")
    private String scrapeStatus;

    @Schema(description = "是否收藏", example = "false")
    private Boolean favorite = false;

    @Schema(description = "播放次数", example = "0")
    private Integer playCount = 0;

    @Schema(description = "最后播放时间")
    private java.time.LocalDateTime lastPlayedAt;

    @Schema(description = "封面图片API路径", example = "/api/v1/music/1/cover")
    @jakarta.persistence.Transient
    private String coverApiPath;

    @Schema(description = "歌手图片API路径", example = "/api/v1/music/1/artist-image")
    @jakarta.persistence.Transient
    private String artistImageApiPath;

    @Schema(description = "音频播放API路径", example = "/api/v1/music/1/stream")
    @jakarta.persistence.Transient
    private String streamApiPath;
}
