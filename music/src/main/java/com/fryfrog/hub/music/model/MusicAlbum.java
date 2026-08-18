package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "music_albums", indexes = {
    @Index(name = "idx_music_album_title", columnList = "title"),
    @Index(name = "idx_music_album_artist", columnList = "artist_id"),
    @Index(name = "idx_music_album_library", columnList = "library_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "音乐专辑")
public class MusicAlbum extends BaseEntity {

    @Schema(description = "专辑名", example = "七里香")
    @Column(nullable = false)
    private String title;

    @Schema(description = "歌手名（冗余，便于查询）", example = "周杰伦")
    private String artistName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artist_id")
    @Schema(description = "歌手")
    private MusicArtist artist;

    @Schema(description = "发行年份", example = "2004")
    private Integer year;

    @Schema(description = "流派", example = "Pop")
    private String genre;

    @Schema(description = "封面本地路径（cover.jpg）")
    private String coverArtPath;

    @Schema(description = "曲目数", example = "10")
    private Integer trackCount;

    @Schema(description = "所属资源库ID", example = "1")
    @Column(name = "library_id")
    private Long libraryId;
}
