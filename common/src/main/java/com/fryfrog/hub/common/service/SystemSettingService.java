package com.fryfrog.hub.common.service;

import com.fryfrog.hub.common.model.SystemSetting;
import com.fryfrog.hub.common.repository.SystemSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Map.entry;

@Service
@Slf4j
public class SystemSettingService {

    private final SystemSettingRepository repository;
    private final Environment environment;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public SystemSettingService(SystemSettingRepository repository, Environment environment,
                                TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.environment = environment;
        this.transactionTemplate = transactionTemplate;
    }

    private static final Map<String, String> KEY_MIGRATION = Map.ofEntries(
            entry("tmdb.api-key", "hub.tmdb.api-key")
    );

    private static final Map<String, String> DEFAULT_SETTINGS = Map.ofEntries(
            // TMDB
            entry("tmdb.api-key", ""),
            // Scrape (全局开关)
            entry("scrape.auto-scrape", "true"),
            // Watcher
            entry("watcher.periodic-scan", "true"),
            entry("watcher.periodic-scan-interval", "30"),
            // Hanime
            entry("hanime.cf-bypass-url", "http://localhost:8000"),
            entry("hanime.use-proxy", "false"),
            entry("hanime.scraper.request-interval", "1500"),
            entry("hanime.scraper.max-retries", "3"),
            entry("hanime.scraper.timeout", "30"),
            entry("hanime.scraper.cache-ttl", "60"),
            entry("hanime.scraper.cache-max-size", "1000")
    );

    @PostConstruct
    public void init() {
        migrateKeys();
        seedDefaults();
        List<SystemSetting> settings = repository.findAll();
        for (SystemSetting setting : settings) {
            cache.put(setting.getKey(), setting.getValue());
        }
        log.info("Loaded {} system settings from database", settings.size());
    }

    private void seedDefaults() {
        if (repository.count() > 0) return;

        transactionTemplate.executeWithoutResult(status -> {
            int seeded = 0;
            for (Map.Entry<String, String> entry : DEFAULT_SETTINGS.entrySet()) {
                String key = entry.getKey();
                if (repository.findByKey(key).isEmpty()) {
                    SystemSetting setting = new SystemSetting();
                    setting.setKey(key);
                    setting.setValue(entry.getValue());
                    repository.save(setting);
                    seeded++;
                }
            }
            if (seeded > 0) {
                log.info("Seeded {} default system settings", seeded);
            }
        });
    }

    private void migrateKeys() {
        boolean hasOldKeys = KEY_MIGRATION.keySet().stream()
                .anyMatch(key -> repository.findByKey(key).isPresent());
        if (!hasOldKeys) return;

        transactionTemplate.executeWithoutResult(status -> {
            int migrated = 0;
            for (Map.Entry<String, String> entry : KEY_MIGRATION.entrySet()) {
                String oldKey = entry.getKey();
                String newKey = entry.getValue();
                Optional<SystemSetting> oldSetting = repository.findByKey(oldKey);
                if (oldSetting.isEmpty()) {
                    continue;
                }
                Optional<SystemSetting> newSetting = repository.findByKey(newKey);
                if (newSetting.isPresent()) {
                    repository.delete(oldSetting.get());
                } else {
                    SystemSetting setting = oldSetting.get();
                    setting.setKey(newKey);
                    repository.save(setting);
                }
                migrated++;
            }
            if (migrated > 0) {
                log.info("Migrated {} system settings to hub.* key format", migrated);
            }
        });
    }

    public String getValue(String key, String defaultValue) {
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<SystemSetting> setting = repository.findByKey(key);
        if (setting.isPresent()) {
            cache.put(key, setting.get().getValue());
            return setting.get().getValue();
        }
        String springValue = environment.getProperty(key);
        if (springValue != null) {
            cache.put(key, springValue);
            return springValue;
        }
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getValue(key, null);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    public double getDouble(String key, double defaultValue) {
        String value = getValue(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public int getInteger(String key, int defaultValue) {
        String value = getValue(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public SystemSetting setValue(String key, String value, String description) {
        Optional<SystemSetting> existing = repository.findByKey(key);
        SystemSetting setting;
        if (existing.isPresent()) {
            setting = existing.get();
            setting.setValue(value);
            if (description != null) {
                setting.setDescription(description);
            }
        } else {
            setting = new SystemSetting();
            setting.setKey(key);
            setting.setValue(value);
            setting.setDescription(description);
        }
        repository.save(setting);
        cache.put(key, value);
        log.info("Updated system setting: {} = {}", key, value);
        return setting;
    }

    public List<SystemSetting> getAll() {
        return repository.findAll();
    }

    public Optional<SystemSetting> getByKey(String key) {
        return repository.findByKey(key);
    }
}
