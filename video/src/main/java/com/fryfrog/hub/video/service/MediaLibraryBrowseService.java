package com.fryfrog.hub.video.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务器目录浏览：用于前端媒体库目录选择器，返回磁盘根目录与子目录列表。
 */
@Service
@Slf4j
public class MediaLibraryBrowseService {

    /** 列出所有磁盘根目录（Docker 环境优先暴露媒体挂载路径） */
    public List<Map<String, Object>> listRoots() {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> added = new HashSet<>();

        String[] mediaPaths = {
                "/data/media/video", "/data/media", "/data"
        };
        for (String p : mediaPaths) {
            File dir = new File(p);
            if (dir.exists() && dir.isDirectory() && added.add(dir.getAbsolutePath())) {
                result.add(item(p, dir.getAbsolutePath(), dir.canWrite()));
            }
        }

        for (File root : File.listRoots()) {
            String rootPath = root.getAbsolutePath();
            if (added.add(rootPath)) {
                result.add(item(rootPath, rootPath, root.canWrite()));
            }
        }

        return result;
    }

    /** 列出指定路径下的子目录，非目录返回空列表 */
    public List<Map<String, Object>> listChildren(String path) {
        if (path == null || path.isBlank()) {
            return listRoots();
        }
        Path dirPath = Paths.get(path);
        if (!Files.isDirectory(dirPath)) {
            return List.of();
        }
        return listChildren(dirPath);
    }

    private List<Map<String, Object>> listChildren(Path dir) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) continue;
                result.add(item(name, child.toAbsolutePath().toString(), Files.isWritable(child)));
            }
        } catch (IOException e) {
            log.warn("Failed to list directory: {}", dir, e);
            return List.of();
        }
        result.sort((a, b) -> ((String) a.get("name")).compareToIgnoreCase((String) b.get("name")));
        return result;
    }

    private Map<String, Object> item(String name, String path, boolean writable) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("path", path);
        item.put("writable", writable);
        return item;
    }
}