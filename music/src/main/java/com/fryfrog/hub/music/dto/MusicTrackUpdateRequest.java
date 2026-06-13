package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "曲目更新请求")
public class MusicTrackUpdateRequest {

    @Schema(description = "歌曲标题（必填）", example = "多余的解释", required = true)
    @NotBlank(message = "Title is required")
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
    private Integer year;

    @Schema(description = "音乐流派", example = "Pop")
    private String genre;
}
