package com.automation.core.harness;

import com.automation.core.model.ActivePrayers;
import com.automation.core.model.EquipmentSlot;
import com.automation.core.model.EquipmentSnapshot;
import com.automation.core.model.GameState;
import com.automation.core.model.InventoryItem;
import com.automation.core.model.InventorySnapshot;
import com.automation.core.model.PlayerState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Loads EchoForge {@code .ndjson} combat fixtures into {@link ReplayTick}s.
 *
 * <p>Each non-blank line is one JSON object matching the {@code TickRecorder}
 * schema ({@code tickCount}, {@code player}, {@code target}, {@code executedAction}).
 * Parsing is intentionally dependency-free so {@code ./gradlew coreTest} stays
 * headless and client-free.
 */
public final class NDJSONFixtureLoader {

    private NDJSONFixtureLoader() {}

    /**
     * Fixtures shipped when nothing is discovered on the classpath (jar-only
     * classloaders where the directory cannot be listed).
     */
    private static final List<String> FALLBACK_FIXTURES = List.of(
            "fixtures/sample_fight.ndjson",
            "fixtures/dh-combo-fixture.ndjson",
            "fixtures/survive-spec-fixture.ndjson");

    /**
     * Every {@code fixtures/*.ndjson} resource visible on the classpath, sorted.
     * Discovered from the directory listing so a recording dropped into
     * {@code core/src/test/resources/fixtures/} is picked up automatically;
     * falls back to the shipped fixtures when the classpath cannot be listed.
     */
    public static List<String> fixtureResourceNames() {
        List<String> found = new ArrayList<>();
        ClassLoader cl = NDJSONFixtureLoader.class.getClassLoader();
        URL dir = cl.getResource("fixtures");
        if (dir != null) {
            try {
                if ("file".equals(dir.getProtocol())) {
                    try (Stream<Path> s = Files.list(Paths.get(dir.toURI()))) {
                        s.filter(p -> p.getFileName().toString().endsWith(".ndjson"))
                                .map(p -> "fixtures/" + p.getFileName())
                                .sorted()
                                .forEach(found::add);
                    }
                } else if ("jar".equals(dir.getProtocol())) {
                    JarURLConnection conn = (JarURLConnection) dir.openConnection();
                    try (JarFile jar = conn.getJarFile()) {
                        jar.stream()
                                .map(JarEntry::getName)
                                .filter(n -> n.startsWith("fixtures/") && n.endsWith(".ndjson"))
                                .sorted()
                                .forEach(found::add);
                    }
                }
            } catch (Exception ignored) {
                // Fall through to the shipped list.
            }
        }
        return found.isEmpty() ? FALLBACK_FIXTURES : List.copyOf(found);
    }

