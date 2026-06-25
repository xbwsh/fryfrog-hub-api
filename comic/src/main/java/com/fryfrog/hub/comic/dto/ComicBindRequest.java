package com.fryfrog.hub.comic.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "漫画元数据绑定请求")
public class ComicBindRequest {

    @Schema(description = "Bangumi 条目 ID")
    private Integer bangumiId;

    @Schema(description = "AniList 漫画 ID")
    private Integer anilistId;

    @Schema(description = "是否同步到同系列所有卷", example = "true", defaultValue = "true")
    private boolean bindSeries = true;
}
