package com.fryfrog.hub.audiobook.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;

/**
 * 有声书音频流输出：支持 HTTP Range 请求（拖拽定位）。
 * 与 MusicStreamService 同模式，但以文件+格式为入参，不耦合音乐实体。
 */
@Service
public class AudiobookStreamService {

    public void stream(HttpServletResponse response, File file, String format,
                       String rangeHeader, String downloadName) throws Exception {
        if (!file.exists()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String contentType = mimeType(format);
        long fileLength = file.length();

        if (downloadName != null) {
            response.setContentType(contentType);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + sanitizeFileName(downloadName) + "\"");
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

    public String mimeType(String format) {
        if (format == null) return "audio/mpeg";
        return switch (format.toLowerCase()) {
            case "m4a", "m4b", "m4r", "mp4", "alac" -> "audio/mp4";
            case "flac" -> "audio/flac";
            case "ogg", "oga" -> "audio/ogg";
            case "opus" -> "audio/opus";
            case "wav" -> "audio/wav";
            case "aac" -> "audio/aac";
            case "mka", "webm" -> "audio/webm";
            default -> "audio/mpeg";
        };
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "audio";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
