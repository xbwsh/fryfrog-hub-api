package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Video;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

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

    public NfoData readNfoForVideo(Path videoPath) {
        Path videoDir = videoPath.getParent();
        String videoBaseName = getBaseName(videoPath.getFileName().toString());

        Path[] searchPaths = {
                videoDir.resolve(videoBaseName + ".nfo"),
                videoDir.resolve("tvshow.nfo"),
                videoDir.resolve("movie.nfo"),
                videoDir.resolve(videoBaseName + ".xml")
        };

        for (Path nfoPath : searchPaths) {
            if (Files.exists(nfoPath)) {
                log.info("Found NFO file: {}", nfoPath);
                NfoData data = parseNfoFile(nfoPath);
                if (data != null && data.isTvShow) {
                    if ("tvshow.nfo".equals(nfoPath.getFileName().toString())) {
                        data.seriesTitle = data.showTitle != null ? data.showTitle : data.title;
                    }
                    if (data.seriesTitle == null) {
                        data.seriesTitle = findSeriesTitleFromParentDirs(videoDir);
                    }
                }
                return data;
            }
        }

        return null;
    }

    private String findSeriesTitleFromParentDirs(Path videoDir) {
        Path current = videoDir;
        while (current != null) {
            Path tvshowNfo = current.resolve("tvshow.nfo");
            if (Files.exists(tvshowNfo)) {
                NfoData seriesData = parseNfoFile(tvshowNfo);
                if (seriesData != null && seriesData.title != null) {
                    log.info("Found tvshow.nfo in parent dir: {}", tvshowNfo);
                    return seriesData.title;
                }
            }
            current = current.getParent();
        }
        return null;
    }

    private NfoData parseNfoFile(Path nfoPath) {
        try {
            String content = Files.readString(nfoPath, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return null;
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(content)));

            NfoData data = new NfoData();
            Element root = doc.getDocumentElement();
            data.isTvShow = "episodedetails".equalsIgnoreCase(root.getTagName());

            data.title = getTagText(root, "title");
            data.originalTitle = getTagText(root, "originaltitle");
            data.showTitle = getTagText(root, "showtitle");
            data.plot = getTagText(root, "plot");
            data.director = getTagText(root, "director");
            data.year = getTagText(root, "year");
            data.genre = getTagText(root, "genre");
            data.rating = getTagText(root, "rating");
            data.votes = getTagText(root, "votes");
            data.imdbId = getTagText(root, "imdbid");
            data.runtime = getTagText(root, "runtime");

            if (data.isTvShow) {
                data.season = getTagText(root, "season");
                data.episode = getTagText(root, "episode");
            }

            NodeList actorNodes = root.getElementsByTagName("actor");
            StringBuilder actors = new StringBuilder();
            for (int i = 0; i < actorNodes.getLength(); i++) {
                Element actorEl = (Element) actorNodes.item(i);
                String name = getTagText(actorEl, "name");
                if (name != null && !name.isBlank()) {
                    if (!actors.isEmpty()) actors.append(",");
                    actors.append(name.trim());
                }
            }
            data.actors = actors.toString();

            NodeList genreNodes = root.getElementsByTagName("genre");
            StringBuilder genres = new StringBuilder();
            for (int i = 0; i < genreNodes.getLength(); i++) {
                String g = genreNodes.item(i).getTextContent();
                if (g != null && !g.isBlank()) {
                    if (!genres.isEmpty()) genres.append(",");
                    genres.append(g.trim());
                }
            }
            if (!genres.isEmpty()) {
                data.genre = genres.toString();
            }

            return data;
        } catch (Exception e) {
            log.warn("Failed to parse NFO file {}: {}", nfoPath, e.getMessage());
            return null;
        }
    }

    private String getTagText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            String text = nodes.item(0).getTextContent();
            return (text != null && !text.isBlank()) ? text.trim() : null;
        }
        return null;
    }

    public void applyNfoData(Video video, NfoData data) {
        if (data.title != null) video.setTitle(data.title);
        if (data.originalTitle != null) video.setOriginalTitle(data.originalTitle);
        if (data.plot != null) video.setOverview(data.plot);
        if (data.director != null) video.setDirector(data.director);
        if (data.actors != null && !data.actors.isBlank()) video.setActors(data.actors);
        if (data.genre != null) video.setGenre(data.genre);
        if (data.year != null) {
            try { video.setYear(Integer.parseInt(data.year)); } catch (NumberFormatException ignored) {}
        }
        if (data.rating != null) {
            try { video.setRating(Double.parseDouble(data.rating)); } catch (NumberFormatException ignored) {}
        }
        if (data.votes != null) {
            try { video.setVoteCount(Integer.parseInt(data.votes)); } catch (NumberFormatException ignored) {}
        }
        if (data.imdbId != null) video.setImdbId(data.imdbId);
        if (data.runtime != null) {
            try { video.setDurationMinutes(Integer.parseInt(data.runtime)); } catch (NumberFormatException ignored) {}
        }

        video.setMediaType(data.isTvShow ? "tv" : "movie");
        video.setMetadataSource("nfo");

        if (data.season != null) {
            try { video.setSeasonNumber(Integer.parseInt(data.season)); } catch (NumberFormatException ignored) {}
        }
        if (data.episode != null) {
            try { video.setEpisodeNumber(Integer.parseInt(data.episode)); } catch (NumberFormatException ignored) {}
        }
        if (data.seriesTitle != null) {
            video.setSeriesName(data.seriesTitle);
        }
    }

    public static class NfoData {
        public String title;
        public String originalTitle;
        public String showTitle;
        public String plot;
        public String director;
        public String actors;
        public String genre;
        public String year;
        public String rating;
        public String votes;
        public String imdbId;
        public String runtime;
        public String season;
        public String episode;
        public boolean isTvShow;
        public String seriesTitle;
    }
}
