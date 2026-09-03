package com.fryfrog.hub.mediacore.service;

import com.fryfrog.hub.mediacore.util.FFmpegBinaryExtractor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * FFmpeg/ffprobe 运行时定位与可用性探测。
 * 集中管理二进制路径与动态库环境变量，供转码、探测、截帧等子服务复用。
 */
@Component
@Slf4j
public class FFmpegRuntime {

    @Value("${video.ffmpeg-path:}")
    private String configuredFfmpegPath;

    private String ffmpegPath;
    private String ffprobePath;
    private String libraryDir;
    private boolean ffmpegAvailable = false;

    @PostConstruct
    public void init() {
        if (configuredFfmpegPath != null && !configuredFfmpegPath.isBlank()) {
            ffmpegPath = configuredFfmpegPath;
            ffprobePath = configuredFfmpegPath.replace("ffmpeg", "ffprobe");
            log.info("Using configured FFmpeg: {}", ffmpegPath);
        } else {
            FFmpegBinaryExtractor.BinaryPaths paths = FFmpegBinaryExtractor.extract();
            if (paths != null) {
                ffmpegPath = paths.ffmpeg();
                ffprobePath = paths.ffprobe();
                libraryDir = paths.libraryDir();
                log.info("Using bundled FFmpeg: {}, ffprobe: {}", ffmpegPath, ffprobePath);
            } else {
                ffmpegPath = "ffmpeg";
                ffprobePath = "ffprobe";
                log.warn("No FFmpeg found, trying system PATH");
            }
        }

        ffmpegAvailable = checkFfmpegAvailable();
        if (ffmpegAvailable) {
            log.info("FFmpeg transcoding available: {}", ffmpegPath);
        } else {
            log.warn("FFmpeg not available, transcoding disabled");
        }
    }

    private boolean checkFfmpegAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-version");
            if (libraryDir != null) {
                pb.environment().put(getLibraryPathEnv(), libraryDir);
            }
            Process p = pb.redirectErrorStream(true).start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            log.debug("FFmpeg check failed: {}", e.getMessage());
            return false;
        }
    }

    /** 为命令注入动态库环境变量（若使用内置 FFmpeg） */
    public void applyLibraryEnv(ProcessBuilder pb) {
        if (libraryDir != null) {
            pb.environment().put(getLibraryPathEnv(), libraryDir);
        }
    }

    public String getLibraryPathEnv() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) return "DYLD_LIBRARY_PATH";
        if (os.contains("win")) return "PATH";
        return "LD_LIBRARY_PATH";
    }

    public boolean isAvailable() {
        return ffmpegAvailable;
    }

    public String ffmpegPath() {
        return ffmpegPath;
    }

    public String ffprobePath() {
        return ffprobePath;
    }
}
