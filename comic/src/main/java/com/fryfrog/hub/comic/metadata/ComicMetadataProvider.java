package com.fryfrog.hub.comic.metadata;

import com.fryfrog.hub.comic.dto.ComicSearchResult;

import java.util.List;

public interface ComicMetadataProvider {

    boolean isAvailable();

    String getName();

    List<ComicSearchResult> search(String title, String author);

    byte[] getCover(ComicSearchResult result);

    ComicSearchResult findBestMatch(List<ComicSearchResult> results, String title, String author);
}
