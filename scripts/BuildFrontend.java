import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuildFrontend {
    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path STATIC_ROOT = PROJECT_ROOT.resolve("src/main/resources/static");
    private static final Path ASSETS_ROOT = STATIC_ROOT.resolve("assets");
    private static final Path OUTPUT_ROOT = PROJECT_ROOT.resolve("target/frontend-static");

    private static final List<String> REQUIRED_FILES = List.of(
            "index.html",
            "reset-client.html",
            "sw.js",
            "manifest.webmanifest",
            "assets/app.css",
            "assets/styles/tokens.css",
            "assets/styles/base.css",
            "assets/styles/surfaces.css",
            "assets/styles/tables.css",
            "assets/styles/overlays.css",
            "assets/styles/industrial-ui.css",
            "assets/styles/responsive.css",
            "assets/styles/polish.css",
            "assets/styles/polish-responsive.css",
            "assets/app.js",
            "assets/modules/access-control.js",
            "assets/modules/app-state.js",
            "assets/modules/batch-operations.js",
            "assets/modules/client-reset-query.js",
            "assets/modules/client-reset.js",
            "assets/modules/data-loader.js",
            "assets/modules/detail-drawer.js",
            "assets/modules/dashboard-view.js",
            "assets/modules/display-utils.js",
            "assets/modules/downloads.js",
            "assets/modules/entity-options.js",
            "assets/modules/field-config.js",
            "assets/modules/filter-options.js",
            "assets/modules/list-config.js",
            "assets/modules/command-palette.js",
            "assets/modules/modal-renderer.js",
            "assets/modules/mutation-refresh.js",
            "assets/modules/paging.js",
            "assets/modules/routes.js",
            "assets/modules/request-id.js",
            "assets/modules/session.js",
            "assets/modules/status-ui.js",
            "assets/modules/vehicle-detail-loader.js",
            "assets/modules/ui-config.js",
            "assets/modules/ui/confirm-dialog.js",
            "assets/modules/ui/contracts.js",
            "assets/modules/ui/action-registry.js",
            "assets/modules/ui/data-table.js",
            "assets/modules/ui/drawer-actions.js",
            "assets/modules/ui/form-workspace.js",
            "assets/modules/ui/overlay-manager.js",
            "assets/modules/ui/shell-decorator.js",
            "assets/modules/ui/state-utils.js",
            "assets/modules/ui/summary-card.js",
            "assets/modules/workflows/attachments-workflow.js",
            "assets/modules/workflows/configs-workflow.js",
            "assets/modules/workflows/imports-workflow.js",
            "assets/modules/workflows/operations-workflow.js",
            "assets/modules/workflows/outbound-workflow.js",
            "assets/modules/workflows/parts-workflow.js",
            "assets/modules/workflows/rentals-workflow.js",
            "assets/modules/workflows/repairs-workflow.js",
            "assets/modules/workflows/statistics-workflow.js",
            "assets/modules/workflows/users-workflow.js",
            "assets/modules/workflows/vehicle-workflow.js"
    );

    private static final Pattern IMPORT_PATTERN = Pattern.compile("from\\s+[\"'](\\./[^\"']+)[\"']");
    private static final Pattern FETCH_PATTERN = Pattern.compile("\\bfetch\\s*\\(");
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("(?:async\\s+)?function\\s+([A-Za-z0-9_$]+)\\s*\\(");
    private static final Pattern MOJIBAKE_PATTERN = Pattern.compile(
            "(?:\\x{95bb}\\x{e742}\\x{9420}\\x{7c17}\\x{95b9}\\x{744b}\\x{95c1}\\x{7698}\\x{93bc}\\x{78e1}\\x{95ba}\\x{55d7}\\x{5039}|\\x{8119}.|\\x{8117}.|\\x{951f}\\?)"
    );
    private static final Set<String> FETCH_ALLOWED_FILES = Set.of(
            "sw.js",
            "assets/modules/downloads.js",
            "assets/modules/session.js"
    );
    private static final Set<String> APP_FETCH_HELPERS = Set.of(
            "fetchProtectedBlob",
            "downloadInvoice",
            "downloadContract",
            "downloadProtectedFile"
    );

    public static void main(String[] args) throws Exception {
        assertRequiredFiles();
        assertModuleImports(ASSETS_ROOT.resolve("app.js"));
        assertControlledFetchUsage();
        assertTextEncoding();
        copyDirectory(STATIC_ROOT, OUTPUT_ROOT);
        writeServiceWorker();
        writeBuildManifest();

        System.out.println("Frontend build prepared at " + PROJECT_ROOT.relativize(OUTPUT_ROOT));
    }

    private static void assertRequiredFiles() throws IOException {
        for (String file : REQUIRED_FILES) {
            Path path = STATIC_ROOT.resolve(file);
            if (!Files.isRegularFile(path)) {
                throw new IOException("Required frontend file is missing: " + PROJECT_ROOT.relativize(path));
            }
        }
    }

    private static void assertModuleImports(Path entryFile) throws IOException {
        Set<Path> seen = new HashSet<>();
        ArrayDeque<Path> stack = new ArrayDeque<>();
        stack.push(entryFile);

        while (!stack.isEmpty()) {
            Path file = stack.pop().toAbsolutePath().normalize();
            if (!seen.add(file)) {
                continue;
            }
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = IMPORT_PATTERN.matcher(source);
            while (matcher.find()) {
                String specifier = matcher.group(1);
                if (!specifier.endsWith(".js")) {
                    continue;
                }
                Path resolved = file.getParent().resolve(specifier).normalize();
                if (!Files.isRegularFile(resolved)) {
                    throw new IOException("Imported frontend module is missing: " + PROJECT_ROOT.relativize(resolved));
                }
                stack.push(resolved);
            }
        }
    }

    private static void assertTextEncoding() throws IOException {
        List<Path> files = new ArrayList<>();
        collectTextFiles(STATIC_ROOT, files);

        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (MOJIBAKE_PATTERN.matcher(source).find()) {
                failures.add("- " + PROJECT_ROOT.relativize(file));
            }
        }
        if (!failures.isEmpty()) {
            throw new IOException("Possible mojibake text found:%n%s".formatted(String.join("%n", failures)));
        }
    }

    private static void assertControlledFetchUsage() throws IOException {
        List<Path> files = new ArrayList<>();
        collectTextFiles(STATIC_ROOT, files);

        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            if (!file.getFileName().toString().endsWith(".js")) {
                continue;
            }
            String relativePath = STATIC_ROOT.relativize(file).toString().replace('\\', '/');
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = FETCH_PATTERN.matcher(source);
            while (matcher.find()) {
                if (isAllowedFetch(relativePath, source, matcher.start())) {
                    continue;
                }
                failures.add("- " + relativePath + ":" + lineNumber(source, matcher.start()));
            }
        }
        if (!failures.isEmpty()) {
            throw new IOException("Bare fetch usage must go through session.js or an approved binary download helper:%n%s"
                    .formatted(String.join("%n", failures)));
        }
    }

    private static boolean isAllowedFetch(String relativePath, String source, int offset) {
        if (FETCH_ALLOWED_FILES.contains(relativePath)) {
            return true;
        }
        if (!"assets/app.js".equals(relativePath)) {
            return false;
        }
        return APP_FETCH_HELPERS.contains(enclosingFunctionName(source, offset));
    }

    private static String enclosingFunctionName(String source, int offset) {
        Matcher matcher = FUNCTION_PATTERN.matcher(source);
        String functionName = null;
        int bodyStart = -1;
        int bodyEnd = -1;
        while (matcher.find() && matcher.start() < offset) {
            int openBrace = source.indexOf('{', matcher.end());
            if (openBrace < 0 || openBrace > offset) {
                continue;
            }
            functionName = matcher.group(1);
            bodyStart = openBrace;
            bodyEnd = findMatchingBrace(source, openBrace);
        }
        if (functionName == null || offset < bodyStart || offset > bodyEnd) {
            return null;
        }
        return functionName;
    }

    private static int findMatchingBrace(String source, int openBrace) {
        int depth = 0;
        for (int index = openBrace; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return source.length();
    }

    private static int lineNumber(String source, int offset) {
        int line = 1;
        for (int index = 0; index < offset && index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static void collectTextFiles(Path root, List<Path> files) throws IOException {
        try (var stream = Files.list(root)) {
            for (Path path : stream.sorted().toList()) {
                if (Files.isDirectory(path)) {
                    collectTextFiles(path, files);
                } else if (Files.isRegularFile(path) && isTextAsset(path)) {
                    files.add(path);
                }
            }
        }
    }

    private static boolean isTextAsset(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html")
                || name.endsWith(".css")
                || name.endsWith(".js")
                || name.endsWith(".json")
                || name.endsWith(".webmanifest");
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        deleteRecursively(target);
        Files.createDirectories(target);
        try (var stream = Files.list(source)) {
            for (Path sourcePath : stream.sorted().toList()) {
                Path targetPath = target.resolve(sourcePath.getFileName().toString());
                if (Files.isDirectory(sourcePath)) {
                    copyDirectory(sourcePath, targetPath);
                } else if (Files.isRegularFile(sourcePath)) {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private static void writeServiceWorker() throws IOException, NoSuchAlgorithmException {
        Path swPath = OUTPUT_ROOT.resolve("sw.js");
        List<String> shellAssets = collectShellAssets();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String asset : shellAssets) {
            if ("/".equals(asset)) {
                continue;
            }
            digest.update(asset.getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(OUTPUT_ROOT.resolve(asset.substring(1))));
        }

        String cacheName = "forklift-erp-client-" + toHex(digest.digest()).substring(0, 12);
        String source = Files.readString(swPath, StandardCharsets.UTF_8);
        String generated = source
                .replaceAll("const CACHE_NAME = \".*?\";", "const CACHE_NAME = \"%s\";".formatted(cacheName))
                .replaceAll("(?s)const SHELL_ASSETS = \\[.*?];", "const SHELL_ASSETS = %s;".formatted(toJsonArray(shellAssets)));
        Files.writeString(swPath, generated, StandardCharsets.UTF_8);
    }

    private static void writeBuildManifest() throws IOException {
        List<OutputFile> files = new ArrayList<>();
        collectFiles(OUTPUT_ROOT, files);
        files.sort(Comparator.comparing(OutputFile::path));

        StringBuilder manifest = new StringBuilder();
        manifest.append("{\n");
        manifest.append("  \"builtAt\": ").append(jsonString(Instant.now().toString())).append(",\n");
        manifest.append("  \"files\": [\n");
        for (int index = 0; index < files.size(); index++) {
            OutputFile file = files.get(index);
            manifest.append("    {\n");
            manifest.append("      \"path\": ").append(jsonString(file.path())).append(",\n");
            manifest.append("      \"bytes\": ").append(file.bytes()).append("\n");
            manifest.append("    }");
            if (index < files.size() - 1) {
                manifest.append(",");
            }
            manifest.append("\n");
        }
        manifest.append("  ]\n");
        manifest.append("}\n");

        Files.writeString(OUTPUT_ROOT.resolve("frontend-build-manifest.json"), manifest.toString(), StandardCharsets.UTF_8);
    }

    private static List<String> collectShellAssets() throws IOException {
        List<OutputFile> files = new ArrayList<>();
        collectFiles(OUTPUT_ROOT, files);
        List<String> assets = files.stream()
                .map(file -> "/" + file.path())
                .filter(file -> !"/sw.js".equals(file) && !"/frontend-build-manifest.json".equals(file))
                .sorted()
                .distinct()
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        if (assets.contains("/index.html")) {
            assets.add(0, "/");
        }
        return assets.stream().distinct().collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private static void collectFiles(Path root, List<OutputFile> files) throws IOException {
        try (var stream = Files.list(root)) {
            for (Path fullPath : stream.sorted().toList()) {
                if (Files.isDirectory(fullPath)) {
                    collectFiles(fullPath, files);
                } else if (Files.isRegularFile(fullPath)) {
                    files.add(new OutputFile(
                            OUTPUT_ROOT.relativize(fullPath).toString().replace('\\', '/'),
                            Files.size(fullPath)
                    ));
                }
            }
        }
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[\n");
        for (int index = 0; index < values.size(); index++) {
            builder.append("  ").append(jsonString(values.get(index)));
            if (index < values.size() - 1) {
                builder.append(",");
            }
            builder.append("\n");
        }
        builder.append("]");
        return builder.toString();
    }

    private static String jsonString(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append("\\u%04x".formatted((int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append("%02x".formatted(b));
        }
        return builder.toString();
    }

    private record OutputFile(String path, long bytes) {
    }
}
