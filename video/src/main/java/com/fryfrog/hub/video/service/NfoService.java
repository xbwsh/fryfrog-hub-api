package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Video;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@Slf4j
public class NfoService {

    public String generateNfo(Video video) {
        try {
            Path metadataDir = getMetadataDir(video);
            Files.createDirectories(metadataDir);

            Path nfoPath = metadataDir.resolve(getNfoFileName(video));

            String nfoContent = buildNfoContent(video);
            Files.writeString(nfoPath, nfoContent, StandardCharsets.UTF_8);

            log.info("Generated NFO: {}", nfoPath);
            return nfoPath.toString();
        } catch (IOException e) {
            log.error("Failed to generate NFO for video {}: {}", video.getTitle(), e.getMessage(), e);
            return null;
        }
    }

    public Path getMetadataDir(Video video) {
        String cleanedTitle = cleanTitle(video.getTitle());
        Path videoDir = Paths.get(video.getFilePath()).getParent();
        Path basePath = videoDir.resolve(cleanedTitle);

        // 对于电视剧，按"第 X 季/第 Y 集"创建子目录
        if ("tv".equalsIgnoreCase(video.getMediaType())) {
            int season = video.getSeasonNumber() != null ? video.getSeasonNumber() : 1;
            int episode = video.getEpisodeNumber() != null ? video.getEpisodeNumber() : 1;
            return basePath.resolve("第 " + season + " 季").resolve("第 " + episode + " 集");
        }

        return basePath;
    }

    private String cleanTitle(String title) {
        if (title == null || title.isBlank()) {
            return "Unknown";
        }

        String cleaned = title;

        // 先处理明确的标记格式：S01E01, EP01, ＃1 等
        cleaned = cleaned.replaceAll("(?i)S\\d{1,2}E\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("(?i)EP?\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("[＃#]\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("[\\s._\\-]*(?:CD|DVD|BD|DISK?|PART|EP?|CHAPTER)[\\s._\\-#＃]*\\d{1,4}[\\s._\\-]*$", "");

        // 处理末尾数字（有分隔符或无分隔符）
        cleaned = cleaned.replaceAll("[\\s._\\-＃#EPep]+\\d{1,4}$", "");
        cleaned = cleaned.replaceAll("\\d{1,4}$", "");

        cleaned = cleaned.replaceAll("[\\[\\]【】()（）]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        return cleaned.isBlank() ? title : cleaned;
    }

    private String getNfoFileName(Video video) {
        String baseName = getBaseName(video.getFileName());
        return baseName + ".nfo";
    }

    private String getBaseName(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private String buildNfoContent(Video video) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");

        if ("tv".equalsIgnoreCase(video.getMediaType())) {
            sb.append("<episodedetails>\n");
            appendTvFields(sb, video);
            sb.append("</episodedetails>\n");
        } else {
            sb.append("<movie>\n");
            appendMovieFields(sb, video);
            sb.append("</movie>\n");
        }

        return sb.toString();
    }

    private void appendMovieFields(StringBuilder sb, Video video) {
        appendField(sb, "title", video.getTitle());
        appendField(sb, "originaltitle", video.getOriginalTitle());
        appendField(sb, "year", video.getYear());
        appendField(sb, "plot", video.getOverview());
        appendField(sb, "director", video.getDirector());

        if (video.getActors() != null) {
            String[] actors = video.getActors().split(",");
            for (String actor : actors) {
                String trimmed = actor.trim();
                if (!trimmed.isEmpty()) {
                    sb.append("  <actor>\n");
                    sb.append("    <name>").append(escapeXml(trimmed)).append("</name>\n");
                    sb.append("  </actor>\n");
                }
            }
        }

        if (video.getGenre() != null) {
            String[] genres = video.getGenre().split(",");
            for (String genre : genres) {
                String trimmed = genre.trim();
                if (!trimmed.isEmpty()) {
                    appendField(sb, "genre", trimmed);
                }
            }
        }

        appendField(sb, "rating", video.getRating());
        appendField(sb, "votes", video.getVoteCount());
        appendField(sb, "imdbid", video.getImdbId());

        if (video.getDurationMinutes() != null) {
            appendField(sb, "runtime", video.getDurationMinutes());
        }

        if (video.getTmdbId() != null) {
            sb.append("  <uniqueid type=\"tmdb\" default=\"false\">")
                    .append(video.getTmdbId())
                    .append("</uniqueid>\n");
        }

        appendField(sb, "thumb", getPosterFileName(video));
        appendField(sb, "fanart", getFanartFileName(video));
    }

    private void appendTvFields(StringBuilder sb, Video video) {
        appendField(sb, "title", video.getTitle());
        appendField(sb, "originaltitle", video.getOriginalTitle());
        appendField(sb, "season", video.getSeasonNumber() != null ? video.getSeasonNumber() : 1);
        appendField(sb, "episode", video.getEpisodeNumber() != null ? video.getEpisodeNumber() : 1);
        appendField(sb, "plot", video.getOverview());
        appendField(sb, "director", video.getDirector());

        if (video.getActors() != null) {
            String[] actors = video.getActors().split(",");
            for (String actor : actors) {
                String trimmed = actor.trim();
                if (!trimmed.isEmpty()) {
                    sb.append("  <actor>\n");
                    sb.append("    <name>").append(escapeXml(trimmed)).append("</name>\n");
                    sb.append("  </actor>\n");
                }
            }
        }

        if (video.getGenre() != null) {
            String[] genres = video.getGenre().split(",");
            for (String genre : genres) {
                String trimmed = genre.trim();
                if (!trimmed.isEmpty()) {
                    appendField(sb, "genre", trimmed);
                }
            }
        }

        appendField(sb, "rating", video.getRating());
        appendField(sb, "votes", video.getVoteCount());

        if (video.getYear() != null) {
            appendField(sb, "year", video.getYear());
        }

        if (video.getTmdbId() != null) {
            sb.append("  <uniqueid type=\"tmdb\" default=\"false\">")
                    .append(video.getTmdbId())
                    .append("</uniqueid>\n");
        }

        appendField(sb, "thumb", getPosterFileName(video));
        appendField(sb, "fanart", getFanartFileName(video));
    }

    private void appendField(StringBuilder sb, String tag, Object value) {
        if (value != null) {
            sb.append("  <").append(tag).append(">")
                    .append(escapeXml(String.valueOf(value)))
                    .append("</").append(tag).append(">\n");
        }
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    public String getPosterFileName(Video video) {
        return getBaseName(video.getFileName()) + "-poster.jpg";
    }

    public String getFanartFileName(Video video) {
        return getBaseName(video.getFileName()) + "-fanart.jpg";
    }

    public Path getPosterPath(Video video) {
        return getMetadataDir(video).resolve(getPosterFileName(video));
    }

    public Path getFanartPath(Video video) {
        return getMetadataDir(video).resolve(getFanartFileName(video));
    }

    public Path getNfoPath(Video video) {
        return getMetadataDir(video).resolve(getNfoFileName(video));
    }
}
