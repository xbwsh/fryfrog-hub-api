package com.fryfrog.hub.video.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "watch_progress",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "video_id"}),
        indexes = @Index(name = "idx_watch_progress_user", columnList = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "观看进度")
public class WatchProgress extends BaseEntity {

    @Schema(description = "所属用户 ID（认证关闭时为匿名档案；升级前旧数据可能为空，由迁移补全）")
    @Column(name = "user_id")
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false)
    @Schema(description = "关联视频")
    private Video video;

    @Schema(description = "播放位置（秒）", example = "3600.5")
    private Double positionSeconds;

    @Schema(description = "视频总时长（秒）", example = "7200.0")
    private Double durationSeconds;

    @Schema(description = "是否已看完（进度>=95%）", example = "false")
    private Boolean completed = false;
}
