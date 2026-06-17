package com.fryfrog.hub.ebook.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "电子书阅读进度保存请求")
public class EbookReadingProgressRequest {

    @Min(value = 1, message = "currentPage must be >= 1")
    @Schema(description = "当前页码/章节数", example = "15", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer currentPage;

    @Min(value = 1, message = "totalPages must be >= 1")
    @Schema(description = "总页数/章节数", example = "350", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer totalPages;
}
