package com.fryfrog.hub.common.service;

import com.fryfrog.hub.common.exception.BadRequestException;
import com.fryfrog.hub.common.model.UserPreference;
import com.fryfrog.hub.common.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户偏好设置：按账号存储的 key-value，登录用户读写自己的一份，
 * 用于主题/隐私/播放器等「我的」页设置的多端同步与账号隔离。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserPreferenceService {

    private static final int MAX_KEYS_PER_USER = 100;

    private final UserPreferenceRepository repository;

    public Map<String, String> getPreferences(Long userId) {
        if (userId == null) {
            return Map.of();
        }
        return repository.findByUserId(userId).stream()
                .collect(Collectors.toMap(
                        UserPreference::getPrefKey,
                        p -> p.getPrefValue() == null ? "" : p.getPrefValue(),
                        (a, b) -> b,
                        LinkedHashMap::new));
    }

    public String getPreference(Long userId, String key) {
        if (userId == null || key == null) {
            return null;
        }
        return repository.findByUserIdAndPrefKey(userId, key)
                .map(UserPreference::getPrefValue)
                .orElse(null);
    }

    /** 全量替换某用户偏好：value 为 null 或空串的键视为删除。 */
    @Transactional
    public Map<String, String> setPreferences(Long userId, Map<String, String> preferences) {
        if (userId == null) {
            throw new BadRequestException("当前请求未关联用户");
        }
        Map<String, String> normalized = preferences == null
                ? Map.of()
                : preferences.entrySet().stream()
                        .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue() == null ? "" : e.getValue(),
                                (a, b) -> b,
                                LinkedHashMap::new));

        if (normalized.size() > MAX_KEYS_PER_USER) {
            throw new BadRequestException("偏好项过多（上限 " + MAX_KEYS_PER_USER + "）");
        }

        repository.deleteByUserId(userId);
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue; // 空值 = 删除该键
            }
            repository.save(UserPreference.builder()
                    .userId(userId)
                    .prefKey(entry.getKey())
                    .prefValue(entry.getValue())
                    .build());
        }
        log.debug("Saved {} user preferences for user {}", normalized.size(), userId);
        return getPreferences(userId);
    }
}