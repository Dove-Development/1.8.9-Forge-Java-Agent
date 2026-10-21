package dev.dove.agent.util;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal logger. The agent runs before log4j is configured by Minecraft, and must not load
 * log4j itself (it would end up in the wrong class loader), so this just writes to stdout/stderr.
 */
public final class AgentLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final boolean DEBUG = Boolean.getBoolean("dove.debug");

    private AgentLog() {
    }

    public static void debug(String message) {
        if (DEBUG) {
            log(System.out, "DEBUG", message);
        }
    }

    public static void info(String message) {
        log(System.out, "INFO", message);
    }

    public static void warn(String message) {
        log(System.err, "WARN", message);
    }

    public static void error(String message, Throwable t) {
        log(System.err, "ERROR", message);
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }

    private static void log(PrintStream out, String level, String message) {
        out.println("[" + LocalTime.now().format(TIME) + "] [Dove/" + level + "] " + message);
    }
}
