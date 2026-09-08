package com.fryfrog.hub.comic.service;

import com.fryfrog.hub.comic.model.ComicChapter;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.util.MediaUrlSigner;
import com.fryfrog.hub.common.util.NaturalOrderComparator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 页图服务：列出章节页图（带签名 URL）与按索引读取页图内容。
 * 页序 = 文件名自然序；目录章节直接列文件，压缩包章节读 zip 条目。
 */
@Service
@Slf4j
public class ComicPageService {

    public static final String PAGE_PATH_TEMPLATE = "/api/v1/comics/chapters/%d/pages/%d";

    private static final List<String> MEDIA_TYPES = List.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/avif");

    public boolean isImageFile(String name) {
        if (name == null) return false;
        // cover.jpg 是封面约定文件，不计入页图
        if (name.equalsIgnoreCase("cover.jpg")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
                || lower.endsWith(".avif");
    }

    /** 章节页图文件名列表（自然序）。 */
    public List<String> listPageNames(ComicChapter chapter) throws IOException {
        if (ComicChapter.TYPE_ARCHIVE.equals(chapter.getType())) {
            try (ZipFile zip = new ZipFile(chapter.getFilePath())) {
                List<String> names = new ArrayList<>();
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && isImageFile(entry.getName())) {
                        names.add(entry.getName());
                    }
                }
                names.sort(NaturalOrderComparator.INSTANCE);
                return names;
            }
        }
        Path dir = Paths.get(chapter.getFilePath());
        try (Stream<Path> walk = Files.list(dir)) {
            List<String> names = walk.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(this::isImageFile)
                    .sorted(NaturalOrderComparator.INSTANCE)
                    .toList();
            return new ArrayList<>(names);
        }
    }

    /** 章节页数（异常返回 null）。 */
    public Integer pageCount(ComicChapter chapter) {
        try {
            return listPageNames(chapter).size();
        } catch (Exception e) {
            log.warn("[ComicPage] Page count failed {}: {}", chapter.getFilePath(), e.getMessage());
            return null;
        }
    }

    /** 每页签名 URL 列表（index 对应自然序位置）。 */
    public List<String> pageUrls(ComicChapter chapter) throws IOException {
        List<String> names = listPageNames(chapter);
        List<String> urls = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            urls.add(MediaUrlSigner.sign(String.format(PAGE_PATH_TEMPLATE, chapter.getId(), i)));
        }
        return urls;
    }

    /** 读取指定索引的页图内容。 */
    public ByteArrayResource readPage(ComicChapter chapter, int index) throws IOException {
        List<String> names = listPageNames(chapter);
        if (index < 0 || index >= names.size()) {
            throw new ResourceNotFoundException("ComicPage", "index", index);
        }
        String name = names.get(index);
        byte[] data;
        if (ComicChapter.TYPE_ARCHIVE.equals(chapter.getType())) {
            try (ZipFile zip = new ZipFile(chapter.getFilePath())) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) throw new ResourceNotFoundException("ComicPage", "entry", name);
                try (InputStream in = zip.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
            }
        } else {
            data = Files.readAllBytes(Paths.get(chapter.getFilePath(), name));
        }
        return new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return name;
            }
        };
    }

    public MediaType mediaTypeOf(String filename) {
        if (filename == null) return MediaType.APPLICATION_OCTET_STREAM;
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".bmp")) return MediaType.parseMediaType("image/bmp");
        if (lower.endsWith(".avif")) return MediaType.parseMediaType("image/avif");
        return MediaType.IMAGE_JPEG;
    }
}
