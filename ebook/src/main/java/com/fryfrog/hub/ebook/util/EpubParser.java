package com.fryfrog.hub.ebook.util;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
public class EpubParser {

    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "^\\s*(第[零一二三四五六七八九十百千万\\d]+[章节目回篇]|Chapter\\s+\\d+|CHAPTER\\s+\\d+).*",
            Pattern.MULTILINE
    );

    public record EpubMetadata(
            String title,
            String author,
            String language,
            String description,
            String publisher,
            String isbn,
            Integer year,
            String coverEntryName
    ) {}

    public record ChapterEntry(
            String id,
            String title,
            String href
    ) {}

    public static boolean isEpub(String filePath) {
        return filePath != null && filePath.toLowerCase().endsWith(".epub");
    }

    public static EpubMetadata extractMetadata(String filePath) throws Exception {
        try (ZipFile zip = new ZipFile(filePath, StandardCharsets.UTF_8)) {
            String opfPath = findOpfPath(zip);
            if (opfPath == null) {
                log.warn("No OPF found in epub: {}", filePath);
                return null;
            }

            String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            Document doc = parseXml(zip, opfPath);
            if (doc == null) return null;

            Element metadata = getFirstChildElement(doc.getDocumentElement(), "metadata");
            if (metadata == null) return null;

            String title = getFirstTagText(metadata, "dc:title");
            String author = getFirstTagText(metadata, "dc:creator");
            String language = getFirstTagText(metadata, "dc:language");
            String description = getFirstTagText(metadata, "dc:description");
            String publisher = getFirstTagText(metadata, "dc:publisher");
            String isbn = getFirstTagText(metadata, "dc:identifier");
            String coverEntryName = findCoverEntry(doc, zip, opfDir);

            Integer year = null;
            String dateStr = getFirstTagText(metadata, "dc:date");
            if (dateStr != null && dateStr.length() >= 4) {
                try {
                    year = Integer.parseInt(dateStr.substring(0, 4));
                } catch (NumberFormatException ignored) {}
            }

            return new EpubMetadata(title, author, language, description, publisher, isbn, year, coverEntryName);
        }
    }

    public static List<ChapterEntry> extractChapters(String filePath) throws Exception {
        try (ZipFile zip = new ZipFile(filePath, StandardCharsets.UTF_8)) {
            String opfPath = findOpfPath(zip);
            if (opfPath == null) return Collections.emptyList();

            String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            Document doc = parseXml(zip, opfPath);
            if (doc == null) return Collections.emptyList();

            Map<String, String> manifest = parseManifest(doc, opfDir);
            List<String> spineIdRefs = parseSpine(doc);

            List<ChapterEntry> chapters = new ArrayList<>();
            int num = 1;
            for (String idref : spineIdRefs) {
                String href = manifest.get(idref);
                if (href == null) {
                    log.debug("Spine idref '{}' not found in manifest, skipping", idref);
                    continue;
                }

                String fullPath = opfDir + href;
                String text = readEntryText(zip, fullPath);
                if (text == null || text.isBlank()) continue;

                String plainText = stripHtml(text);
                String title = extractChapterTitle(plainText, num);
                chapters.add(new ChapterEntry(String.valueOf(num), title, href));
                num++;
            }
            return chapters;
        }
    }

    public static String readChapterContent(String filePath, String href) throws Exception {
        try (ZipFile zip = new ZipFile(filePath, StandardCharsets.UTF_8)) {
            String opfPath = findOpfPath(zip);
            if (opfPath == null) return "";

            String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            String fullPath = opfDir + href;
            String text = readEntryText(zip, fullPath);
            if (text == null) return "";
            return stripHtml(text).trim();
        }
    }

    public static String readChapterHtml(String filePath, String href) throws Exception {
        try (ZipFile zip = new ZipFile(filePath, StandardCharsets.UTF_8)) {
            String opfPath = findOpfPath(zip);
            if (opfPath == null) return "";

            String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            String fullPath = opfDir + href;
            String html = readEntryText(zip, fullPath);
            if (html == null) return "";
            return rewriteImageSrc(html, opfDir, filePath);
        }
    }

    public static byte[] readImage(String filePath, String imageName) throws Exception {
        String normalized = Path.of(imageName).normalize().toString().replace('\\', '/');
        try (ZipFile zip = new ZipFile(filePath, StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(normalized);
            if (entry == null) {
                for (ZipEntry e : Collections.list(zip.entries())) {
                    if (e.getName().endsWith("/" + normalized) || e.getName().equals(normalized)) {
                        if (!e.isDirectory() && isImageFile(e.getName().toLowerCase())) {
                            entry = e;
                            break;
                        }
                    }
                }
            }
            if (entry == null) return null;
            try (InputStream is = zip.getInputStream(entry)) {
                return is.readAllBytes();
            }
        }
    }

    public static List<String> listImages(String filePath) throws Exception {
        try (ZipFile zip = new ZipFile(filePath, StandardCharsets.UTF_8)) {
            List<String> images = new ArrayList<>();
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (!e.isDirectory() && isImageFile(e.getName().toLowerCase())) {
                    images.add(e.getName());
                }
            }
            return images;
        }
    }

    private static final Pattern IMG_SRC_PATTERN = Pattern.compile(
            "(<img[^>]*\\bsrc=[\"'])([^\"']+)([\"'])"
    );

    private static String rewriteImageSrc(String html, String opfDir, String epubFilePath) {
        String encodedPath = java.net.URLEncoder.encode(epubFilePath, StandardCharsets.UTF_8);
        String baseUrl = "/api/v1/ebook/epub-image?filePath=" + encodedPath + "&file=";
        Matcher m = IMG_SRC_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String src = m.group(2);
            String fullPath = Path.of(opfDir + src).normalize().toString().replace('\\', '/');
            String replacement = m.group(1) + baseUrl
                    + java.net.URLEncoder.encode(fullPath, StandardCharsets.UTF_8) + m.group(3);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static byte[] readCover(String filePath, EpubMetadata metadata) throws Exception {
        if (metadata == null || metadata.coverEntryName() == null) return null;

        try (ZipFile zip = new ZipFile(filePath, StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(metadata.coverEntryName());
            if (entry == null) return null;
            try (InputStream is = zip.getInputStream(entry)) {
                return is.readAllBytes();
            }
        }
    }

    public static String readAllContent(String filePath) throws Exception {
        List<ChapterEntry> chapters = extractChapters(filePath);
        if (chapters.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (ChapterEntry ch : chapters) {
            String content = readChapterContent(filePath, ch.href());
            if (!content.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(content);
            }
        }
        return sb.toString();
    }

    private static String findOpfPath(ZipFile zip) {
        ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
        if (containerEntry == null) return null;

        try {
            Document doc = parseXmlFromEntry(zip, containerEntry);
            if (doc == null) return null;

            NodeList rootfiles = doc.getElementsByTagName("rootfile");
            if (rootfiles.getLength() == 0) return null;

            Element rootfile = (Element) rootfiles.item(0);
            return rootfile.getAttribute("full-path");
        } catch (Exception e) {
            log.warn("Failed to parse container.xml: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, String> parseManifest(Document doc, String opfDir) {
        Map<String, String> manifest = new HashMap<>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String id = item.getAttribute("id");
            String href = item.getAttribute("href");
            manifest.put(id, href);
        }
        return manifest;
    }

    private static List<String> parseSpine(Document doc) {
        List<String> spine = new ArrayList<>();
        NodeList itemrefs = doc.getElementsByTagName("itemref");
        for (int i = 0; i < itemrefs.getLength(); i++) {
            Element ref = (Element) itemrefs.item(i);
            spine.add(ref.getAttribute("idref"));
        }
        return spine;
    }

    private static String findCoverEntry(Document doc, ZipFile zip, String opfDir) {
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String properties = item.getAttribute("properties");
            if (properties != null && properties.contains("cover-image")) {
                return opfDir + item.getAttribute("href");
            }
        }

        NodeList metas = doc.getElementsByTagName("meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if ("cover".equals(meta.getAttribute("name"))) {
                String coverId = meta.getAttribute("content");
                for (int j = 0; j < items.getLength(); j++) {
                    Element item = (Element) items.item(j);
                    if (coverId.equals(item.getAttribute("id"))) {
                        return opfDir + item.getAttribute("href");
                    }
                }
            }
        }

        String[] coverNames = {
                "cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "cover.gif",
                "images/cover.jpg", "images/cover.jpeg", "images/cover.png",
                "img/cover.jpg", "img/cover.jpeg", "img/cover.png",
                "OEBPS/cover.jpg", "OEBPS/cover.jpeg", "OEBPS/cover.png",
                "OEBPS/images/cover.jpg", "OEBPS/images/cover.jpeg", "OEBPS/images/cover.png",
                "OEBPS/img/cover.jpg", "OEBPS/img/cover.jpeg", "OEBPS/img/cover.png"
        };
        for (String name : coverNames) {
            if (zip.getEntry(name) != null) return name;
        }

        for (ZipEntry e : Collections.list(zip.entries())) {
            String lower = e.getName().toLowerCase();
            if (!e.isDirectory() && (lower.contains("cover") && isImageFile(lower))) {
                return e.getName();
            }
        }
        return null;
    }

    private static String readEntryText(ZipFile zip, String entryName) {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (e.getName().endsWith("/") || e.getName().contains("META-INF")) continue;
                if (e.getName().endsWith("/" + entryName) || e.getName().equals(entryName)) {
                    entry = e;
                    break;
                }
            }
        }
        if (entry == null) return null;

        try (InputStream is = zip.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to read entry {}: {}", entryName, e.getMessage());
            return null;
        }
    }

    private static Document parseXml(ZipFile zip, String entryName) {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) return null;
        return parseXmlFromEntry(zip, entry);
    }

    private static Document parseXmlFromEntry(ZipFile zip, ZipEntry entry) {
        try (InputStream is = zip.getInputStream(entry)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(is);
        } catch (Exception e) {
            log.warn("Failed to parse XML from {}: {}", entry.getName(), e.getMessage());
            return null;
        }
    }

    private static Element getFirstChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el) {
                String name = el.getTagName();
                if (name.endsWith(":" + localName) || name.equals(localName)) {
                    return el;
                }
            }
        }
        return null;
    }

    private static String getFirstTagText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        String text = nodes.item(0).getTextContent();
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    private static String extractChapterTitle(String text, int num) {
        Matcher m = CHAPTER_PATTERN.matcher(text);
        if (m.find()) {
            return m.group().trim();
        }
        return "第 " + num + " 章";
    }

    private static boolean isImageFile(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".gif")
                || name.endsWith(".webp") || name.endsWith(".bmp");
    }

    static String stripHtml(String html) {
        String text = html.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "");
        text = text.replaceAll("<style[^>]*>[\\s\\S]*?</style>", "");
        text = text.replaceAll("<br\\s*/?>", "\n");
        text = text.replaceAll("</?p[^>]*>", "\n");
        text = text.replaceAll("</?div[^>]*>", "\n");
        text = text.replaceAll("</?h[1-6][^>]*>", "\n");
        text = text.replaceAll("<[^>]+>", "");
        text = text.replaceAll("&nbsp;", " ");
        text = text.replaceAll("&lt;", "<");
        text = text.replaceAll("&gt;", ">");
        text = text.replaceAll("&amp;", "&");
        text = text.replaceAll("&quot;", "\"");
        text = text.replaceAll("&#\\d+;", "");
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }
}