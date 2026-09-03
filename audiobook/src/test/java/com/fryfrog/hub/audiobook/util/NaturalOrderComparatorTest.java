package com.fryfrog.hub.audiobook.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NaturalOrderComparatorTest {

    private final NaturalOrderComparator comparator = NaturalOrderComparator.INSTANCE;

    @Test
    void sortsNumericallyNotLexicographically() {
        List<String> names = List.of("10.mp3", "2.mp3", "1.mp3");
        List<String> sorted = names.stream().sorted(comparator).toList();
        assertThat(sorted).containsExactly("1.mp3", "2.mp3", "10.mp3");
    }

    @Test
    void handlesChapterPrefixes() {
        List<String> names = List.of("Chapter 12.mp3", "Chapter 2.mp3", "Chapter 1.mp3");
        List<String> sorted = names.stream().sorted(comparator).toList();
        assertThat(sorted).containsExactly("Chapter 1.mp3", "Chapter 2.mp3", "Chapter 12.mp3");
    }

    @Test
    void leadingZeroFilesSortBeforePlain() {
        assertThat(comparator.compare("01.mp3", "1.mp3")).isNegative();
        assertThat(comparator.compare("1.mp3", "01.mp3")).isPositive();
        assertThat(comparator.compare("01.mp3", "02.mp3")).isNegative();
    }

    @Test
    void mixedDigitsAndText() {
        List<String> names = List.of("disc2-10.mp3", "disc2-2.mp3", "disc1-1.mp3");
        List<String> sorted = names.stream().sorted(comparator).toList();
        assertThat(sorted).containsExactly("disc1-1.mp3", "disc2-2.mp3", "disc2-10.mp3");
    }

    @Test
    void identicalStringsAreEqual() {
        assertThat(comparator.compare("abc.mp3", "abc.mp3")).isZero();
    }
}
