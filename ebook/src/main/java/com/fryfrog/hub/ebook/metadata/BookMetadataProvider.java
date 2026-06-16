package com.fryfrog.hub.ebook.metadata;

import com.fryfrog.hub.ebook.dto.BookSearchResult;

import java.util.List;

public interface BookMetadataProvider {

    String getName();

    List<BookSearchResult> search(String title, String author);

    BookSearchResult getDetail(String id);

    byte[] getCover(BookSearchResult result);

    BookSearchResult findBestMatch(List<BookSearchResult> results, String title, String author);

    default boolean isAvailable() {
        return true;
    }
}