package com.fryfrog.hub.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "创建用户请求")
public class UserCreateRequest {

    @Schema(description = "登录用户名", example = "zhangsan")
    private String username;

    @Schema(description = "密码（至少 8 位）", example = "secret123")
    private String password;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像 URL")
    private String avatar;

    @Schema(description = "角色: ADMIN/USER，默认 USER", example = "USER")
    private String role;
}