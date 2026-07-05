package com.fryfrog.hub.video.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class MediaInfoService {

    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of(
            ".srt", ".ass", ".ssa", ".vtt", ".sub", ".sup"
    );

    public List<Map<String, Object>> getExternalSubtitles(String videoPath) {
        Path dir = Path.of(videoPath).getParent();
        String baseName = getBaseName(Path.of(videoPath).getFileName().toString());
        List<Map<String, Object>> result = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return result;

        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString();
                        return name.startsWith(baseName) && SUBTITLE_EXTENSIONS.stream()
                                .anyMatch(ext -> name.toLowerCase().endsWith(ext));
                    })
                    .sorted()
                    .forEach(f -> {
                        String name = f.getFileName().toString();
                        String ext = name.substring(name.lastIndexOf('.')).toLowerCase();
                        result.add(Map.of(
                                "fileName", name,
                                "ext", ext,
                                "path", f.toString(),
                                "language", extractLanguageFromName(name, baseName)
                        ));
                    });
        } catch (IOException ignored) {
        }
        return result;
    }

    private String getBaseName(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private String extractLanguageFromName(String fileName, String baseName) {
        String withoutBase = fileName.substring(baseName.length());
        if (withoutBase.startsWith(".")) {
            String langPart = withoutBase.substring(1);
            int dotIndex = langPart.indexOf('.');
            if (dotIndex > 0) {
                return langPart.substring(0, dotIndex).toLowerCase();
            }
        }
        return "unknown";
    }
}
