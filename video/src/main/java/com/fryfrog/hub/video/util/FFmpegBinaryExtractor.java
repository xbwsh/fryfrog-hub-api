package com.fryfrog.hub.video.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

@Slf4j
public class FFmpegBinaryExtractor {

    private static final Path EXTRACT_DIR = Path.of(System.getProperty("java.io.tmpdir"), "fryfrog-ffmpeg");

    public record BinaryPaths(String ffmpeg, String ffprobe) {}

    public static BinaryPaths extract() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        String platformDir;
        String ffmpegName;
        String ffprobeName;

        if (osName.contains("win")) {
            platformDir = "windows-x86_64";
            ffmpegName = "ffmpeg.exe";
            ffprobeName = "ffprobe.exe";
        } else if (osName.contains("mac")) {
            platformDir = osArch.contains("arm") || osArch.contains("aarch") ? "macosx-arm64" : "macosx-x86_64";
            ffmpegName = "ffmpeg";
            ffprobeName = "ffprobe";
        } else {
            platformDir = osArch.contains("arm") || osArch.contains("aarch") ? "linux-arm64" : "linux-x86_64";
            ffmpegName = "ffmpeg";
            ffprobeName = "ffprobe";
        }

        Path platformDirPath = EXTRACT_DIR.resolve(platformDir);
        Path ffmpegPath = platformDirPath.resolve(ffmpegName);
        Path ffprobePath = platformDirPath.resolve(ffprobeName);

        if (Files.exists(ffmpegPath) && Files.exists(ffprobePath)) {
            log.info("使用已提取的内嵌 FFmpeg: {}", platformDir);
            return new BinaryPaths(ffmpegPath.toString(), ffprobePath.toString());
        }

        try {
            Files.createDirectories(platformDirPath);
            extractResource(platformDir + "/" + ffmpegName, ffmpegPath);
            extractResource(platformDir + "/" + ffprobeName, ffprobePath);
            setExecutable(ffmpegPath);
            setExecutable(ffprobePath);
            log.info("已提取内嵌 FFmpeg 到: {}", platformDir);
            return new BinaryPaths(ffmpegPath.toString(), ffprobePath.toString());
        } catch (Exception e) {
            log.warn("提取内嵌 FFmpeg 失败: {}", e.getMessage());
            return null;
        }
    }

    private static void extractResource(String resourcePath, Path targetPath) throws IOException {
        try (InputStream is = FFmpegBinaryExtractor.class.getResourceAsStream("/" + resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setExecutable(Path path) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
