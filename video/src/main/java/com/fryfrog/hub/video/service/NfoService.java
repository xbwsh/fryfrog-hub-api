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
        Path videoPath = Paths.get(video.getFilePath());
        Path videoDir = videoPath.getParent();
        String showName = cleanTitle(selectShowName(video));

        // 对于电视剧，根据 seasonNumber 和 episodeNumber 确定目录
        if ("tv".equalsIgnoreCase(video.getMediaType())) {
            int season = video.getSeasonNumber() != null ? video.getSeasonNumber() : 1;
            int episode = video.getEpisodeNumber() != null ? video.getEpisodeNumber() : 1;

            // 构建正确的集目录名
            String correctEpisodeDirName = "第 " + episode + " 集";

            // 向上查找季目录（以"第 X 季"命名），但必须验证上级目录是正确的 showName
            Path parentDir = videoDir.getParent();
            while (parentDir != null) {
                if (parentDir.getFileName() == null) break;
                String parentName = parentDir.getFileName().toString();
                if (parentName.matches("第 \\d+ 季")) {
                    // 找到季目录，验证其父目录是否是正确的 showName
                    Path seasonParent = parentDir.getParent();
                    if (seasonParent != null && cleanTitle(seasonParent.getFileName().toString()).equals(showName)) {
                        return parentDir.resolve(correctEpisodeDirName);
                    }
                    // 父目录不是正确的 showName，继续向上查找
                }
                parentDir = parentDir.getParent();
            }

            // 向上查找是否已在 showName 目录下
            Path checkDir = videoDir;
            while (checkDir != null) {
                if (checkDir.getFileName() != null && cleanTitle(checkDir.getFileName().toString()).equals(showName)) {
                    // 已在 showName 目录下，直接构建季/集目录
                    String seasonDirName = "第 " + season + " 季";
                    return checkDir.resolve(seasonDirName).resolve(correctEpisodeDirName);
                }
                checkDir = checkDir.getParent();
            }

            // 没找到正确的 showName 目录，向上找到合适的根目录再创建
            // 一直向上直到父目录为 null（根目录）
            Path baseDir = videoDir;
            while (baseDir.getParent() != null) {
                String dirName = baseDir.getFileName().toString();
                // 跳过已整理的季/集目录结构
                if (dirName.matches("第 \\d+ 季") || dirName.matches("第 \\d+ 集")) {
                    baseDir = baseDir.getParent();
                    continue;
                }
                baseDir = baseDir.getParent();
            }
            String seasonDirName = "第 " + season + " 季";
            return baseDir.resolve(showName).resolve(seasonDirName).resolve(correctEpisodeDirName);
        }

        // 对于电影，在视频目录下创建以清洗后标题命名的子目录
        Path checkDir = videoDir;
        while (checkDir != null) {
            if (checkDir.getFileName() != null && cleanTitle(checkDir.getFileName().toString()).equals(showName)) {
                return checkDir;
            }
            checkDir = checkDir.getParent();
        }
        return videoDir.resolve(showName);
    }

    private String selectShowName(Video video) {
        String title = video.getTitle();
        String originalTitle = video.getOriginalTitle();

        // title 包含中文字符就用 title
        if (title != null && title.codePoints().anyMatch(cp ->
                (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF))) {
            return title;
        }
        // originalTitle 包含中文字符就用 originalTitle
        if (originalTitle != null && originalTitle.codePoints().anyMatch(cp ->
                (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF))) {
            return originalTitle;
        }
        // 都没有中文，用 title
        return title != null ? title : (originalTitle != null ? originalTitle : "Unknown");
    }

    private String cleanTitle(String title) {
        String cleaned = com.fryfrog.hub.common.util.TitleCleaner.cleanForSearch(title);
        return (cleaned == null || cleaned.isBlank()) ? "Unknown" : cleaned;
    }

    private String getNfoFileName(Video video) {
        String baseName = getBaseName(video.getFileName());
        return baseName + ".nfo";
    }

    public String getBaseName(String fileName) {
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

        if (video.getImdbId() != null && !video.getImdbId().isBlank()) {
            sb.append("  <uniqueid type=\"imdb\">")
                    .append(video.getImdbId())
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
                log.debug("Found NFO file: {}", nfoPath);
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
            data.runtime = getTagText(root, "runtime");

            if (data.year == null) {
                String premiered = getTagText(root, "premiered");
                if (premiered != null && premiered.length() >= 4) {
                    data.year = premiered.substring(0, 4);
                }
            }

            NodeList ratingNodes = root.getElementsByTagName("rating");
            for (int i = 0; i < ratingNodes.getLength(); i++) {
                Element ratingEl = (Element) ratingNodes.item(i);
                String value = getTagText(ratingEl, "value");
                if (value != null && !value.isBlank()) {
                    data.rating = value;
                    String votes = getTagText(ratingEl, "votes");
                    if (votes != null) data.votes = votes;
                    break;
                }
            }

            NodeList uniqueIdNodes = root.getElementsByTagName("uniqueid");
            for (int i = 0; i < uniqueIdNodes.getLength(); i++) {
                Element uidEl = (Element) uniqueIdNodes.item(i);
                String type = uidEl.getAttribute("type");
                String value = uidEl.getTextContent();
                if (value == null || value.isBlank()) continue;
                if ("tmdb".equalsIgnoreCase(type)) {
                    try { data.tmdbId = Long.parseLong(value.trim()); } catch (NumberFormatException ignored) {}
                } else if ("imdb".equalsIgnoreCase(type)) {
                    data.imdbId = value.trim();
                }
            }

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

            // 读取封面文件名（本地文件名，不是URL）
            data.thumb = getTagText(root, "thumb");
            data.fanart = getTagText(root, "fanart");

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
        if (data.tmdbId != null) video.setTmdbId(data.tmdbId);
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

        // 设置本地封面路径
        if (data.thumb != null && !data.thumb.isBlank()) {
            java.nio.file.Path videoDir = java.nio.file.Paths.get(video.getFilePath()).getParent();
            if (videoDir != null) {
                java.nio.file.Path posterPath = videoDir.resolve(data.thumb);
                if (java.nio.file.Files.exists(posterPath)) {
                    video.setCoverArtPath(posterPath.toString());
                }
            }
        }
        if (data.fanart != null && !data.fanart.isBlank()) {
            java.nio.file.Path videoDir = java.nio.file.Paths.get(video.getFilePath()).getParent();
            if (videoDir != null) {
                java.nio.file.Path fanartPath = videoDir.resolve(data.fanart);
                if (java.nio.file.Files.exists(fanartPath)) {
                    video.setBackdropLocalPath(fanartPath.toString());
                }
            }
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
        public Long tmdbId;
        public String runtime;
        public String season;
        public String episode;
        public boolean isTvShow;
        public String seriesTitle;
        public String thumb;  // 封面文件名
        public String fanart; // 背景图文件名
    }
}
