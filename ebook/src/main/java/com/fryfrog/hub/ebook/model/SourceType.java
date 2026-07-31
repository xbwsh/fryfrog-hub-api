package com.fryfrog.hub.ebook.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "电子书来源类型")
public enum SourceType {
    @Schema(description = "本地文件")
    LOCAL,
    
    @Schema(description = "在线书源")
    ONLINE
}
