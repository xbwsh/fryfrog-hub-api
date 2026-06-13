package com.fryfrog.hub.music.model;

import com.fryfrog.hub.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "music_tracks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MusicTrack extends BaseEntity {

    @Column(nullable = false)
    private String title;

    private String artist;

    private String album;

    private String albumArtist;

    private Integer trackNumber;

    private Integer discNumber;

    @Column(name = "\"year\"")
    private Integer year;

    private String genre;

    @Column(unique = true)
    private String filePath;

    @Column(nullable = false)
    private String fileName;

    private Long fileSize;

    private Long durationSeconds;

    private Integer bitrateKbps;

    private String format;

    private String coverArtPath;

    @Column(columnDefinition = "TEXT")
    private String lyrics;
}
