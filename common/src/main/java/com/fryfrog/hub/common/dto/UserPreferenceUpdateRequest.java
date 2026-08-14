package com.fryfrog.hub.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
@Schema(description = "更新用户偏好请求（全量替换；value 为空串表示删除该键）")
public class UserPreferenceUpdateRequest {

    @Schema(description = "偏好键值对", example = "{\"theme.mode\":\"dark\",\"privacy.enabled\":\"true\"}")
    private Map<String, String> preferences;
}