package com.fryfrog.hub.music.metadata;

import com.fryfrog.hub.music.dto.SearchResult;

import java.util.List;

public interface MusicMetadataProvider {

    String getName();

    List<SearchResult> search(String artist, String title, String album);

    String getLyrics(SearchResult result);

    byte[] getCover(SearchResult result);

    SearchResult findBestMatch(List<SearchResult> results, String artist, String title);

    default boolean isAvailable() {
        return true;
    }
}
