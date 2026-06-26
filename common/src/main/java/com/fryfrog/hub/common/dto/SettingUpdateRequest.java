package com.fryfrog.hub.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "系统设置更新请求")
public class SettingUpdateRequest {

    @Schema(description = "设置值", example = "your_tmdb_api_key")
    private String value;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
