package com.fryfrog.hub.video.service;

import com.fryfrog.hub.video.model.Video;
import com.fryfrog.hub.video.model.VideoSeries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@Slf4j
public class NfoService {

    // ==================== NFO 生成 ====================

    /**
     * 生成单集/电影 NFO 文件
     */
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

    /**
     * 生成系列 NFO 文件 (tvshow.nfo)
     */
    public String generateTvShowNfo(VideoSeries series, Path seasonDir) {
        try {
            Files.createDirectories(seasonDir);
            Path nfoPath = seasonDir.resolve("tvshow.nfo");
            String nfoContent = buildTvShowNfoContent(series);
            Files.writeString(nfoPath, nfoContent, StandardCharsets.UTF_8);

            log.info("Generated tvshow.nfo: {}", nfoPath);
            return nfoPath.toString();
        } catch (IOException e) {
            log.error("Failed to generate tvshow.nfo for series {}: {}", series.getTitle(), e.getMessage(), e);
            return null;
        }
    }

    private String buildNfoContent(Video video) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");

        if ("tv".equalsIgnoreCase(video.getMediaType())) {
            sb.append("<episodedetails>\n");
        } else {
            sb.append("<movie>\n");
        }

        appendCommonFields(sb, video);

        // 电视剧额外字段
        if ("tv".equalsIgnoreCase(video.getMediaType())) {
            appendField(sb, "season", video.getSeasonNumber() != null ? video.getSeasonNumber() : 1);
            appendField(sb, "episode", video.getEpisodeNumber() != null ? video.getEpisodeNumber() : 1);
        }

        // 封面文件名
        appendField(sb, "thumb", getPosterFileName(video));
        appendField(sb, "fanart", getFanartFileName(video));

        if ("tv".equalsIgnoreCase(video.getMediaType())) {
            sb.append("</episodedetails>\n");
        } else {
            sb.append("</movie>\n");
        }

