package com.fryfrog.hub.video.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

@Slf4j
public class FFmpegBinaryExtractor {

    private static final Path EXTRACT_DIR = Path.of(System.getProperty("java.io.tmpdir"), "fryfrog-ffmpeg");

    public record BinaryPaths(String ffmpeg, String ffprobe) {}

    public static BinaryPaths extract() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        String platform;
        String ffmpegName;
        String ffprobeName;

        if (osName.contains("win")) {
            platform = "windows-x86_64";
            ffmpegName = "ffmpeg.exe";
            ffprobeName = "ffprobe.exe";
        } else if (osName.contains("mac")) {
            platform = osArch.contains("arm") || osArch.contains("aarch") ? "macosx-arm64" : "macosx-x86_64";
            ffmpegName = "ffmpeg";
            ffprobeName = "ffprobe";
        } else {
            platform = osArch.contains("arm") || osArch.contains("aarch") ? "linux-arm64" : "linux-x86_64";
            ffmpegName = "ffmpeg";
            ffprobeName = "ffprobe";
        }

        Path platformDir = EXTRACT_DIR.resolve(platform);
        Path ffmpegPath = platformDir.resolve(ffmpegName);
        Path ffprobePath = platformDir.resolve(ffprobeName);

        if (Files.exists(ffmpegPath) && Files.exists(ffprobePath)) {
            log.info("Using extracted FFmpeg: {}", platformDir);
            return new BinaryPaths(ffmpegPath.toString(), ffprobePath.toString());
        }

        try {
            Files.createDirectories(platformDir);

            // bytedeco JAR 中二进制路径: org/bytedeco/ffmpeg/{platform}/{name}
            String basePath = "org/bytedeco/ffmpeg/" + platform + "/";

            boolean ffmpegFound = extractBinary(basePath + ffmpegName, ffmpegPath);
            boolean ffprobeFound = extractBinary(basePath + ffprobeName, ffprobePath);

            if (!ffmpegFound || !ffprobeFound) {
                throw new IOException("FFmpeg binary not found in classpath");
            }

            // Windows 需要提取 DLL 依赖
            if (osName.contains("win")) {
                extractDlls(platform, platformDir);
            }

            setExecutable(ffmpegPath);
            setExecutable(ffprobePath);
            log.info("Extracted FFmpeg to: {}", platformDir);
            return new BinaryPaths(ffmpegPath.toString(), ffprobePath.toString());
        } catch (Exception e) {
            log.warn("Failed to extract FFmpeg: {}", e.getMessage());
            return null;
        }
    }

    private static void extractDlls(String platform, Path targetDir) throws IOException {
        String basePath = "org/bytedeco/ffmpeg/" + platform + "/";
        Set<String> dlls = Set.of(
                "avcodec-60.dll", "avdevice-60.dll", "avfilter-9.dll",
                "avformat-60.dll", "avutil-58.dll", "postproc-57.dll",
                "swresample-4.dll", "swscale-7.dll"
        );
        for (String dll : dlls) {
            Path target = targetDir.resolve(dll);
            if (!Files.exists(target)) {
                extractBinary(basePath + dll, target);
            }
        }
    }

    private static boolean extractBinary(String resourcePath, Path targetPath) {
        try (InputStream is = FFmpegBinaryExtractor.class.getResourceAsStream("/" + resourcePath)) {
            if (is != null) {
                Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private static void setExecutable(Path path) {
        try {
            var perms = Files.getPosixFilePermissions(path);
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
