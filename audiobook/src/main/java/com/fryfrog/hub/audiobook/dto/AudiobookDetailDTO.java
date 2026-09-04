package com.fryfrog.hub.audiobook.dto;

import com.fryfrog.hub.audiobook.model.Audiobook;
import com.fryfrog.hub.audiobook.model.AudiobookChapter;
import com.fryfrog.hub.audiobook.model.AudiobookProgress;
import com.fryfrog.hub.audiobook.model.AudiobookTrack;
import com.fryfrog.hub.common.util.MediaUrlSigner;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@Schema(description = "有声书详情")
public class AudiobookDetailDTO {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "朗读者")
    private String narrator;

    @Schema(description = "简介")
    private String overview;

    @Schema(description = "元数据来源: scan/manual/scrape")
    private String metadataSource;

    @Schema(description = "丛书名")
    private String series;

    @Schema(description = "丛书序号")
    private Integer seriesPart;

    @Schema(description = "播放模式: SINGLE=单文件（chapters 为内嵌章节）, MULTI=多文件（tracks 即章节）")
    private String playType;

    @Schema(description = "封面 URL（签名）")
    private String coverUrl;

    @Schema(description = "总时长（秒）")
    private Double totalDurationSeconds;

    @Schema(description = "音轨数")
    private Integer trackCount;

    @Schema(description = "音轨列表（SINGLE 模式只有一轨）")
    private List<TrackDTO> tracks;

    @Schema(description = "章节列表（SINGLE 模式内嵌章节；MULTI 模式由 tracks 计算，全局时间轴）")
    private List<ChapterDTO> chapters;

    @Schema(description = "收听进度（未开播为 null）")
    private ProgressDTO progress;

    @Data
    @Builder
    @Schema(description = "音轨")
    public static class TrackDTO {
        @Schema(description = "音轨 ID（流播放用）")
        private Long id;
        @Schema(description = "播放顺序（0 起）")
        private Integer trackIndex;
        @Schema(description = "标题")
        private String title;
        @Schema(description = "时长（秒）")
        private Double durationSeconds;
        @Schema(description = "流播放 URL（签名）")
        private String streamUrl;
        @Schema(description = "文件大小（字节）")
        private Long fileSize;

        public static TrackDTO from(AudiobookTrack track) {
            return TrackDTO.builder()
                    .id(track.getId())
                    .trackIndex(track.getTrackIndex())
                    .title(track.getTitle())
                    .durationSeconds(track.getDurationSeconds())
                    .fileSize(track.getFileSize())
                    .streamUrl(MediaUrlSigner.sign("/api/v1/audiobooks/tracks/" + track.getId() + "/stream"))
                    .build();
        }
    }

    @Data
    @Builder
    @Schema(description = "章节（全局时间轴）")
    public static class ChapterDTO {
        @Schema(description = "章节顺序")
        private Integer chapterIndex;
        @Schema(description = "标题")
        private String title;
        @Schema(description = "全局起始时间（秒）")
        private Double startSeconds;
        @Schema(description = "全局结束时间（秒）")
        private Double endSeconds;
        @Schema(description = "所在音轨索引")
        private Integer trackIndex;
        @Schema(description = "轨内起始时间（秒）")
        private Double startInTrack;
    }

    @Data
    @Builder
    @Schema(description = "收听进度")
    public static class ProgressDTO {
        @Schema(description = "当前音轨索引")
        private Integer trackIndex;
        @Schema(description = "轨内位置（秒）")
        private Double positionSeconds;
        @Schema(description = "是否听完")
        private Boolean completed;
        @Schema(description = "进度百分比（0-100）")
        private Double percent;

        public static ProgressDTO from(AudiobookProgress progress, Audiobook book,
                                       double globalSeconds) {
            double percent = book.getTotalDurationSeconds() != null && book.getTotalDurationSeconds() > 0
                    ? Math.min(100.0, globalSeconds / book.getTotalDurationSeconds() * 100) : 0;
            return ProgressDTO.builder()
                    .trackIndex(progress.getTrackIndex())
                    .positionSeconds(progress.getPositionSeconds())
                    .completed(progress.getCompleted())
                    .percent(percent)
                    .build();
        }
    }
}