        return sb.toString();
    }

    private String buildTvShowNfoContent(VideoSeries series) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<tvshow>\n");

        appendField(sb, "title", series.getTitle());
        appendField(sb, "originaltitle", series.getOriginalTitle());
        appendField(sb, "plot", series.getOverview());
        appendField(sb, "year", series.getYear());
        appendField(sb, "status", series.getStatus());

        // 类型
        if (series.getMediaType() != null) {
            appendField(sb, "genre", series.getMediaType());
        }

        appendField(sb, "rating", series.getRating());

        // TMDB ID
        if (series.getTmdbId() != null) {
            sb.append("  <uniqueid type=\"tmdb\" default=\"false\">")
                    .append(series.getTmdbId())
                    .append("</uniqueid>\n");
        }

        // IMDB ID（从系列的第一个视频获取）
        if (series.getVideos() != null && !series.getVideos().isEmpty()) {
            Video firstVideo = series.getVideos().get(0);
            if (firstVideo.getImdbId() != null && !firstVideo.getImdbId().isBlank()) {
                sb.append("  <uniqueid type=\"imdb\">")
                        .append(firstVideo.getImdbId())
                        .append("</uniqueid>\n");
            }
        }

        // 封面
        appendField(sb, "thumb", "tvshow-poster.jpg");
        appendField(sb, "fanart", "tvshow-fanart.jpg");

        // 成人内容标记
        if (Boolean.TRUE.equals(series.getIsAdult())) {
            sb.append("  <adult>true</adult>\n");
        }

        sb.append("</tvshow>\n");
        return sb.toString();
    }

    /**
     * 写入通用字段（movie 和 episode 共用）
     */
    private void appendCommonFields(StringBuilder sb, Video video) {
        appendField(sb, "title", video.getTitle());
        appendField(sb, "originaltitle", video.getOriginalTitle());
        appendField(sb, "plot", video.getOverview());
        appendField(sb, "year", video.getYear());
        appendField(sb, "director", video.getDirector());

        // 演员
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

        // 类型
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
        appendField(sb, "runtime", video.getDurationMinutes());

        // TMDB ID
        if (video.getTmdbId() != null) {
            sb.append("  <uniqueid type=\"tmdb\" default=\"false\">")
                    .append(video.getTmdbId())
                    .append("</uniqueid>\n");
        }

        // IMDB ID
        if (video.getImdbId() != null && !video.getImdbId().isBlank()) {
            sb.append("  <uniqueid type=\"imdb\">")
                    .append(video.getImdbId())
                    .append("</uniqueid>\n");
        }

        // 成人内容标记
        if (Boolean.TRUE.equals(video.getIsAdult())) {
            sb.append("  <adult>true</adult>\n");
        }
    }

    // ==================== 路径计算 ====================

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

    /**
     * 获取季目录路径（用于 tvshow.nfo）
     */
    public Path getSeasonDir(Video video) {
        Path metadataDir = getMetadataDir(video);
        return metadataDir.getParent(); // 集目录的父目录就是季目录
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

    // ==================== 文件名工具 ====================

    private String getNfoFileName(Video video) {
        String baseName = getBaseName(video.getFileName());
        return baseName + ".nfo";
    }

    public String getBaseName(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(0, lastDot) : fileName;
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

    // ==================== NFO 读取 ====================

    public NfoData readNfoForVideo(Path videoPath) {
        Path videoDir = videoPath.getParent();
        String videoBaseName = getBaseName(videoPath.getFileName().toString());

        // 搜索顺序：单集NFO > tvshow.nfo > movie.nfo > xml
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
                if (data != null) {
                    // 如果是 tvshow.nfo，提取系列标题
                    if ("tvshow.nfo".equals(nfoPath.getFileName().toString())) {
                        data.isTvShow = true;
                        data.seriesTitle = data.showTitle != null ? data.showTitle : data.title;
                    }
                    // 如果是 episode NFO，尝试从父目录获取系列标题
                    if (data.isTvShow && data.seriesTitle == null) {
                        data.seriesTitle = findSeriesTitleFromParentDirs(videoDir);
                    }
                    return data;
                }
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
                    log.debug("Found tvshow.nfo in parent dir: {}", tvshowNfo);
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
            String rootTag = root.getTagName();
            data.isTvShow = "episodedetails".equalsIgnoreCase(rootTag) || "tvshow".equalsIgnoreCase(rootTag);

            // 通用字段
            data.title = getTagText(root, "title");
            data.originalTitle = getTagText(root, "originaltitle");
            data.showTitle = getTagText(root, "showtitle");
            data.plot = getTagText(root, "plot");
            data.director = getTagText(root, "director");
            data.year = getTagText(root, "year");
            data.genre = getTagText(root, "genre");
            data.runtime = getTagText(root, "runtime");
            data.status = getTagText(root, "status");

            if (data.year == null) {
                String premiered = getTagText(root, "premiered");
                if (premiered != null && premiered.length() >= 4) {
                    data.year = premiered.substring(0, 4);
                }
            }

            // 评分（支持两种格式：<rating>8.5</rating> 和 <rating><value>8.5</value></rating>）
            String ratingText = getTagText(root, "rating");
            if (ratingText != null && !ratingText.isBlank()) {
                data.rating = ratingText;
            } else {
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
            }

            // 投票数
            if (data.votes == null) {
                data.votes = getTagText(root, "votes");
            }

            // ID
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

            // 电视剧额外字段
            if ("episodedetails".equalsIgnoreCase(rootTag)) {
                data.season = getTagText(root, "season");
                data.episode = getTagText(root, "episode");
            }

            // 演员
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

            // 类型（支持多个 genre 标签）
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

            // 封面文件名
            data.thumb = getTagText(root, "thumb");
            data.fanart = getTagText(root, "fanart");

            // 成人内容标记
            String adultText = getTagText(root, "adult");
            data.isAdult = "true".equalsIgnoreCase(adultText);

            return data;
        } catch (Exception e) {
            log.warn("Failed to parse NFO file {}: {}", nfoPath, e.getMessage());
            return null;
        }
    }

    // ==================== NFO 数据应用 ====================

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
        if (data.status != null) video.setStatus(data.status);

        video.setMediaType(data.isTvShow ? "tv" : "movie");
        video.setMetadataSource("nfo");
        if (data.isAdult) {
            video.setIsAdult(true);
        }

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
            Path videoDir = Paths.get(video.getFilePath()).getParent();
            if (videoDir != null) {
                Path posterPath = videoDir.resolve(data.thumb);
                if (Files.exists(posterPath)) {
                    video.setCoverArtPath(posterPath.toString());
                }
            }
        }
        if (data.fanart != null && !data.fanart.isBlank()) {
            Path videoDir = Paths.get(video.getFilePath()).getParent();
            if (videoDir != null) {
                Path fanartPath = videoDir.resolve(data.fanart);
                if (Files.exists(fanartPath)) {
                    video.setBackdropLocalPath(fanartPath.toString());
                }
            }
        }
    }

    // ==================== XML 工具 ====================

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

    private String getTagText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            String text = nodes.item(0).getTextContent();
            return (text != null && !text.isBlank()) ? text.trim() : null;
        }
        return null;
    }

    // ==================== NFO 数据类 ====================

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
        public String status;
        public boolean isTvShow;
        public String seriesTitle;
        public String thumb;
        public String fanart;
        public boolean isAdult;
    }
}
