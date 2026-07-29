package com.example.forklift_erp;

import com.example.forklift_erp.entity.Permission;
import com.example.forklift_erp.entity.Role;
import com.example.forklift_erp.entity.User;
import com.example.forklift_erp.repository.PermissionRepository;
import com.example.forklift_erp.repository.RoleRepository;
import com.example.forklift_erp.repository.UserRepository;
import com.example.forklift_erp.repository.WarehouseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("docker-integration")
@ActiveProfiles("test")
@AutoConfigureMockMvc
abstract class TestcontainersDatabaseSupport {

    protected static final String PASSWORD = "CodexTest123!";

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0.43");
    private static final String EXTERNAL_DATABASE_URL = setting(
            "forklift.test.db.url",
            "FORKLIFT_ERP_TEST_DB_URL"
    );
    private static final String EXTERNAL_DATABASE_USERNAME = setting(
            "forklift.test.db.username",
            "FORKLIFT_ERP_TEST_DB_USERNAME"
    );
    private static final String EXTERNAL_DATABASE_PASSWORD = setting(
            "forklift.test.db.password",
            "FORKLIFT_ERP_TEST_DB_PASSWORD"
    );
    private static final boolean USE_EXTERNAL_TEST_DATABASE =
            EXTERNAL_DATABASE_URL != null && !EXTERNAL_DATABASE_URL.isBlank();

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("forklift_erp_test")
            .withUsername("forklift")
            .withPassword("forklift")
            .withUrlParam("useUnicode", "true")
            .withUrlParam("characterEncoding", "utf-8")
            .withUrlParam("serverTimezone", "Asia/Shanghai")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true");

    static {
        if (USE_EXTERNAL_TEST_DATABASE) {
            requireIsolatedTestDatabase(EXTERNAL_DATABASE_URL);
        } else {
            MYSQL.start();
        }
    }

    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        if (USE_EXTERNAL_TEST_DATABASE) {
            registry.add("spring.datasource.url", () -> EXTERNAL_DATABASE_URL);
            registry.add("spring.datasource.username", () -> requiredSetting(
                    EXTERNAL_DATABASE_USERNAME,
                    "forklift.test.db.username / FORKLIFT_ERP_TEST_DB_USERNAME"
            ));
            registry.add("spring.datasource.password", () -> EXTERNAL_DATABASE_PASSWORD == null
                    ? ""
                    : EXTERNAL_DATABASE_PASSWORD);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            return;
        }
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected RoleRepository roleRepository;

    @Autowired
    protected PermissionRepository permissionRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected WarehouseRepository warehouseRepository;

    protected final List<String> usersToCleanup = new ArrayList<>();
    protected final List<String> rolesToCleanup = new ArrayList<>();

    @AfterEach
    void cleanUpTrackedUsersAndRoles() {
        for (String username : usersToCleanup.reversed()) {
            userRepository.findByUsername(username).ifPresent(userRepository::delete);
        }
        usersToCleanup.clear();

        for (String roleName : rolesToCleanup.reversed()) {
            roleRepository.findByName(roleName).ifPresent(roleRepository::delete);
        }
        rolesToCleanup.clear();
    }

    protected User createUserDirectly(String username, String roleName) {
        return createUserDirectly(username, PASSWORD, roleName);
    }

    protected User createUserDirectly(String username, String password, String roleName) {
        Role role = findOrCreateRole(roleName);

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setEnabled(true);
        user.setRoles(Set.of(role));
        user.setJobTag("SUPER_ADMIN".equals(roleName) || "ADMIN".equals(roleName) ? "MANAGEMENT" : "CLERK");
        User saved = userRepository.save(user);
        usersToCleanup.add(username);
        return saved;
    }

    protected User createUserWithPermissions(String username, String roleName, String... permissionCodes) {
        Set<Permission> permissions = new HashSet<>();
        for (String code : permissionCodes) {
            Permission permission = permissionRepository.findByCode(code)
                    .orElseGet(() -> {
                        Permission created = new Permission();
                        created.setCode(code);
                        created.setDescription(code);
                        return permissionRepository.save(created);
                    });
            permissions.add(permission);
        }

        Role role = new Role();
        role.setName(roleName);
        role.setDescription(roleName);
        role.setPermissions(permissions);
        roleRepository.save(role);
        rolesToCleanup.add(roleName);

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setEnabled(true);
        user.setRoles(Set.of(role));
        User saved = userRepository.save(user);
        usersToCleanup.add(username);
        return saved;
    }

    protected ResultActions registerViaApi(String token, String username, String password, String role) throws Exception {
        usersToCleanup.add(username);
        return mockMvc.perform(post("/api/auth/register")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "username", username,
                        "password", password,
                        "roles", List.of(role)
                ))));
    }

    protected String login(String username) throws Exception {
        return login(username, PASSWORD);
    }

    protected String login(String username, String password) throws Exception {
        String body = loginExpectingStatus(username, password, 200);
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    protected String loginExpectingStatus(String username, String password, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    protected Long findUserId(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow()
                .getId();
    }

    protected Long findUserVersion(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow()
                .getVersion();
    }

    protected String unique(String prefix) {
        return "it_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected String uniqueUsername(String prefix) {
        return unique(prefix);
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }

    protected List<String> textValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    protected Long defaultWarehouseId() {
        return warehouseRepository.findFirstByDefaultWarehouseTrueOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException("Default warehouse is required for integration tests"))
                .getId();
    }

    private Role findOrCreateRole(String roleName) {
        return roleRepository.findByName(roleName)
                .orElseGet(() -> {
                    Role newRole = new Role();
                    newRole.setName(roleName);
                    newRole.setDescription(roleName);
                    if ("SUPER_ADMIN".equals(roleName)) {
                        permissionRepository.findAll().forEach(newRole.getPermissions()::add);
                    }
                    return roleRepository.save(newRole);
                });
    }

    private static String setting(String systemProperty, String environmentVariable) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentVariable);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requiredSetting(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing external test database setting: " + label);
        }
        return value;
    }

    private static void requireIsolatedTestDatabase(String jdbcUrl) {
        String normalized = jdbcUrl == null ? "" : jdbcUrl.trim().toLowerCase();
        int queryStart = normalized.indexOf('?');
        String withoutQuery = queryStart < 0 ? normalized : normalized.substring(0, queryStart);
        if (!withoutQuery.matches(".+/forklift_erp_test(?:_[0-9a-f]{12,32})?")) {
            throw new IllegalStateException(
                    "Integration tests may only use forklift_erp_test or a UUID-suffixed isolated database; refusing URL: "
                            + sanitizedDatabaseUrl(jdbcUrl)
            );
        }
    }

    private static String sanitizedDatabaseUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "<null>";
        }
        int queryStart = jdbcUrl.indexOf('?');
        return queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
    }
}
