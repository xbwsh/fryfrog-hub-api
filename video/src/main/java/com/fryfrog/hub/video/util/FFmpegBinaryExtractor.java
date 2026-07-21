package com.fryfrog.hub.video.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
public class FFmpegBinaryExtractor {

    private static final Path EXTRACT_DIR = Path.of(System.getProperty("java.io.tmpdir"), "fryfrog-ffmpeg");

    public record BinaryPaths(String ffmpeg, String ffprobe, String libraryDir) {}

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

        // 检查是否已完整提取（二进制 + 动态库）
        if (Files.exists(ffmpegPath) && Files.exists(ffprobePath) && libsExist(platformDir, osName)) {
            log.info("Using extracted FFmpeg: {}", platformDir);
            return new BinaryPaths(ffmpegPath.toString(), ffprobePath.toString(), platformDir.toString());
        }

        // 二进制存在但动态库缺失，只补充提取动态库
        if (Files.exists(ffmpegPath) && Files.exists(ffprobePath)) {
            try {
                extractLibs(platform, platformDir, osName);
                return new BinaryPaths(ffmpegPath.toString(), ffprobePath.toString(), platformDir.toString());
            } catch (Exception e) {
                log.warn("Failed to extract FFmpeg libs: {}", e.getMessage());
            }
        }

        // 完整提取
        try {
            Files.createDirectories(platformDir);
            String basePath = "org/bytedeco/ffmpeg/" + platform + "/";

            boolean ffmpegFound = extractBinary(basePath + ffmpegName, ffmpegPath);
            boolean ffprobeFound = extractBinary(basePath + ffprobeName, ffprobePath);
            if (!ffmpegFound || !ffprobeFound) {
                throw new IOException("FFmpeg binary not found in classpath");
            }

            extractLibs(platform, platformDir, osName);
            setExecutable(ffmpegPath);
            setExecutable(ffprobePath);
            log.info("Extracted FFmpeg to: {}", platformDir);
            return new BinaryPaths(ffmpegPath.toString(), ffprobePath.toString(), platformDir.toString());
        } catch (Exception e) {
            log.warn("Failed to extract FFmpeg: {}", e.getMessage());
            return null;
        }
    }

    private static boolean libsExist(Path platformDir, String osName) {
        if (osName.contains("win")) return true;
        String suffix = osName.contains("mac") ? ".dylib" : ".so";
        String libName = osName.contains("mac") ? "libavcodec.60" + suffix : "libavcodec" + suffix + ".60";
        return Files.exists(platformDir.resolve(libName));
    }

    private static void extractLibs(String platform, Path targetDir, String osName) throws IOException {
        String basePath = "org/bytedeco/ffmpeg/" + platform + "/";
        String[] libs = {"avcodec", "avdevice", "avfilter", "avformat", "avutil", "swresample", "swscale"};
        int[] versions = {60, 60, 9, 60, 58, 4, 7};

        int extracted = 0;
        for (int i = 0; i < libs.length; i++) {
            String fileName = getLibFileName(libs[i], versions[i], osName);
            Path target = targetDir.resolve(fileName);
            if (!Files.exists(target) && extractBinary(basePath + fileName, target)) {
                extracted++;
            } else if (Files.exists(target)) {
                extracted++;
            }
        }
        if (extracted > 0) {
            log.info("Extracted {} FFmpeg libraries to {}", extracted, targetDir);
        }
    }

    private static String getLibFileName(String name, int version, String osName) {
        if (osName.contains("win")) {
            return name + "-" + version + ".dll";
        } else if (osName.contains("mac")) {
            return "lib" + name + "." + version + ".dylib";
        } else {
            return "lib" + name + ".so." + version;
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
