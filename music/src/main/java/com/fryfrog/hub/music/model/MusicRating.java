package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "music_ratings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "target_type", "target_id"}),
        indexes = @Index(name = "idx_music_ratings_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "音乐评分")
public class MusicRating extends BaseEntity {

    @Schema(description = "所属用户 ID（认证关闭时为匿名档案）")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Schema(description = "目标类型: SONG/ALBUM/ARTIST", example = "SONG")
    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    @Schema(description = "目标 ID")
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Schema(description = "评分 1-5，0 表示清除", example = "5")
    @Column(nullable = false)
    private Integer rating;
}
