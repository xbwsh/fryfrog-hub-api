package com.fryfrog.hub.music.service;

import com.fryfrog.hub.music.model.MusicSong;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;

/**
 * 音频流式输出：支持 HTTP Range 请求（浏览器/播放器拖拽定位）。
 */
@Service
public class MusicStreamService {

    public static final String DEFAULT_CONTENT_TYPE = "audio/mpeg";

    public void stream(HttpServletResponse response, MusicSong song, String rangeHeader, boolean asDownload) throws Exception {
        File file = new File(song.getFilePath());
        if (!file.exists()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String contentType = mimeType(song.getFormat());
        long fileLength = file.length();

        if (asDownload) {
            response.setContentType(contentType);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + sanitizeFileName(song.getTitle()) + "." + ext(song.getFormat()) + "\"");
        }

        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            response.setContentType(contentType);
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setContentLengthLong(fileLength);
            try (var is = new FileInputStream(file); var os = response.getOutputStream()) {
                is.transferTo(os);
            }
            return;
        }

        String[] ranges = rangeHeader.substring(6).split("-");
        long start;
        long end;
        try {
            start = Long.parseLong(ranges[0]);
            end = (ranges.length > 1 && !ranges[1].isEmpty()) ? Long.parseLong(ranges[1]) : fileLength - 1;
        } catch (NumberFormatException e) {
            start = 0;
            end = fileLength - 1;
        }
        start = Math.max(0, start);
        end = Math.min(fileLength - 1, end);
        long contentLength = end - start + 1;

        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setContentType(contentType);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_RANGE, String.format("bytes %d-%d/%d", start, end, fileLength));
        response.setContentLengthLong(contentLength);

        try (var raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            try (var os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                long remaining = contentLength;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = raf.read(buffer, 0, toRead);
                    if (read == -1) break;
                    os.write(buffer, 0, read);
                    remaining -= read;
                }
                os.flush();
            }
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "audio";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private String ext(String format) {
        return format != null && !format.isBlank() ? format.toLowerCase() : "mp3";
    }

    public String mimeType(String format) {
        if (format == null) return DEFAULT_CONTENT_TYPE;
        return switch (format.toLowerCase()) {
            case "flac" -> "audio/flac";
            case "m4a", "mp4", "alac" -> "audio/mp4";
            case "ogg", "oga" -> "audio/ogg";
            case "opus" -> "audio/opus";
            case "wav" -> "audio/wav";
            case "wma" -> "audio/x-ms-wma";
            case "aac" -> "audio/aac";
            case "webm" -> "audio/webm";
            case "aiff", "aif" -> "audio/aiff";
            default -> DEFAULT_CONTENT_TYPE;
        };
    }
}