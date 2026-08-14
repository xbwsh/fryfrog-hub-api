package com.fryfrog.hub.common.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_preferences",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "pref_key"}),
        indexes = @Index(name = "idx_user_preferences_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "用户偏好设置（按账号存储，多端同步）")
public class UserPreference extends BaseEntity {

    @Schema(description = "用户 ID")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Schema(description = "偏好键", example = "theme.mode")
    @Column(name = "pref_key", nullable = false, length = 64)
    private String prefKey;

    @Schema(description = "偏好值（JSON 字符串或普通文本）", example = "dark")
    @Column(name = "pref_value", length = 2048)
    private String prefValue;
}