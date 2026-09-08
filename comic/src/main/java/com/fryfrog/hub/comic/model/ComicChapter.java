package com.fryfrog.hub.comic.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "comic_chapters", indexes = {
    @Index(name = "idx_comic_chapter_comic", columnList = "comic_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "漫画卷/话")
public class ComicChapter extends BaseEntity {

    public static final String TYPE_DIRECTORY = "DIRECTORY";
    public static final String TYPE_ARCHIVE = "ARCHIVE";

    @Schema(description = "所属漫画")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comic_id", nullable = false)
    private Comic comic;

    @Schema(description = "卷/话序号（自然序，从 0 开始）", example = "0")
    @Column(name = "chapter_index", nullable = false)
    private Integer chapterIndex;

    @Schema(description = "卷/话名", example = "第01卷")
    private String title;

    @Schema(description = "章节路径（目录或压缩包文件，comic 内唯一）")
    @Column(nullable = false)
    private String filePath;

    @Schema(description = "路径修改时间（epoch 毫秒，增量扫描用）")
    private Long fileMtime;

    @Schema(description = "类型: DIRECTORY=图片目录, ARCHIVE=cbz/zip", example = "DIRECTORY")
    @Column(nullable = false, length = 16)
    private String type;

    @Schema(description = "页数", example = "196")
    private Integer pageCount;

    @Schema(description = "文件大小（字节，目录为合计）")
    private Long fileSize;
}
