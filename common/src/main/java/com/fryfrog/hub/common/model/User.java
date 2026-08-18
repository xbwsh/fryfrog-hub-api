package com.fryfrog.hub.common.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_username", columnList = "username", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "系统用户")
public class User extends BaseEntity {

    @Schema(description = "登录用户名", example = "admin")
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    /**
     * Subsonic 协议认证用明文密码副本（Navidrome 同款做法：Subsonic 的
     * token 认证 t=md5(明文+salt) 无法用 BCrypt 反推，故在密码变更时同步维护一份）。
     * 仅用于 /rest/* 认证，绝不通过任何 API 返回；需安全存储时可用配置密钥加密。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String subsonicPassword;

    @Schema(description = "昵称", example = "管理员")
    private String nickname;

    @Schema(description = "头像 URL")
    private String avatar;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Schema(description = "是否启用", example = "true")
    @Column(nullable = false)
    private Boolean enabled = true;

    @Schema(description = "最近登录时间")
    private LocalDateTime lastLoginAt;

    @Schema(description = "最近登录 IP")
    private String lastLoginIp;

    public enum Role {
        ADMIN, USER
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}