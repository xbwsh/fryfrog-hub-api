package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "MusicBrainz搜索结果")
public class MusicBrainzResult {

    @Schema(description = "MusicBrainz ID")
    private String id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "艺术家")
    private String artist;

    @Schema(description = "专辑")
    private String album;

    @Schema(description = "专辑艺术家")
    private String albumArtist;

    @Schema(description = "发行年份")
    private Integer year;

    @Schema(description = "流派")
    private String genre;

    @Schema(description = "曲目编号")
    private Integer trackNumber;

    @Schema(description = "碟片编号")
    private Integer discNumber;

    @Schema(description = "时长（秒）")
    private Long duration;

    @Schema(description = "封面图片URL")
    private String coverUrl;

    @Schema(description = "专辑封面URL")
    private String albumCoverUrl;
}