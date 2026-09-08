package com.fryfrog.hub.ebook.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.ActiveProfiles;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
class EpubParserTest {

    @TempDir
    Path tempDir;

    private static final String CONTAINER_XML = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
            """;

    private static final String OPF = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>三体</dc:title>
                <dc:creator>刘慈欣</dc:creator>
                <dc:publisher>重庆出版社</dc:publisher>
                <dc:language>zh</dc:language>
                <dc:date>2008-01-01</dc:date>
                <dc:description>文化大革命如火如荼进行的同时…</dc:description>
              </metadata>
              <manifest>
                <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
            </package>
            """;

    private Path writeEpub(String coverProperties) throws Exception {
        Path epub = tempDir.resolve("book.epub");
        try (JarOutputStream zip = new JarOutputStream(new FileOutputStream(epub.toFile()))) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml", CONTAINER_XML);
            put(zip, "OEBPS/content.opf",
                    coverProperties == null ? OPF
                            : OPF.replace("properties=\"cover-image\"", "properties=\"" + coverProperties + "\""));
            put(zip, "OEBPS/ch1.xhtml", "<html/>");
            put(zip, "OEBPS/ch2.xhtml", "<html/>");
            zip.putNextEntry(new ZipEntry("OEBPS/images/cover.jpg"));
            zip.write(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x10});
            zip.closeEntry();
        }
        return epub;
    }

    private void put(JarOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    void parsesDublinCoreMetadata() throws Exception {
        Path epub = writeEpub(null);
        EpubParser.EpubMeta meta = new EpubParser().parse(epub, tempDir.resolve("out"));

        assertThat(meta.title()).isEqualTo("三体");
        assertThat(meta.author()).isEqualTo("刘慈欣");
        assertThat(meta.publisher()).isEqualTo("重庆出版社");
        assertThat(meta.language()).isEqualTo("zh");
        assertThat(meta.pubYear()).isEqualTo(2008);
        assertThat(meta.overview()).contains("文化大革命");
        assertThat(meta.totalChapters()).isEqualTo(2);
    }

    @Test
    void extractsCoverByEpub3Property() throws Exception {
        Path epub = writeEpub(null);
        Path coverDir = tempDir.resolve("cover-out");
        EpubParser.EpubMeta meta = new EpubParser().parse(epub, coverDir);

        assertThat(meta.coverPath()).isNotNull();
        assertThat(meta.coverPath().getFileName().toString()).isEqualTo("cover.jpg");
        assertThat(Files.size(meta.coverPath())).isEqualTo(4);
    }

    @Test
    void missingCoverYieldsNullPath() throws Exception {
        // properties 置空且无 meta[name=cover]：仅 id 含 cover 也命中（id=cover-image），改成无关 id
        Path epub = tempDir.resolve("no-cover.epub");
        try (JarOutputStream zip = new JarOutputStream(new FileOutputStream(epub.toFile()))) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml", CONTAINER_XML);
            put(zip, "OEBPS/content.opf", OPF.replace(" properties=\"cover-image\"", "")
                    .replace("id=\"cover-image\"", "id=\"img1\""));
        }
        EpubParser.EpubMeta meta = new EpubParser().parse(epub, tempDir.resolve("out2"));

        assertThat(meta.coverPath()).isNull();
    }
}
