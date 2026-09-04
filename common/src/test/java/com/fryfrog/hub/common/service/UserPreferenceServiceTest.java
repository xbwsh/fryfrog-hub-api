package com.fryfrog.hub.common.service;

import com.fryfrog.hub.common.model.UserPreference;
import com.fryfrog.hub.common.repository.UserPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPreferenceServiceTest {

    @Mock
    private UserPreferenceRepository repository;

    @InjectMocks
    private UserPreferenceService service;

    private final Map<Long, Map<String, String>> store = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        when(repository.findByUserId(anyLong())).thenAnswer(inv -> {
            Long userId = inv.getArgument(0);
            Map<String, String> m = store.get(userId);
            if (m == null) {
                return List.of();
            }
            List<UserPreference> list = new ArrayList<>();
            m.forEach((k, v) -> list.add(UserPreference.builder().userId(userId).prefKey(k).prefValue(v).build()));
            return list;
        });
        when(repository.save(any(UserPreference.class))).thenAnswer(inv -> {
            UserPreference p = inv.getArgument(0);
            store.computeIfAbsent(p.getUserId(), k -> new ConcurrentHashMap<>()).put(p.getPrefKey(), p.getPrefValue());
            return p;
        });
        doAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return null;
        }).when(repository).deleteByUserId(anyLong());
    }

    @Test
    @DisplayName("全量替换：delete 后必须立即 flush（防 Hibernate INSERT 先于 DELETE 撞唯一键）")
    void setPreferences_flushesDeleteBeforeInsert() {
        // 回归：Hibernate ActionQueue flush 时 INSERT 先于 DELETE 执行，
        // delete 后不 flush 直接 save 新行会违反 (user_id, pref_key) 唯一键
        doAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return null;
        }).when(repository).deleteByUserId(1L);
        doNothing().when(repository).flush();

        service.setPreferences(1L, Map.of("theme.mode", "light"));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(repository);
        inOrder.verify(repository).deleteByUserId(1L);
        inOrder.verify(repository).flush();
        inOrder.verify(repository, org.mockito.Mockito.atLeastOnce()).save(any(UserPreference.class));
    }

    @Test
    @DisplayName("获取偏好：按 userId 聚合为 map")
    void getPreferences_aggregates() {
        store.put(7L, Map.of("theme.mode", "dark", "privacy.enabled", "true"));

        Map<String, String> result = service.getPreferences(7L);

        assertThat(result).containsEntry("theme.mode", "dark").containsEntry("privacy.enabled", "true");
    }

    @Test
    @DisplayName("获取偏好：userId 为空返回空 map")
    void getPreferences_nullUser_returnsEmpty() {
        assertThat(service.getPreferences(null)).isEmpty();
    }

    @Test
    @DisplayName("全量替换：删除旧键、保存新值、空值视为删除")
    void setPreferences_replacesAll() {
        store.put(7L, Map.of("old.key", "x"));

        Map<String, String> result = service.setPreferences(7L, Map.of(
                "theme.mode", "dark",
                "privacy.enabled", "",
                "player.engine", "mpv"));

        verify(repository).deleteByUserId(7L);
        verify(repository, times(2)).save(any(UserPreference.class));
        assertThat(result).containsEntry("theme.mode", "dark")
                .containsEntry("player.engine", "mpv")
                .doesNotContainKey("old.key")
                .doesNotContainKey("privacy.enabled");
    }

    @Test
    @DisplayName("全量替换：userId 为空抛出异常")
    void setPreferences_nullUser_throws() {
        assertThatThrownBy(() -> service.setPreferences(null, Map.of("k", "v")))
                .isInstanceOf(com.fryfrog.hub.common.exception.BadRequestException.class);
    }
}
