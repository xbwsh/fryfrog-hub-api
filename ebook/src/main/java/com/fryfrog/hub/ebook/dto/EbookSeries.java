package com.fryfrog.hub.ebook.dto;

import com.fryfrog.hub.ebook.model.Ebook;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "电子书系列信息")
public class EbookSeries {

    @Schema(description = "系列名称")
    private String name;

    @Schema(description = "作者")
    private String author;

    @Schema(description = "封面图片路径")
    private String coverArtPath;

    @Schema(description = "卷数")
    private Integer volumeCount;

    @Schema(description = "该系列下的所有电子书")
    private List<Ebook> books;
}
