package com.fryfrog.hub.comic.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "漫画页面信息")
public class PageInfo {

    @Schema(description = "页码（从1开始）")
    private int pageNum;

    @Schema(description = "文件名")
    private String fileName;
}
