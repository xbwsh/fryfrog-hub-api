package com.fryfrog.hub.common.service;

import com.fryfrog.hub.common.exception.BadRequestException;
import com.fryfrog.hub.common.exception.ResourceNotFoundException;
import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class UserService {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final String USERNAME_PATTERN = "^[a-zA-Z0-9_]{3,64}$";
    private static final String DEFAULT_NICKNAME = "用户";

    private final UserRepository repository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public List<User> findAll() {
        return repository.findAll();
    }

    public User getUser(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    public Optional<User> findByUsername(String username) {
        return repository.findByUsername(username);
    }

    @Transactional
    public User createUser(String username, String rawPassword, String nickname, User.Role role) {
        validateUsername(username);
        validatePassword(rawPassword);

        if (repository.existsByUsername(username)) {
            throw new BadRequestException("用户名已存在: " + username);
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(rawPassword));
        user.setSubsonicPassword(rawPassword);
        user.setNickname(nickname == null || nickname.isBlank() ? DEFAULT_NICKNAME : nickname);
        user.setRole(role != null ? role : User.Role.USER);
        user.setEnabled(true);
        repository.save(user);
        log.info("Created user: {} (role={})", username, user.getRole());
        return user;
    }

    @Transactional
    public User updateUser(Long id, String nickname, String avatar, User.Role role, Boolean enabled) {
        User user = getUser(id);

        if (nickname != null) {
            user.setNickname(nickname);
        }
        if (avatar != null) {
            user.setAvatar(avatar);
        }
        if (role != null && user.getRole() != role) {
            user.setRole(role);
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        return repository.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = getUser(id);
        repository.delete(user);
        log.info("Deleted user: {} (id={})", user.getUsername(), id);
    }

    @Transactional
    public void changePassword(Long id, String oldPassword, String newPassword) {
        User user = getUser(id);
        if (oldPassword == null || !encoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BadRequestException("原密码不正确");
        }
        setPassword(user, newPassword);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        User user = getUser(id);
        setPassword(user, newPassword);
    }

    @Transactional
    public void updateLastLogin(Long id, String ip) {
        repository.findById(id).ifPresent(user -> {
            user.setLastLoginAt(LocalDateTime.now());
            user.setLastLoginIp(ip);
            repository.save(user);
        });
    }

    public boolean verifyPassword(User user, String rawPassword) {
        return rawPassword != null && encoder.matches(rawPassword, user.getPasswordHash());
    }

    public boolean isAdmin(Long userId) {
        if (userId == null) return false;
        return repository.findById(userId)
                .map(User::isAdmin)
                .orElse(false);
    }

    public boolean hasUsers() {
        return repository.count() > 0;
    }

    @Transactional
    public User createInitialAdmin(String rawPassword) {
        if (repository.existsByUsername("admin")) {
            throw new BadRequestException("管理员账号已存在");
        }
        User user = new User();
        user.setUsername("admin");
        user.setPasswordHash(encoder.encode(rawPassword == null ? "" : rawPassword));
        user.setSubsonicPassword(rawPassword == null ? "" : rawPassword);
        user.setNickname("管理员");
        user.setRole(User.Role.ADMIN);
        user.setEnabled(true);
        repository.save(user);
        log.info("Created initial admin user");
        return user;
    }

    private void setPassword(User user, String newPassword) {
        validatePassword(newPassword);
        user.setPasswordHash(encoder.encode(newPassword));
        user.setSubsonicPassword(newPassword);
        repository.save(user);
        log.info("Password changed for user: {}", user.getUsername());
    }

    private void validateUsername(String username) {
        if (username == null || !username.matches(USERNAME_PATTERN)) {
            throw new BadRequestException("用户名必须为 3-64 位的字母、数字或下划线");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }
}