package com.fryfrog.hub.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "修改密码请求")
public class ChangePasswordRequest {

    @Schema(description = "原密码")
    private String oldPassword;

    @Schema(description = "新密码（至少 8 位）")
    private String newPassword;
}