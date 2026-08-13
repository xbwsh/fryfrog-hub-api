package com.fryfrog.hub.controller;

import com.fryfrog.hub.common.dto.*;
import com.fryfrog.hub.common.exception.BadRequestException;
import com.fryfrog.hub.common.exception.ForbiddenException;
import com.fryfrog.hub.common.model.User;
import com.fryfrog.hub.common.service.UserService;
import com.fryfrog.hub.config.AuthInterceptor;
import com.fryfrog.hub.config.AuthManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "用户管理", description = "多用户账号管理（管理员操作）")
public class UserController {

    private final UserService userService;
    private final AuthManager authManager;

    public UserController(UserService userService, AuthManager authManager) {
        this.userService = userService;
        this.authManager = authManager;
    }

    @GetMapping
    @Operation(summary = "用户列表", description = "仅管理员")
    public ResponseEntity<ApiResponse<List<UserDTO>>> list(HttpServletRequest request) {
        requireAdmin(request);
        List<UserDTO> users = userService.findAll().stream().map(UserDTO::from).toList();
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    @GetMapping("/me")
    @Operation(summary = "当前用户信息")
    public ResponseEntity<ApiResponse<UserDTO>> me(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.success(UserDTO.from(userService.getUser(currentUserId(request)))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "用户详情", description = "管理员或本人")
    public ResponseEntity<ApiResponse<UserDTO>> get(@PathVariable Long id, HttpServletRequest request) {
        requireAdminOrSelf(id, request);
        return ResponseEntity.ok(ApiResponse.success(UserDTO.from(userService.getUser(id))));
    }

    @PostMapping
    @Operation(summary = "创建用户", description = "仅管理员")
    public ResponseEntity<ApiResponse<UserDTO>> create(@RequestBody UserCreateRequest req, HttpServletRequest request) {
        requireAdmin(request);
        User user = userService.createUser(
                req.getUsername(), req.getPassword(), req.getNickname(), parseRole(req.getRole()));
        return ResponseEntity.ok(ApiResponse.success("用户已创建", UserDTO.from(user)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新用户", description = "管理员可改全部字段，普通用户仅能改自己昵称/头像")
    public ResponseEntity<ApiResponse<UserDTO>> update(@PathVariable Long id,
                                                       @RequestBody UserUpdateRequest req,
                                                       HttpServletRequest request) {
        Long currentId = currentUserId(request);
        requireAdminOrSelf(id, request);

        if (!userService.isAdmin(currentId) && (req.getRole() != null || req.getEnabled() != null)) {
            throw new ForbiddenException("无权修改角色或启用状态");
        }

        boolean roleChanged = req.getRole() != null && !parseRole(req.getRole()).equals(userService.getUser(id).getRole());
        boolean statusChanged = req.getEnabled() != null && !req.getEnabled().equals(userService.getUser(id).getEnabled());

        User user = userService.updateUser(id, req.getNickname(), req.getAvatar(), parseRole(req.getRole()), req.getEnabled());

        if (roleChanged || statusChanged) {
            invalidateTokensFor(id);
        }
        return ResponseEntity.ok(ApiResponse.success("用户已更新", UserDTO.from(user)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户", description = "仅管理员，不能删除自己")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, HttpServletRequest request) {
        requireAdmin(request);
        Long currentId = currentUserId(request);
        if (currentId != null && currentId.equals(id)) {
            throw new BadRequestException("不能删除当前登录账号");
        }
        userService.deleteUser(id);
        invalidateTokensFor(id);
        return ResponseEntity.ok(ApiResponse.success("用户已删除", null));
    }

    @PutMapping("/me/password")
    @Operation(summary = "修改自己的密码", description = "需提供原密码")
    public ResponseEntity<ApiResponse<Void>> changeMyPassword(@RequestBody ChangePasswordRequest req,
                                                              HttpServletRequest request) {
        userService.changePassword(currentUserId(request), req.getOldPassword(), req.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success("密码已修改，请重新登录", null));
    }

    @PutMapping("/{id}/password")
    @Operation(summary = "重置指定用户密码", description = "仅管理员，无需原密码")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@PathVariable Long id,
                                                           @RequestBody ChangePasswordRequest req,
                                                           HttpServletRequest request) {
        requireAdmin(request);
        userService.resetPassword(id, req.getNewPassword());
        invalidateTokensFor(id);
        return ResponseEntity.ok(ApiResponse.success("密码已重置", null));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.USER_ID_ATTR);
        return value instanceof Long id ? id : null;
    }

    private void requireAdmin(HttpServletRequest request) {
        Long id = currentUserId(request);
        if (id == null || !userService.isAdmin(id)) {
            throw new ForbiddenException("需要管理员权限");
        }
    }

    private void requireAdminOrSelf(Long targetId, HttpServletRequest request) {
        Long id = currentUserId(request);
        if (id == null || (!id.equals(targetId) && !userService.isAdmin(id))) {
            throw new ForbiddenException("无权访问该用户");
        }
    }

    private void invalidateTokensFor(Long userId) {
        if (userId != null) {
            authManager.invalidateUserTokens(userId);
        }
    }

    private User.Role parseRole(String role) {
        if (role == null || role.isBlank()) return null;
        try {
            return User.Role.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("无效角色: " + role);
        }
    }
}