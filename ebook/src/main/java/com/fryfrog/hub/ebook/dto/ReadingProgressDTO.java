package com.fryfrog.hub.ebook.dto;

import com.fryfrog.hub.ebook.model.ReadingProgress;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "电子书阅读进度信息")
public class ReadingProgressDTO {

    @Schema(description = "电子书ID")
    private Long ebookId;

    @Schema(description = "当前页码/章节数", example = "15")
    private Integer currentPage;

    @Schema(description = "总页数/章节数", example = "350")
    private Integer totalPages;

    @Schema(description = "是否已读完", example = "false")
    private Boolean completed;

    @Schema(description = "阅读进度百分比", example = "4.3")
    private Double progressPercent;

    @Schema(description = "最后更新时间")
    private LocalDateTime updatedAt;

    public static ReadingProgressDTO fromEntity(ReadingProgress progress) {
        ReadingProgressDTO dto = new ReadingProgressDTO();
        dto.setEbookId(progress.getEbook().getId());
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
