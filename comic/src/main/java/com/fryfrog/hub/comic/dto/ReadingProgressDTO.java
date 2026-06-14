package com.fryfrog.hub.comic.dto;

import com.fryfrog.hub.comic.model.ReadingProgress;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "漫画阅读进度信息")
public class ReadingProgressDTO {

    @Schema(description = "漫画ID")
    private Long comicId;

    @Schema(description = "当前页码（从1开始）", example = "42")
    private Integer currentPage;

    @Schema(description = "总页数", example = "196")
    private Integer totalPages;

    @Schema(description = "是否已读完", example = "false")
    private Boolean completed;

    @Schema(description = "阅读进度百分比", example = "21.4")
    private Double progressPercent;

    @Schema(description = "最后更新时间")
    private LocalDateTime updatedAt;

    public static ReadingProgressDTO fromEntity(ReadingProgress progress) {
        ReadingProgressDTO dto = new ReadingProgressDTO();
        dto.setComicId(progress.getComic().getId());
        dto.setCurrentPage(progress.getCurrentPage());
        dto.setTotalPages(progress.getTotalPages());
        dto.setCompleted(progress.getCompleted());
        dto.setUpdatedAt(progress.getUpdatedAt());

        if (progress.getTotalPages() != null && progress.getTotalPages() > 0) {
            dto.setProgressPercent((double) progress.getCurrentPage() / progress.getTotalPages() * 100);
        } else {
            dto.setProgressPercent(0.0);
        }

        return dto;
    }
}
