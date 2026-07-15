import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public class CheckCoverageBaseline {
    private static final Path DEFAULT_REPORT = Path.of("target/site/jacoco/jacoco.csv");
    private static final Path DEFAULT_BASELINE = Path.of("scripts/jacoco-baseline.properties");
    private static final double ROUNDING_TOLERANCE = 0.00005d;

    public static void main(String[] args) throws Exception {
        Path report = args.length > 0 ? Path.of(args[0]) : DEFAULT_REPORT;
        Path baselineFile = args.length > 1 ? Path.of(args[1]) : DEFAULT_BASELINE;
        if (!Files.isRegularFile(report)) {
            throw new IOException("JaCoCo CSV report not found: " + report);
        }
        if (!Files.isRegularFile(baselineFile)) {
            throw new IOException("Coverage baseline not found: " + baselineFile);
        }

        Map<String, Counter> counters = readCounters(report);
        Properties baseline = new Properties();
        try (Reader reader = Files.newBufferedReader(baselineFile, StandardCharsets.UTF_8)) {
            baseline.load(reader);
        }

        List<Metric> metrics = List.of(
                new Metric("instruction", "INSTRUCTION", counters.get("INSTRUCTION")),
                new Metric("branch", "BRANCH", counters.get("BRANCH")),
                new Metric("line", "LINE", counters.get("LINE")),
                new Metric("method", "METHOD", counters.get("METHOD"))
        );
        boolean declined = false;
        for (Metric metric : metrics) {
            if (metric.counter() == null) {
                throw new IOException("JaCoCo CSV is missing " + metric.csvPrefix() + " counters");
            }
            double minimum = requiredDouble(
                    baseline,
                    metric.propertyName() + ".minimum-percent"
            );
            double current = metric.counter().percentage();
            System.out.printf(
                    Locale.ROOT,
                    "%-12s current=%8.4f%% baseline=%8.4f%% covered=%d total=%d%n",
                    metric.propertyName(),
                    current,
                    minimum,
                    metric.counter().covered(),
                    metric.counter().total()
            );
            if (current + ROUNDING_TOLERANCE < minimum) {
                declined = true;
            }
        }
        if (declined) {
            throw new IOException(
                    "JaCoCo coverage declined below scripts/jacoco-baseline.properties"
            );
        }
        System.out.println("JaCoCo coverage baseline check passed");
    }

    private static Map<String, Counter> readCounters(Path report) throws IOException {
        List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            throw new IOException("JaCoCo CSV report is empty: " + report);
        }
        String[] header = lines.getFirst().split(",", -1);
        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < header.length; index++) {
            indexes.put(header[index], index);
        }

        Map<String, Counter> result = new HashMap<>();
        for (String prefix : List.of("INSTRUCTION", "BRANCH", "LINE", "METHOD")) {
            int missedIndex = requiredIndex(indexes, prefix + "_MISSED");
            int coveredIndex = requiredIndex(indexes, prefix + "_COVERED");
            long missed = 0;
            long covered = 0;
            for (int lineIndex = 1; lineIndex < lines.size(); lineIndex++) {
                String row = lines.get(lineIndex);
                if (row.isBlank()) {
                    continue;
                }
                String[] columns = row.split(",", -1);
                missed += parseLong(columns, missedIndex, prefix + "_MISSED", lineIndex + 1);
                covered += parseLong(columns, coveredIndex, prefix + "_COVERED", lineIndex + 1);
            }
            result.put(prefix, new Counter(missed, covered));
        }
        return result;
    }

    private static int requiredIndex(Map<String, Integer> indexes, String name) throws IOException {
        Integer index = indexes.get(name);
        if (index == null) {
            throw new IOException("JaCoCo CSV column not found: " + name);
        }
        return index;
    }

    private static long parseLong(
            String[] columns,
            int index,
            String column,
            int lineNumber
    ) throws IOException {
        if (index >= columns.length) {
            throw new IOException(
                    "JaCoCo CSV row " + lineNumber + " has no " + column + " column"
            );
        }
        try {
            return Long.parseLong(columns[index]);
        } catch (NumberFormatException error) {
            throw new IOException(
                    "Invalid JaCoCo counter at row " + lineNumber + ", column " + column,
                    error
            );
        }
    }

    private static double requiredDouble(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Coverage baseline property not found: " + key);
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            throw new IOException("Invalid coverage baseline value for " + key, error);
        }
    }

    private record Counter(long missed, long covered) {
        long total() {
            return missed + covered;
        }

        double percentage() {
            return total() == 0 ? 100.0d : covered * 100.0d / total();
        }
    }

    private record Metric(String propertyName, String csvPrefix, Counter counter) {
    }
}
