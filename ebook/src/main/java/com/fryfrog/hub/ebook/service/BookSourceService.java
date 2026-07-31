package com.fryfrog.hub.ebook.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.ebook.dto.BookSourceDTO;
import com.fryfrog.hub.ebook.model.BookSource;
import com.fryfrog.hub.ebook.repository.BookSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookSourceService {

    private final BookSourceRepository repository;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {};

    @Transactional(readOnly = true)
    public List<BookSourceDTO> findAll() {
        return repository.findByDeletedFalseOrderBySortOrderAsc().stream()
                .map(BookSourceDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<BookSourceDTO> findEnabled() {
        return repository.findByEnabledTrueAndDeletedFalseOrderBySortOrderAsc().stream()
                .map(BookSourceDTO::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public BookSourceDTO findById(Long id) {
        BookSource source = repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("书源", "id", id));
        return BookSourceDTO.fromEntity(source);
    }

    @Transactional
    public BookSourceDTO create(BookSourceDTO dto) {
        if (repository.existsByUrlAndDeletedFalse(dto.getUrl())) {
            throw new IllegalArgumentException("书源URL已存在: " + dto.getUrl());
        }
        BookSource entity = dto.toEntity();
        BookSource saved = repository.save(entity);
        log.info("创建书源: {} ({})", saved.getName(), saved.getUrl());
        return BookSourceDTO.fromEntity(saved);
    }

    @Transactional
    public BookSourceDTO update(Long id, BookSourceDTO dto) {
        BookSource existing = repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("书源", "id", id));

        existing.setName(dto.getName());
        existing.setAuthor(dto.getAuthor());
        existing.setVersion(dto.getVersion());
        existing.setUrl(dto.getUrl());
        existing.setRuleJson(dto.getRuleJson());
        existing.setCleanRuleJson(dto.getCleanRuleJson());
        existing.setEnabled(dto.getEnabled());
        existing.setGroup(dto.getGroup());
        existing.setSourceType(dto.getSourceType());
        existing.setHeaderJson(dto.getHeaderJson());
        existing.setSortOrder(dto.getSortOrder());
        existing.setDescription(dto.getDescription());

        BookSource saved = repository.save(existing);
        log.info("更新书源: {} ({})", saved.getName(), saved.getUrl());
        return BookSourceDTO.fromEntity(saved);
    }

    @Transactional
    public void delete(Long id) {
        BookSource source = repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("书源", "id", id));
        source.setDeleted(true);
        repository.save(source);
        log.info("删除书源: {} ({})", source.getName(), source.getUrl());
    }

    @Transactional
    public BookSourceDTO toggleEnabled(Long id, boolean enabled) {
        BookSource source = repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("书源", "id", id));
        source.setEnabled(enabled);
        BookSource saved = repository.save(source);
        log.info("书源 {} 已{}", saved.getName(), enabled ? "启用" : "禁用");
        return BookSourceDTO.fromEntity(saved);
    }

    @Transactional
    public List<BookSourceDTO> importFromUrl(String url) {
        try {
            String json = webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return importFromJson(json);
        } catch (Exception e) {
            log.error("从URL导入书源失败: {}", url, e);
            throw new RuntimeException("导入书源失败: " + e.getMessage(), e);
        }
    }

    @Transactional
    public List<BookSourceDTO> importFromJson(String json) {
        List<BookSourceDTO> imported = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);

            List<Map<String, Object>> sourceList;
            if (root.isArray()) {
                sourceList = objectMapper.convertValue(root, LIST_TYPE);
            } else if (root.isObject()) {
                sourceList = List.of(objectMapper.convertValue(root, Map.class));
            } else {
                throw new IllegalArgumentException("无效的书源JSON格式");
            }

            for (Map<String, Object> sourceMap : sourceList) {
                try {
                    BookSourceDTO dto = parseBookSource(sourceMap);
                    if (dto != null && !repository.existsByUrlAndDeletedFalse(dto.getUrl())) {
                        BookSource saved = repository.save(dto.toEntity());
                        imported.add(BookSourceDTO.fromEntity(saved));
                        log.info("导入书源: {} ({})", saved.getName(), saved.getUrl());
                    }
                } catch (Exception e) {
                    log.warn("解析单个书源失败: {}", e.getMessage());
                }
            }

            log.info("成功导入 {} 个书源", imported.size());
            return imported;
        } catch (Exception e) {
            log.error("解析书源JSON失败", e);
            throw new RuntimeException("解析书源JSON失败: " + e.getMessage(), e);
        }
    }

    private BookSourceDTO parseBookSource(Map<String, Object> map) {
        String name = getStringValue(map, "bookSourceName");
        String url = getStringValue(map, "bookSourceUrl");

        if (name == null || name.isEmpty() || url == null || url.isEmpty()) {
            return null;
        }

        String ruleJson;
        try {
            ruleJson = objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }

        return BookSourceDTO.builder()
                .name(name)
                .author(getStringValue(map, "bookSourceAuthor"))
                .version(getStringValue(map, "bookSourceVersion"))
                .url(url)
                .ruleJson(ruleJson)
                .cleanRuleJson(null)
                .enabled(getBooleanValue(map, "enabled", true))
                .group(getStringValue(map, "bookSourceGroup"))
                .sourceType(getStringValue(map, "bookSourceType"))
                .headerJson(getStringValue(map, "header"))
                .sortOrder(0)
                .description(getStringValue(map, "bookSourceComment"))
                .build();
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private boolean getBooleanValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    @Transactional(readOnly = true)
    public String exportToJson(Long id) {
        BookSource source = repository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("书源", "id", id));

        try {
            Map<String, Object> map = objectMapper.readValue(source.getRuleJson(), Map.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);
        } catch (Exception e) {
            log.error("导出书源JSON失败: {}", source.getName(), e);
            throw new RuntimeException("导出书源失败: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public List<String> findDistinctGroups() {
        return repository.findDistinctGroups();
    }

    @Transactional(readOnly = true)
    public long countEnabled() {
        return repository.countEnabled();
    }
}
