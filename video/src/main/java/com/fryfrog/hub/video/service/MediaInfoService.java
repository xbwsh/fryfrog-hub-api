package com.fryfrog.hub.video.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaInfoService {

    private final VideoRepository videoRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${hub.video.ffprobe-path:ffprobe}")
    private String ffprobePath;

    public Map<String, Object> analyzeFile(String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }

        List<String> command = new ArrayList<>();
        command.add(ffprobePath);
        command.add("-v");
        command.add("quiet");
        command.add("-print_format");
        command.add("json");
        command.add("-show_format");
        command.add("-show_streams");
        command.add(filePath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (var is = process.getInputStream()) {
            output = new String(is.readAllBytes());
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("ffprobe timed out");
        }

        if (process.exitValue() != 0) {
            throw new RuntimeException("ffprobe failed with exit code: " + process.exitValue());
        }

        return objectMapper.readValue(output, Map.class);
    }

    public MediaInfo extractMediaInfo(String filePath) throws Exception {
        Map<String, Object> probeResult = analyzeFile(filePath);
        MediaInfo info = new MediaInfo();

        JsonNode format = objectMapper.valueToTree(probeResult).path("format");
        if (!format.isMissingNode()) {
            if (format.has("duration")) {
                double durationSec = format.path("duration").asDouble();
                info.durationSeconds = durationSec;
                info.durationMinutes = (int) (durationSec / 60);
            }
            if (format.has("bit_rate")) {
                info.bitrateKbps = (int) (format.path("bit_rate").asLong() / 1000);
            }
            info.formatName = format.path("format_name").asText(null);
            info.formatLongName = format.path("format_long_name").asText(null);
        }

        JsonNode streams = objectMapper.valueToTree(probeResult).path("streams");
        if (streams.isArray()) {
            for (JsonNode stream : streams) {
                String codecType = stream.path("codec_type").asText("");
                switch (codecType) {
                    case "video":
                        info.videoCodec = stream.path("codec_name").asText(null);
                        info.videoCodecLong = stream.path("codec_long_name").asText(null);
                        int width = stream.path("width").asInt(0);
                        int height = stream.path("height").asInt(0);
                        if (width > 0 && height > 0) {
                            info.resolution = width + "x" + height;
                        }
                        String rFrameRate = stream.path("r_frame_rate").asText("0/1");
                        info.frameRate = parseFrameRate(rFrameRate);
                        break;
                    case "audio":
                        if (info.audioCodec == null) {
                            info.audioCodec = stream.path("codec_name").asText(null);
                            info.audioCodecLong = stream.path("codec_long_name").asText(null);
                            info.audioChannels = stream.path("channels").asInt(0);
                            info.audioSampleRate = stream.path("sample_rate").asText(null);
                        }
                        break;
                    case "subtitle":
                        SubtitleInfo sub = new SubtitleInfo();
                        sub.index = stream.path("index").asInt();
                        sub.codec = stream.path("codec_name").asText(null);
                        sub.language = stream.path("tags").path("language").asText(null);
                        sub.title = stream.path("tags").path("title").asText(null);
                        sub.defaultStream = "yes".equals(stream.path("disposition").path("default").asText("0"));
                        info.subtitles.add(sub);
                        break;
                }
            }
        }

        return info;
    }

    private double parseFrameRate(String rFrameRate) {
        try {
            String[] parts = rFrameRate.split("/");
            if (parts.length == 2) {
                double num = Double.parseDouble(parts[0]);
                double den = Double.parseDouble(parts[1]);
                if (den > 0) {
                    return Math.round(num / den * 100.0) / 100.0;
                }
            }
            return Double.parseDouble(rFrameRate);
        } catch (Exception e) {
            return 0;
        }
    }

    public void updateVideoMediaInfo(Video video) {
        try {
            MediaInfo info = extractMediaInfo(video.getFilePath());

            video.setVideoCodec(info.videoCodec);
            video.setAudioCodec(info.audioCodec);
            video.setResolution(info.resolution);
            video.setFrameRate(info.frameRate);
            video.setBitrateKbps(info.bitrateKbps);
            if (video.getDurationMinutes() == null || video.getDurationMinutes() == 0) {
                video.setDurationMinutes(info.durationMinutes);
            }

            videoRepository.save(video);
            log.info("Updated media info for: {}", video.getFileName());
        } catch (Exception e) {
            log.warn("Failed to analyze media info for {}: {}", video.getFileName(), e.getMessage());
        }
    }

    public List<Map<String, Object>> getSubtitleStreams(String filePath) throws Exception {
        MediaInfo info = extractMediaInfo(filePath);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SubtitleInfo sub : info.subtitles) {
            Map<String, Object> map = new HashMap<>();
            map.put("index", sub.index);
            map.put("codec", sub.codec);
            map.put("language", sub.language);
            map.put("title", sub.title);
            map.put("default", sub.defaultStream);
            result.add(map);
        }
        return result;
    }

    public static class MediaInfo {
        public double durationSeconds;
        public int durationMinutes;
        public int bitrateKbps;
        public String formatName;
        public String formatLongName;
        public String videoCodec;
        public String videoCodecLong;
        public String resolution;
        public double frameRate;
        public String audioCodec;
        public String audioCodecLong;
        public int audioChannels;
        public String audioSampleRate;
        public List<SubtitleInfo> subtitles = new ArrayList<>();
    }

    public static class SubtitleInfo {
        public int index;
        public String codec;
        public String language;
        public String title;
        public boolean defaultStream;
    }
}
