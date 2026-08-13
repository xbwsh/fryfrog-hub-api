package com.fryfrog.hub.video.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "favorites",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "content_type", "content_id"}),
        indexes = @Index(name = "idx_favorites_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "用户收藏")
public class Favorite extends BaseEntity {

    @Schema(description = "所属用户 ID（认证关闭时为匿名档案）")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Schema(description = "内容类型: VIDEO/SERIES", example = "VIDEO")
    @Column(name = "content_type", nullable = false, length = 16)
    private String contentType;

    @Schema(description = "内容 ID")
    @Column(name = "content_id", nullable = false)
    private Long contentId;

    public static final String TYPE_VIDEO = "VIDEO";
    public static final String TYPE_SERIES = "SERIES";
}