package com.fryfrog.hub.ebook.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * EPUB 元数据解析（纯 JDK）：container.xml → OPF → dc:* 元数据 + 内嵌封面 + spine 章节数。
 * 只读取必要 entry，绝不全量解压；XML 解析禁用 DTD 与外部实体。
 */
@Component
@Slf4j
public class EpubParser {

    /** 解析结果；coverPath 为提取出的封面本地路径（可能为 null）。 */
    public record EpubMeta(String title, String author, String publisher, String language,
                           Integer pubYear, String overview, Integer totalChapters, Path coverPath) {
    }

    private static final String DC_NS = "http://purl.org/dc/elements/1.1/";

    /** 解析失败抛 IOException，由调用方决定回退到文件名兜底。 */
    public EpubMeta parse(Path epubPath, Path coverOutputDir) throws Exception {
        try (JarFile zip = new JarFile(epubPath.toFile())) {
            String opfPath = findOpfPath(zip);
            if (opfPath == null) throw new IllegalStateException("EPUB 缺少 container.xml/rootfile");

            Element root = parseXml(zip, opfPath);
            String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

            String title = dcValue(root, "title");
            String author = dcValue(root, "creator");
            String publisher = dcValue(root, "publisher");
            String language = dcValue(root, "language");
            String description = dcValue(root, "description");
            Integer pubYear = yearOf(dcValue(root, "date"));

            Element manifest = firstChild(root, "manifest");
            Element spine = firstChild(root, "spine");
            int totalChapters = spine != null
                    ? spine.getElementsByTagNameNS("*", "itemref").getLength() : 0;

            Path coverPath = extractCover(zip, opfDir, manifest, coverOutputDir);

            return new EpubMeta(title, author, publisher, language, pubYear,
                    description, totalChapters > 0 ? totalChapters : null, coverPath);
        }
    }

    private String findOpfPath(JarFile zip) throws Exception {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) return null;
        Element root = parseXml(zip, "META-INF/container.xml");
        NodeList rootfiles = root.getElementsByTagName("*");
        for (int i = 0; i < rootfiles.getLength(); i++) {
            Element el = (Element) rootfiles.item(i);
            if ("rootfile".equals(el.getLocalName()) && el.hasAttribute("full-path")) {
                return el.getAttribute("full-path");
            }
        }
        return null;
    }

    private Element parseXml(JarFile zip, String entryPath) throws Exception {
        ZipEntry entry = zip.getEntry(entryPath);
        if (entry == null) throw new IllegalStateException("EPUB 缺少 entry: " + entryPath);
        try (InputStream in = zip.getInputStream(entry)) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            dbf.setNamespaceAware(true);
            return dbf.newDocumentBuilder().parse(in).getDocumentElement();
        }
    }

    private String dcValue(Element root, String localName) {
        NodeList nodes = root.getElementsByTagNameNS(DC_NS, localName);
        if (nodes.getLength() == 0) nodes = root.getElementsByTagName("dc:" + localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            String text = nodes.item(i).getTextContent();
            if (text != null && !text.isBlank()) return text.strip();
        }
        return null;
    }

    private Integer yearOf(String date) {
        if (date == null || date.length() < 4) return null;
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 封面定位优先级：manifest item properties 含 cover-image → meta[name=cover] 指向的 item id
     * → manifest id 含 cover 的图片。提取内容写到 coverOutputDir/cover.jpg。
     */
    private Path extractCover(JarFile zip, String opfDir, Element manifest, Path coverOutputDir) {
        if (manifest == null || coverOutputDir == null) return null;
        try {
            String coverHref = findCoverHref(manifest);
            if (coverHref == null) return null;
            String entryPath = opfDir + coverHref;
            // OPF 内 href 可能是 URL 编码
            entryPath = java.net.URLDecoder.decode(entryPath, java.nio.charset.StandardCharsets.UTF_8);
            ZipEntry entry = zip.getEntry(entryPath);
            if (entry == null) return null;

            byte[] data;
            try (InputStream in = zip.getInputStream(entry)) {
                data = in.readAllBytes();
            }
            if (data.length == 0) return null;

            Files.createDirectories(coverOutputDir);
            Path target = coverOutputDir.resolve("cover.jpg");
            Files.write(target, data);
            return target;
        } catch (Exception e) {
            log.debug("[EbookScan] Cover extract failed: {}", e.getMessage());
            return null;
        }
    }

    private String findCoverHref(Element manifest) {
        NodeList items = manifest.getElementsByTagNameNS("*", "item");
        // 1) properties="cover-image"（EPUB3）
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String props = item.getAttribute("properties");
            if (props != null && props.contains("cover-image")) return item.getAttribute("href");
        }
        // 2) meta[name=cover] → item id（EPUB2 惯例）
        NodeList metas = manifest.getElementsByTagName("meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if ("cover".equals(meta.getAttribute("name"))) {
                String coverId = meta.getAttribute("content");
                for (int j = 0; j < items.getLength(); j++) {
                    Element item = (Element) items.item(j);
                    if (coverId.equals(item.getAttribute("id"))) return item.getAttribute("href");
                }
            }
        }
        // 3) id 含 cover 的图片
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String id = item.getAttribute("id");
            String mediaType = item.getAttribute("media-type");
            if (id.toLowerCase().contains("cover") && mediaType != null && mediaType.startsWith("image/")) {
                return item.getAttribute("href");
            }
        }
        return null;
    }

    private Element firstChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && localName.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }
}