    /** Loads every {@code .ndjson} file under {@code classpath:/fixtures/}. */
    public static List<ReplayTick> loadClasspathFixtures() {
        List<ReplayTick> all = new ArrayList<>();
        for (String resource : fixtureResourceNames()) {
            try (InputStream in = NDJSONFixtureLoader.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    continue;
                }
                all.addAll(load(in, resource));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed closing fixture " + resource, e);
            }
        }
        if (all.isEmpty()) {
            throw new IllegalStateException("No EchoForge fixtures found on classpath under fixtures/");
        }
        return List.copyOf(all);
    }

    /** Loads one classpath resource, e.g. {@code fixtures/sample_fight.ndjson}. */
    public static List<ReplayTick> loadClasspathResource(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (InputStream in = NDJSONFixtureLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Classpath fixture not found: " + resourcePath);
            }
            return load(in, resourcePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading fixture " + resourcePath, e);
        }
    }

    /** Loads an on-disk NDJSON file. */
    public static List<ReplayTick> loadFile(Path path) {
        Objects.requireNonNull(path, "path");
        try (InputStream in = Files.newInputStream(path)) {
            return load(in, path.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading fixture " + path, e);
        }
    }

    public static List<ReplayTick> load(InputStream in, String sourceName) throws IOException {
        Objects.requireNonNull(in, "in");
        List<ReplayTick> ticks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                try {
                    ticks.add(parseLine(trimmed));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(
                            "Invalid NDJSON in " + sourceName + " line " + lineNo + ": " + e.getMessage(), e);
                }
            }
        }
        return List.copyOf(ticks);
    }

    /** Parses a single NDJSON object line into a {@link ReplayTick}. */
    public static ReplayTick parseLine(String line) {
        JsonObject root = JsonObject.parse(line);
        int tickCount = root.getInt("tickCount", 0);
        JsonObject player = root.getObject("player");
        JsonObject target = root.getObject("target");
        String executed = root.getString("executedAction", "");

        PlayerState local = toPlayerState(player, /*includeInventoryGear*/ true);
        Optional<PlayerState> opp = Optional.empty();
        if (target != null && (target.getInt("hp", -1) > 0 || target.getInt("weaponId", 0) > 0)) {
            opp = Optional.of(toTargetState(target));
        }
        InventorySnapshot inventory = toInventory(player == null ? null : player.getObject("inventory"));
        GameState state = new GameState(tickCount, local, opp, inventory);
        return new ReplayTick(state, ActionExpectation.parse(executed), line);
    }

    private static PlayerState toPlayerState(JsonObject player, boolean ignored) {
        if (player == null) {
            return new PlayerState(99, 99, 99, 0, -1, ActivePrayers.none(), EquipmentSnapshot.empty());
        }
        int hp = player.getInt("hp", 99);
        int maxHp = player.getInt("maxHp", Math.max(hp, 1));
        int prayer = player.getInt("prayer", 99);
        int spec = player.getInt("specEnergy", 0);
        EquipmentSnapshot gear = toEquipment(player.getObject("equipment"));
        return new PlayerState(hp, maxHp, prayer, spec, -1, ActivePrayers.none(), gear);
    }

    private static PlayerState toTargetState(JsonObject target) {
        int hp = target.getInt("hp", -1);
        int maxHp = target.getInt("maxHp", Math.max(hp, 1));
        int weaponId = target.getInt("weaponId", 0);
        int anim = target.getInt("animationId", -1);
        Map<EquipmentSlot, Integer> gear = new EnumMap<>(EquipmentSlot.class);
        if (weaponId > 0) {
            gear.put(EquipmentSlot.WEAPON, weaponId);
        }
        return new PlayerState(
                Math.max(hp, 0),
                Math.max(maxHp, 1),
                0,
                0,
                anim,
                ActivePrayers.none(),
                new EquipmentSnapshot(gear));
    }

    private static EquipmentSnapshot toEquipment(JsonObject equipment) {
        if (equipment == null || equipment.isEmpty()) {
            return EquipmentSnapshot.empty();
        }
        Map<EquipmentSlot, Integer> map = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            int id = equipment.getInt(Integer.toString(slot.index()), 0);
            if (id <= 0) {
                // Also accept enum-name keys for hand-authored fixtures.
                id = equipment.getInt(slot.name(), 0);
            }
            if (id > 0) {
                map.put(slot, id);
            }
        }
        return new EquipmentSnapshot(map);
    }

    private static InventorySnapshot toInventory(JsonObject inventory) {
        if (inventory == null || inventory.isEmpty()) {
            return InventorySnapshot.empty();
        }
        List<InventoryItem> items = new ArrayList<>();
        for (Map.Entry<String, Object> e : inventory.entries()) {
            int slot;
            try {
                slot = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            int itemId = asInt(e.getValue(), 0);
            if (itemId > 0) {
                items.add(new InventoryItem(slot, itemId, 1));
            }
        }
        return new InventorySnapshot(items);
    }

    private static int asInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Minimal JSON object reader for the fixed EchoForge tick schema.
     * Supports nested objects, numbers, strings, booleans, and null — enough
     * for NDJSON fixtures without pulling in Jackson on the core classpath.
     */
    static final class JsonObject {
        private final Map<String, Object> values;

        private JsonObject(Map<String, Object> values) {
            this.values = values;
        }

        static JsonObject parse(String json) {
            Parser p = new Parser(json);
            Object value = p.parseValue();
            p.skipWs();
            if (!p.eof()) {
                throw new IllegalArgumentException("Trailing junk at index " + p.i);
            }
            if (!(value instanceof JsonObject obj)) {
                throw new IllegalArgumentException("Expected a JSON object root");
            }
            return obj;
        }

        boolean isEmpty() {
            return values.isEmpty();
        }

        JsonObject getObject(String key) {
            Object v = values.get(key);
            return v instanceof JsonObject obj ? obj : null;
        }

        int getInt(String key, int defaultValue) {
            return asInt(values.get(key), defaultValue);
        }

        String getString(String key, String defaultValue) {
            Object v = values.get(key);
            return v instanceof String s ? s : defaultValue;
        }

        Iterable<Map.Entry<String, Object>> entries() {
            return values.entrySet();
        }

        private static final class Parser {
            private final String s;
            private int i;

            Parser(String s) {
                this.s = s;
            }

            boolean eof() {
                return i >= s.length();
            }

            void skipWs() {
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        i++;
                    } else {
                        break;
                    }
                }
            }

            Object parseValue() {
                skipWs();
                if (eof()) {
                    throw new IllegalArgumentException("Unexpected end of JSON");
                }
                char c = s.charAt(i);
                if (c == '{') {
                    return parseObject();
                }
                if (c == '"') {
                    return parseString();
                }
                if (c == 't' || c == 'f') {
                    return parseBoolean();
                }
                if (c == 'n') {
                    parseNull();
                    return null;
                }
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return parseNumber();
                }
                throw new IllegalArgumentException("Unexpected character '" + c + "' at " + i);
            }

            JsonObject parseObject() {
                expect('{');
                Map<String, Object> map = new LinkedHashMap<>();
                skipWs();
                if (peek('}')) {
                    i++;
                    return new JsonObject(map);
                }
                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    expect(':');
                    Object value = parseValue();
                    map.put(key, value);
                    skipWs();
                    if (peek('}')) {
                        i++;
                        return new JsonObject(map);
                    }
                    expect(',');
                }
            }

            String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (!eof()) {
                    char c = s.charAt(i++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c == '\\') {
                        if (eof()) {
                            throw new IllegalArgumentException("Unterminated escape");
                        }
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"', '\\', '/' -> sb.append(e);
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'u' -> {
                                if (i + 4 > s.length()) {
                                    throw new IllegalArgumentException("Bad unicode escape");
                                }
                                int code = Integer.parseInt(s.substring(i, i + 4), 16);
                                i += 4;
                                sb.append((char) code);
                            }
                            default -> throw new IllegalArgumentException("Bad escape \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw new IllegalArgumentException("Unterminated string");
            }

            Number parseNumber() {
                int start = i;
                if (peek('-')) {
                    i++;
                }
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    i++;
                }
                boolean fractional = false;
                if (peek('.')) {
                    fractional = true;
                    i++;
                    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                        i++;
                    }
                }
                String num = s.substring(start, i);
                if (fractional) {
                    return Double.parseDouble(num);
                }
                long v = Long.parseLong(num);
                if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                    return (int) v;
                }
                return v;
            }

            Boolean parseBoolean() {
                if (s.startsWith("true", i)) {
                    i += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return Boolean.FALSE;
                }
                throw new IllegalArgumentException("Invalid boolean at " + i);
            }

            void parseNull() {
                if (!s.startsWith("null", i)) {
                    throw new IllegalArgumentException("Invalid null at " + i);
                }
                i += 4;
            }

            void expect(char c) {
                skipWs();
                if (eof() || s.charAt(i) != c) {
                    throw new IllegalArgumentException("Expected '" + c + "' at " + i);
                }
                i++;
            }

            boolean peek(char c) {
                return i < s.length() && s.charAt(i) == c;
            }
        }
    }
}
