package com.fryfrog.hub.video.dto;

import com.fryfrog.hub.video.model.WatchProgress;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "观看进度信息")
public class WatchProgressDTO {

    @Schema(description = "视频ID")
    private Long videoId;

    @Schema(description = "播放位置（秒）", example = "3600.5")
    private Double positionSeconds;

    @Schema(description = "视频总时长（秒）", example = "7200.0")
    private Double durationSeconds;

    @Schema(description = "是否已看完", example = "false")
    private Boolean completed;

    @Schema(description = "观看进度百分比", example = "50.0")
    private Double progressPercent;

    @Schema(description = "最后更新时间")
    private LocalDateTime updatedAt;

    public static WatchProgressDTO fromEntity(WatchProgress progress) {
        WatchProgressDTO dto = new WatchProgressDTO();
        dto.setVideoId(progress.getVideo().getId());
        dto.setPositionSeconds(progress.getPositionSeconds());
        dto.setDurationSeconds(progress.getDurationSeconds());
        dto.setCompleted(progress.getCompleted());
        dto.setUpdatedAt(progress.getUpdatedAt());

        if (progress.getDurationSeconds() != null && progress.getDurationSeconds() > 0) {
            dto.setProgressPercent(progress.getPositionSeconds() / progress.getDurationSeconds() * 100);
        } else {
            dto.setProgressPercent(0.0);
        }

        return dto;
    }
}
