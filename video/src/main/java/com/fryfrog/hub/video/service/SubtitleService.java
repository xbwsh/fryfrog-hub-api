package com.fryfrog.hub.video.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubtitleService {

    private final MediaInfoService mediaInfoService;

    @Value("${hub.video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    public List<SubtitleFile> extractSubtitles(String videoPath, String outputDir) throws Exception {
        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            throw new IllegalArgumentException("Video file not found: " + videoPath);
        }

        List<Map<String, Object>> subtitleStreams = mediaInfoService.getSubtitleStreams(videoPath);
        if (subtitleStreams.isEmpty()) {
            log.info("No embedded subtitles found in: {}", videoFile.getName());
            return List.of();
        }

        Path outDir = Paths.get(outputDir);
        if (!Files.exists(outDir)) {
            Files.createDirectories(outDir);
        }

        String baseName = getBaseName(videoFile.getName());
        List<SubtitleFile> extracted = new ArrayList<>();

        for (Map<String, Object> stream : subtitleStreams) {
            int index = (int) stream.get("index");
            String language = stream.get("language") != null ? (String) stream.get("language") : "und";
            String codec = stream.get("codec") != null ? (String) stream.get("codec") : "subrip";

            String ext = getSubtitleExtension(codec);
            String fileName = baseName + "." + language + ext;
            Path outputPath = outDir.resolve(fileName);

            try {
                extractSubtitleStream(videoPath, index, outputPath.toString(), ext);
                SubtitleFile subFile = new SubtitleFile();
                subFile.index = index;
                subFile.language = language;
                subFile.codec = codec;
                subFile.title = (String) stream.get("title");
                subFile.defaultStream = (boolean) stream.getOrDefault("default", false);
                subFile.filePath = outputPath.toString();
                subFile.fileName = fileName;
                extracted.add(subFile);
                log.info("Extracted subtitle: {} (stream {})", fileName, index);
            } catch (Exception e) {
                log.warn("Failed to extract subtitle stream {}: {}", index, e.getMessage());
            }
        }

        return extracted;
    }

    private void extractSubtitleStream(String videoPath, int streamIndex, String outputPath, String ext) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");
        command.add("-i");
        command.add(videoPath);
        command.add("-map");
        command.add("0:" + streamIndex);

        if (ext.equals(".srt")) {
            command.add("-c:s");
            command.add("srt");
        } else if (ext.equals(".ass")) {
            command.add("-c:s");
            command.add("ass");
        } else {
            command.add("-c:s");
            command.add("copy");
        }

        command.add(outputPath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (var is = process.getInputStream()) {
            is.readAllBytes();
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("ffmpeg subtitle extraction timed out");
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("ffmpeg subtitle extraction failed with exit code: " + process.exitValue());
        }
    }

    public List<String> getAvailableSubtitles(String videoDir, String baseName) {
        File dir = new File(videoDir);
        if (!dir.exists()) {
            return List.of();
        }

        String[] supportedExts = {".srt", ".ass", ".ssa", ".vtt", ".sub"};
        File[] files = dir.listFiles((d, name) -> {
            if (!name.startsWith(baseName)) return false;
            String lower = name.toLowerCase();
            for (String ext : supportedExts) {
                if (lower.endsWith(ext)) return true;
            }
            return false;
        });

        if (files == null) {
            return List.of();
        }

        return java.util.Arrays.stream(files)
                .map(File::getName)
                .sorted()
                .collect(Collectors.toList());
    }

    public Path convertToVtt(Path subtitlePath) throws Exception {
        String fileName = subtitlePath.getFileName().toString();
        String lower = fileName.toLowerCase();

        if (lower.endsWith(".vtt")) {
            return subtitlePath;
        }

        Path vttPath = subtitlePath.getParent().resolve(fileName.substring(0, fileName.lastIndexOf('.')) + ".vtt");
        if (Files.exists(vttPath)) {
            return vttPath;
        }

        List<String> command = List.of(
                ffmpegPath, "-y", "-i", subtitlePath.toString(),
                "-c:s", "webvtt", "-f", "webvtt", vttPath.toString()
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (var is = process.getInputStream()) {
            is.readAllBytes();
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("ffmpeg subtitle conversion timed out");
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("ffmpeg subtitle conversion failed with exit code: " + process.exitValue());
        }

        return vttPath;
    }

    private String getBaseName(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private String getSubtitleExtension(String codec) {
        if (codec == null) return ".srt";
        return switch (codec.toLowerCase()) {
            case "subrip" -> ".srt";
            case "ass", "ssa" -> ".ass";
            case "webvtt" -> ".vtt";
            case "dvd_subtitle" -> ".sub";
            case "hdmv_pgs_subtitle", "pgssub" -> ".sup";
            default -> ".srt";
        };
    }

    public static class SubtitleFile {
        public int index;
        public String language;
        public String codec;
        public String title;
        public boolean defaultStream;
        public String filePath;
        public String fileName;
    }
}
