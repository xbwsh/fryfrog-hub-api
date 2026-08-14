package com.fryfrog.hub.common.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "auth_tokens",
        indexes = {
                @Index(name = "idx_auth_tokens_user", columnList = "user_id"),
                @Index(name = "idx_auth_tokens_expires", columnList = "expires_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "登录令牌（DB 持久化，重启不失效）")
public class AuthToken extends BaseEntity {

    @Schema(description = "令牌值")
    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @Schema(description = "用户 ID")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Schema(description = "过期时间")
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}