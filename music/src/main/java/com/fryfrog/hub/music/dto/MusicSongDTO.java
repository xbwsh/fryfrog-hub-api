package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "音乐单曲")
public class MusicSongDTO {

    private Long id;
    private String title;
    private String artistName;
    private String albumName;
    private Long artistId;
    private Long albumId;
    private Integer trackNumber;
    private Integer discNumber;
    private Double durationSeconds;
    private String format;
    private Integer bitRate;
    private String genre;
    private Integer year;
    private Long fileSize;
    private String streamUrl;
    private String coverUrl;
    private String lyricsUrl;
    private boolean starred;
    private Integer rating;
    private int playCount;
}