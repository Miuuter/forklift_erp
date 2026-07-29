package com.example.forklift_erp.service;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.JobTag;
import com.example.forklift_erp.constant.RoleNames;
import com.example.forklift_erp.controller.AuthController;
import com.example.forklift_erp.entity.Role;
import com.example.forklift_erp.entity.User;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.RoleRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.UserRepository;
import com.example.forklift_erp.security.JwtTokenProvider;
import com.example.forklift_erp.security.PermissionCodes;
import com.example.forklift_erp.security.PermissionService;
import com.example.forklift_erp.util.ListPageSupport;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final PermissionService permissionService;
    private final OperationAuditService operationAuditService;
    private final CollaborationService collaborationService;
    private final RepairRecordRepository repairRecordRepository;

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            PermissionService permissionService,
            OperationAuditService operationAuditService,
            CollaborationService collaborationService,
            RepairRecordRepository repairRecordRepository
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.permissionService = permissionService;
        this.operationAuditService = operationAuditService;
        this.collaborationService = collaborationService;
        this.repairRecordRepository = repairRecordRepository;
    }

    @Transactional(readOnly = true)
    public AuthController.TokenResponse login(AuthController.LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!user.isEnabled()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号已停用");
        }

        AuthController.TokenResponse response = new AuthController.TokenResponse();
        response.setToken(jwtTokenProvider.createToken(user.getUsername()));
        response.setUsername(user.getUsername());
        response.setJobTag(normalizeJobTag(user));
        response.setRoles(user.getRoles().stream()
                .map(Role::getName)
                .sorted()
                .toList());
        response.setPermissions(permissionService.findPermissionCodes(user).stream().sorted().toList());
        return response;
    }

    @Transactional(readOnly = true)
    public Object listUsers(boolean paged, String keyword, Integer page, Integer size, Authentication authentication) {
        boolean canManagePrivilegedUsers = permissionService.hasPermission(authentication, PermissionCodes.USER_ADMIN);

        if (paged) {
            int normalizedPage = ListPageSupport.page(page);
            int normalizedSize = ListPageSupport.size(size);
            var result = userRepository.searchPage(
                    normalizeKeyword(keyword),
                    canManagePrivilegedUsers,
                    ListPageSupport.pageRequest(page, size, Sort.by(Sort.Direction.DESC, "id"))
            );
            return PageResult.of(
                    result.getContent().stream()
                            .map(this::toSummary)
                            .toList(),
                    normalizedPage,
                    normalizedSize,
                    result.getTotalElements()
            );
        }

        return userRepository.findAll().stream()
                .filter(user -> canManagePrivilegedUsers
                        || user.getRoles().stream().allMatch(role -> RoleNames.isStandardUser(role.getName())))
                .sorted(Comparator.comparing(User::getId).reversed())
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuthController.UserSummaryResponse> listRepairUsers() {
        return userRepository.findAll().stream()
                .filter(User::isEnabled)
                .filter(user -> JobTag.REPAIR.code().equals(normalizeJobTag(user)))
                .sorted(Comparator.comparing(User::getId).reversed())
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public void register(AuthController.RegisterRequest request, Authentication authentication) {
        boolean canManagePrivilegedUsers = permissionService.hasPermission(authentication, PermissionCodes.USER_ADMIN);
        String username = request.getUsername().trim();

        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ResultCode.DATA_DUPLICATE, "用户名已存在");
        }

        Set<Role> assignRoles = new HashSet<>();
        if (request.getRoles() != null && !request.getRoles().isEmpty()) {
            for (String roleName : request.getRoles()) {
                if (RoleNames.isSuperAdmin(roleName)) {
                    throw new BusinessException(ResultCode.FORBIDDEN, "不允许创建超级管理员");
                }
                if (RoleNames.isAdmin(roleName) && !canManagePrivilegedUsers) {
                    throw new BusinessException(ResultCode.FORBIDDEN, "当前权限不允许创建管理员");
                }
                if (!RoleNames.isAdmin(roleName) && !RoleNames.isStandardUser(roleName)) {
                    throw new BusinessException(ResultCode.PARAM_ERROR, "无效角色: " + roleName);
                }
                Role role = roleRepository.findByName(roleName)
                        .orElseThrow(() -> new BusinessException(ResultCode.PARAM_ERROR, "无效角色: " + roleName));
                assignRoles.add(role);
            }
        } else {
            Role userRole = roleRepository.findByName(RoleNames.USER)
                    .orElseThrow(() -> new BusinessException(ResultCode.SYSTEM_ERROR, "默认角色 USER 不存在"));
            assignRoles.add(userRole);
        }

        User newUser = new User();
        newUser.setUsername(username);
        newUser.setPassword(passwordEncoder.encode(request.getPassword()));
        newUser.setRoles(assignRoles);
        newUser.setJobTag(normalizeJobTag(request.getJobTag(), assignRoles));
        newUser.setEnabled(true);
        collaborationService.stampWrite(newUser);

        User savedUser = userRepository.save(newUser);
        operationAuditService.record("用户管理", "CREATE", RoleNames.USER, savedUser.getId(),
                savedUser.getUsername(), roleNames(savedUser), "创建用户", null, null, RoleNames.USER, savedUser.getId());
    }

    @Transactional
    public void resetPassword(AuthController.ResetPasswordRequest request, Authentication authentication) {
        User targetUser = userRepository.findByUsernameForUpdate(request.getTargetUsername())
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "目标用户不存在"));

        boolean targetIsPrivileged = isPrivileged(targetUser);
        boolean canManagePrivilegedUsers = permissionService.hasPermission(authentication, PermissionCodes.USER_ADMIN);

        if (targetIsPrivileged && !canManagePrivilegedUsers) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前权限不允许重置管理员密码");
        }

        collaborationService.validateWrite(targetUser, request.getVersion());
        targetUser.setPassword(passwordEncoder.encode(request.getNewPassword()));
        collaborationService.stampWrite(targetUser);
        User savedUser = userRepository.save(targetUser);
        operationAuditService.record("用户管理", "PASSWORD_RESET", RoleNames.USER, savedUser.getId(),
                savedUser.getUsername(), roleNames(savedUser), "重置用户密码", null, null, RoleNames.USER, savedUser.getId());
    }

    @Transactional
    public void updateUsername(Long id, AuthController.UpdateUsernameRequest request) {
        User targetUser = findUserByIdForUpdate(id);
        rejectSuperAdminTarget(targetUser, "超级管理员账号不允许修改用户名");

        collaborationService.validateWrite(targetUser, request.getVersion());
        String newUsername = request.getUsername().trim();
        if (userRepository.existsByUsernameAndIdNot(newUsername, id)) {
            throw new BusinessException(ResultCode.DATA_DUPLICATE, "用户名已存在");
        }

        String oldUsername = targetUser.getUsername();
        targetUser.setUsername(newUsername);
        collaborationService.stampWrite(targetUser);
        User savedUser = userRepository.save(targetUser);
        operationAuditService.record("用户管理", "USERNAME_UPDATE", RoleNames.USER, savedUser.getId(),
                savedUser.getUsername(), roleNames(savedUser), oldUsername + " -> " + savedUser.getUsername(),
                null, null, RoleNames.USER, savedUser.getId());
    }

    @Transactional
    public void updateUserPassword(Long id, AuthController.UpdateUserPasswordRequest request) {
        User targetUser = findUserByIdForUpdate(id);
        rejectSuperAdminTarget(targetUser, "超级管理员账号不允许修改密码");

        collaborationService.validateWrite(targetUser, request.getVersion());
        targetUser.setPassword(passwordEncoder.encode(request.getPassword()));
        collaborationService.stampWrite(targetUser);
        User savedUser = userRepository.save(targetUser);
        operationAuditService.record("用户管理", "PASSWORD_UPDATE", RoleNames.USER, savedUser.getId(),
                savedUser.getUsername(), roleNames(savedUser), "修改用户密码", null, null, RoleNames.USER, savedUser.getId());
    }

    @Transactional
    public AuthController.UserSummaryResponse updateUserJobTag(
            Long id,
            AuthController.UpdateJobTagRequest request,
            Authentication authentication
    ) {
        User targetUser = findUserByIdForUpdate(id);
        boolean targetIsPrivileged = isPrivileged(targetUser);
        boolean canManagePrivilegedUsers = permissionService.hasPermission(authentication, PermissionCodes.USER_ADMIN);

        if (targetIsPrivileged && !canManagePrivilegedUsers) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前权限不允许修改管理员职务标签");
        }

        collaborationService.validateWrite(targetUser, request.getVersion());
        String oldTag = normalizeJobTag(targetUser);
        targetUser.setJobTag(normalizeJobTag(request.getJobTag(), targetUser.getRoles()));
        collaborationService.stampWrite(targetUser);
        User savedUser = userRepository.save(targetUser);
        operationAuditService.record("用户管理", "JOB_TAG_UPDATE", RoleNames.USER, savedUser.getId(),
                savedUser.getUsername(), roleNames(savedUser), oldTag + " -> " + normalizeJobTag(savedUser),
                null, null, RoleNames.USER, savedUser.getId());
        return toSummary(savedUser);
    }

    @Transactional
    public AuthController.UserSummaryResponse updateUserEnabled(
            Long id,
            AuthController.UpdateEnabledRequest request,
            Authentication authentication
    ) {
        User targetUser = findUserByIdForUpdate(id);
        rejectSuperAdminTarget(targetUser, "超级管理员账号不允许停用");
        boolean targetIsPrivileged = isPrivileged(targetUser);
        boolean canManagePrivilegedUsers = permissionService.hasPermission(authentication, PermissionCodes.USER_ADMIN);

        if (targetUser.getUsername().equals(authentication.getName())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能停用当前登录账号");
        }
        if (targetIsPrivileged && !canManagePrivilegedUsers) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前权限不允许修改管理员启用状态");
        }
        if (request.getEnabled() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "启用状态不能为空");
        }

        collaborationService.validateWrite(targetUser, request.getVersion());
        boolean oldEnabled = targetUser.isEnabled();
        targetUser.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        collaborationService.stampWrite(targetUser);
        User savedUser = userRepository.save(targetUser);
        operationAuditService.record("用户管理", savedUser.isEnabled() ? "ENABLE" : "DISABLE", RoleNames.USER, savedUser.getId(),
                savedUser.getUsername(), roleNames(savedUser), oldEnabled + " -> " + savedUser.isEnabled(),
                null, null, RoleNames.USER, savedUser.getId());
        return toSummary(savedUser);
    }

    @Transactional
    public void deleteUser(Long id, Long version, Authentication authentication) {
        User targetUser = findUserByIdForUpdate(id);

        if (targetUser.getUsername().equals(authentication.getName())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能删除当前登录账号");
        }
        rejectSuperAdminTarget(targetUser, "超级管理员账号不允许删除");

        collaborationService.validateWrite(targetUser, version);
        if (repairRecordRepository.existsByRepairPersonUserId(targetUser.getId())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "User has repair history and cannot be deleted; disable the account instead");
        }
        operationAuditService.record("用户管理", "DELETE", RoleNames.USER, targetUser.getId(),
                targetUser.getUsername(), roleNames(targetUser), "删除用户", null, null, RoleNames.USER, targetUser.getId());
        userRepository.delete(targetUser);
    }

    private AuthController.UserSummaryResponse toSummary(User user) {
        return AuthController.UserSummaryResponse.fromEntity(user, permissionService.findPermissionCodes(user));
    }

    private User findUserByIdForUpdate(Long id) {
        return userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "用户不存在"));
    }

    private void rejectSuperAdminTarget(User user, String message) {
        if (user.getRoles().stream().anyMatch(role -> RoleNames.isSuperAdmin(role.getName()))) {
            throw new BusinessException(ResultCode.FORBIDDEN, message);
        }
    }

    private boolean isPrivileged(User user) {
        return user.getRoles().stream()
                .anyMatch(role -> RoleNames.isPrivileged(role.getName()));
    }

    private static String normalizeJobTag(User user) {
        return normalizeJobTag(user.getJobTag(), user.getRoles());
    }

    private static String normalizeJobTag(String value, Collection<Role> roles) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (JobTag.MANAGEMENT.code().equals(normalized)
                || JobTag.CLERK.code().equals(normalized)
                || JobTag.REPAIR.code().equals(normalized)) {
            return normalized;
        }
        return roles.stream().anyMatch(role -> RoleNames.isPrivileged(role.getName()))
                ? JobTag.MANAGEMENT.code()
                : JobTag.CLERK.code();
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = keyword.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String roleNames(User user) {
        return user.getRoles().stream()
                .map(Role::getName)
                .sorted()
                .collect(Collectors.joining("/"));
    }
}
