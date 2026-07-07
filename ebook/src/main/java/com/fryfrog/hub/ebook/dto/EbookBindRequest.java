package com.fryfrog.hub.ebook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "电子书元数据绑定请求")
public class EbookBindRequest {

    @Schema(description = "Bangumi 条目 ID")
    private Integer bangumiId;

    @Schema(description = "是否同步到同系列所有卷", example = "true", defaultValue = "true")
    private boolean bindSeries = true;
}
