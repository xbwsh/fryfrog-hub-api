package com.fryfrog.hub.audiobook.dto;

import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.model.AudiobookProgress;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "有声书列表条目（轻量）")
public class AudiobookListDTO {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "朗读者")
    private String narrator;

    @Schema(description = "丛书名")
    private String series;

    @Schema(description = "封面 URL（签名，可能为 null，前端用占位图）")
    private String coverUrl;

    @Schema(description = "播放模式: SINGLE/MULTI")
    private String playType;

    @Schema(description = "总时长（秒）")
    private Double totalDurationSeconds;

    @Schema(description = "音轨数")
    private Integer trackCount;

    @Schema(description = "是否听完")
    private Boolean completed;

    @Schema(description = "收听进度百分比（0-100，未开播为 null）")
    private Double progressPercent;

    public static AudiobookListDTO from(Audiobook book, AudiobookProgress progress) {
        AudiobookListDTOBuilder builder = AudiobookListDTO.builder()
                .id(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .narrator(book.getNarrator())
                .series(book.getSeries())
                .coverUrl(book.getCoverUrl())
                .playType(book.getPlayType())
                .totalDurationSeconds(book.getTotalDurationSeconds())
                .trackCount(book.getTrackCount());
        if (progress != null) {
            builder.completed(progress.getCompleted());
            if (progress.getPositionSeconds() != null && book.getTotalDurationSeconds() != null
                    && book.getTotalDurationSeconds() > 0) {
                double done = 0;
                if (book.getTrackCount() != null && book.getTrackCount() > 0 && progress.getTrackIndex() != null) {
                    done = (double) progress.getTrackIndex() / book.getTrackCount()
                            * book.getTotalDurationSeconds();
                }
                builder.progressPercent(Math.min(100.0,
                        (done + progress.getPositionSeconds()) / book.getTotalDurationSeconds() * 100));
            }
        } else {
            builder.completed(false);
        }
        return builder.build();
    }
}
