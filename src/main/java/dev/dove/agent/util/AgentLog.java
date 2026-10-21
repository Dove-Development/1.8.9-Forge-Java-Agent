package dev.dove.agent.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal logger. The agent runs before log4j is configured by Minecraft, and must not load
 * log4j itself (it would end up in the wrong class loader), so this writes to the console and,
 * when the launcher asks for it, to a log file.
 */
public final class AgentLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    // Captured while the agent starts: FML later swaps System.out/err for streams that re-log
    // every line through log4j with a caller prefix.
    private static final PrintStream OUT = System.out;
    private static final PrintStream ERR = System.err;

    private static volatile boolean debug = Boolean.getBoolean("dove.debug");
    private static volatile PrintStream file;

    private AgentLog() {
    }

    /** Starts writing to {@code logFile} (replacing it) in addition to the console. */
    public static void init(Path logFile, boolean debugEnabled) {
        debug = debugEnabled;
        if (logFile == null) {
            return;
        }
        try {
            if (logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
            OutputStream out = Files.newOutputStream(logFile);
            file = new PrintStream(out, true, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn("Cannot write agent log to " + logFile + ": " + e);
        }
    }

    public static boolean isDebug() {
        return debug;
    }

    public static void debug(String message) {
        if (debug) {
            log(OUT, "DEBUG", message, null);
        }
    }

    public static void info(String message) {
        log(OUT, "INFO", message, null);
    }

    public static void warn(String message) {
        log(ERR, "WARN", message, null);
    }

    public static void error(String message, Throwable t) {
        log(ERR, "ERROR", message, t);
    }

    private static synchronized void log(PrintStream console, String level, String message, Throwable t) {
        String line = "[" + LocalTime.now().format(TIME) + "] [Dove/" + level + "] " + message;
        console.println(line);
        if (t != null) {
            t.printStackTrace(console);
        }
        PrintStream out = file;
        if (out != null) {
            out.println(line);
            if (t != null) {
                t.printStackTrace(out);
            }
        }
    }
}
