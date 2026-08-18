package com.fryfrog.hub.music.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "更新播放列表请求")
public class MusicPlaylistUpdateRequest {

    private String name;

    private String comment;

    private Boolean isPublic;

    @Schema(description = "追加的曲目 ID")
    private List<Long> songIdsToAdd;

    @Schema(description = "按位置删除的曲目下标（0-based）")
    private List<Integer> songIndexesToRemove;
}