package com.fryfrog.hub.common.service;

import com.fryfrog.hub.common.model.SystemSetting;
import com.fryfrog.hub.common.repository.SystemSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SystemSettingService {

    private final SystemSettingRepository repository;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public SystemSettingService(SystemSettingRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        List<SystemSetting> settings = repository.findAll();
        for (SystemSetting setting : settings) {
            cache.put(setting.getKey(), setting.getValue());
        }
        log.info("Loaded {} system settings from database", settings.size());
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
            return Integer.parseInt(value);
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
