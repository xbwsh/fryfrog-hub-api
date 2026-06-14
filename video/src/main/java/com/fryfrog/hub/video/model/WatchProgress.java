package com.fryfrog.hub.video.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "watch_progress", uniqueConstraints = @UniqueConstraint(columnNames = "video_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "观看进度")
public class WatchProgress extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false, unique = true)
    @Schema(description = "关联视频")
    private Video video;

    @Schema(description = "播放位置（秒）", example = "3600.5")
    private Double positionSeconds;

    @Schema(description = "视频总时长（秒）", example = "7200.0")
    private Double durationSeconds;

    @Schema(description = "是否已看完（进度>=90%）", example = "false")
    private Boolean completed = false;
}
