package com.fryfrog.hub.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "设置用户可访问媒体库请求")
public class UserLibraryUpdateRequest {

    @Schema(description = "允许用户访问的媒体库 ID 列表", example = "[1,2]")
    private List<Long> libraryIds;
}