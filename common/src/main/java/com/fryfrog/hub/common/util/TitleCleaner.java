package com.fryfrog.hub.common.util;

import java.util.regex.Pattern;

public final class TitleCleaner {

    private TitleCleaner() {}

    private static final String DOTTED_QUALITY =
            "(?i)\\bH\\.?264\\b|\\bH\\.?265\\b|\\bDD[P+]\\s*\\.?\\s*\\d+(?:\\.\\d+)*\\b" +
            "|\\bDTS(?:\\s*-?\\s*HD)?(?:\\s*\\.?\\s*(?:MA|ES|RA))?(?:\\s*\\.?\\s*\\d+(?:\\.\\d+)*)?\\b" +
            "|\\bAC\\s*\\.?\\s*3\\b|\\bE\\s*-?\\s*AC\\s*-?\\s*3\\b" +
            "|\\bMPEG\\s*-?\\s*[24]\\b" +
            "|\\bFLAC\\s*\\.?\\s*\\d+(?:\\.\\d+)*\\b" +
            "|\\b[2-7]\\.\\d\\b";

    private static final String QUALITY_PATTERN =
            "(?i)\\b(?:2160p|1080p|720p|480p|4K|UHD|FHD)\\b" +
            "|\\b(?:BluRay|BDRip|BRRip|WEB-?DL|WEB-?Rip|HDRip|DVDRip|HDTV|TVRip|CamRip|TS|TC|SCR|R5)\\b" +
            "|\\b(?:x264|x265|HEVC|AVC|AV1)\\b" +
            "|\\b(?:AAC|FLAC|DTS(?:MA|HD)?|AC3|EAC3|DDP?\\d|Atmos|TrueHD|MP3|OGG|Opus)\\b" +
            "|\\bHDR(?:10?)?\\b|\\bDoVi\\b|\\bDV\\b|\\bHLG\\b" +
            "|\\bDolby\\s*Vision\\b|\\bDolby\\b" +
            "|\\b(?:10-?bit|8-?bit)\\b" +
            "|\\bREMUX\\b|\\bBlu-?ray\\b" +
            "|\\b(?:AVS|FRDS|Ma10p|Ma10s|NCOP|NCED)\\b" +
            "|\\b(?:Baha|ADBA|Bilibili|ABEMA|Crunchyroll|Funimation|Netflix|NF|Disney\\+?|Amazon|Hulu|Hi10|Nazzy|FGT|SPARKS|SHAFT)\\b" +
            "|\\b(?:ASS|SSA|SRT|BIG5|GB2312|UTF-?8|EUC-?JP|Shift-?JIS)\\b" +
            "|\\b(?:PART|CHAPTER|CD|DVD|BD|DISC?)\\b" +
            "|\\b(?:19|20)\\d{2}\\b|\\d*Audio\\b" +
            "|\\b(?:mp4|mkv|avi|mov|flv|wmv|webm|m4v|ts)\\b";

    private static final Pattern DOTTED_QUALITY_PATTERN = Pattern.compile(DOTTED_QUALITY);
    private static final Pattern QUALITY_COMPILE_PATTERN = Pattern.compile(QUALITY_PATTERN);

    public static String cleanForSearch(String title) {
        if (title == null || title.isBlank()) {
            return title;
        }

        String cleaned = title;

        cleaned = cleaned.replaceAll("\\[.*?\\]", " ");
        cleaned = cleaned.replaceAll("【.*?】", " ");
        cleaned = cleaned.replaceAll("\\(.*?\\)", " ");
        cleaned = cleaned.replaceAll("（.*?）", " ");

        cleaned = DOTTED_QUALITY_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replaceAll("[._]", " ");

        cleaned = cleaned.replaceAll("(?i)\\bS\\d{1,2}\\s*E\\d{1,4}\\b", " ");
        cleaned = QUALITY_COMPILE_PATTERN.matcher(cleaned).replaceAll(" ");

        cleaned = cleaned.replaceAll("(?i)第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*(?:季|部|期)", " ");
        cleaned = cleaned.replaceAll("(?i)第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*(?:话|集|回|篇|章)", " ");

        cleaned = cleaned.replaceAll("(?i)\\bSeason\\s*\\d+\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\bS\\d{1,2}\\b", " ");

        cleaned = cleaned.replaceAll("(?i)\\bE(?:p(?:isode)?)?\\s*\\d{1,4}\\b", " ");
        cleaned = cleaned.replaceAll("[＃#]\\s*\\d{1,4}\\b", " ");

        cleaned = cleaned.replaceAll("\\s*[-–—]\\s*\\d{1,4}\\s*$", "");
        cleaned = cleaned.replaceAll("\\s+\\d{1,4}\\s*$", "");
        cleaned = cleaned.replaceAll("^\\d{1,4}\\s*[-–—]\\s*", "");
        cleaned = cleaned.replaceAll("\\s*[-–—]\\s*$", "");
        cleaned = cleaned.replaceAll("[,;]+\\s*$", "");

        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned.isBlank() ? title : cleaned;
    }

    public static String clean(String title) {
        if (title == null || title.isBlank()) {
            return title;
        }

        String cleaned = title;

        cleaned = cleaned.replaceAll("\\[.*?\\]", " ");
        cleaned = cleaned.replaceAll("【.*?】", " ");
        cleaned = cleaned.replaceAll("\\(.*?\\)", " ");
        cleaned = cleaned.replaceAll("（.*?）", " ");

        cleaned = DOTTED_QUALITY_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replaceAll("[._]", " ");

        cleaned = cleaned.replaceAll("(?i)\\bS\\d{1,2}\\s*E\\d{1,4}\\b", " ");
        cleaned = QUALITY_COMPILE_PATTERN.matcher(cleaned).replaceAll(" ");

        cleaned = cleaned.replaceAll("(?i)第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*(?:季|部|期)", " ");
        cleaned = cleaned.replaceAll("(?i)第[\\s]*[一二三四五六七八九十百千万零\\d]+[\\s]*(?:话|集|回|篇|章)", " ");

        cleaned = cleaned.replaceAll("(?i)\\bSeason\\s*\\d+\\b", " ");
        cleaned = cleaned.replaceAll("(?i)\\bS\\d{1,2}\\b", " ");

        cleaned = cleaned.replaceAll("(?i)\\bE(?:p(?:isode)?)?\\s*\\d{1,4}\\b", " ");
        cleaned = cleaned.replaceAll("[＃#]\\s*\\d{1,4}\\b", " ");

        cleaned = cleaned.replaceAll("\\s*[-–—]\\s*\\d{1,4}\\s*$", "");
        cleaned = cleaned.replaceAll("\\s+\\d{1,4}\\s*$", "");
        cleaned = cleaned.replaceAll("^\\d{1,4}\\s*[-–—]\\s*", "");
        cleaned = cleaned.replaceAll("\\s*[-–—]\\s*$", "");
        cleaned = cleaned.replaceAll("[,;]+\\s*$", "");

        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned.isBlank() ? title : cleaned;
    }
}
