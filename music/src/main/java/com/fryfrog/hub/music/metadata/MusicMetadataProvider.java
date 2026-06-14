package com.fryfrog.hub.music.metadata;

import com.fryfrog.hub.music.dto.MusicBrainzResult;
import com.fryfrog.hub.music.model.MusicTrack;

public interface MusicMetadataProvider {

    String getName();

    int getPriority();

    MusicBrainzResult scrape(String title, String artist, String album);

    default boolean isAvailable() {
        return true;
    }
}