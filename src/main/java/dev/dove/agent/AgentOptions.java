package dev.dove.agent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Options passed as {@code -javaagent:dove-agent.jar=key=value,key=value}.
 *
 * <ul>
 *   <li>{@code log} - file to write the agent log to (the launcher passes {@code logs/dove-agent.log})</li>
 *   <li>{@code cache} - directory for cached work such as unpacked Forge binary patches</li>
 *   <li>{@code debug} - {@code true} for verbose logging (same as {@code -Ddove.debug=true})</li>
 * </ul>
 */
public record AgentOptions(Path logFile, Path cacheDir, boolean debug) {

    public static AgentOptions parse(String args) {
        Map<String, String> values = new HashMap<>();
        if (args != null && !args.isBlank()) {
            String key = null;
            for (String part : args.split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).matches("[a-zA-Z]+")) {
                    key = part.substring(0, eq);
                    values.put(key, part.substring(eq + 1));
                } else if (key != null) {
                    // A comma inside a value (e.g. a path); glue it back on.
                    values.merge(key, "," + part, String::concat);
                }
            }
        }
        Path log = values.containsKey("log") ? Path.of(values.get("log")) : null;
        Path cache = values.containsKey("cache")
                ? Path.of(values.get("cache"))
                : Path.of(System.getProperty("java.io.tmpdir"), "dove-agent");
        boolean debug = Boolean.parseBoolean(values.get("debug")) || Boolean.getBoolean("dove.debug");
        return new AgentOptions(log, cache, debug);
    }
}
