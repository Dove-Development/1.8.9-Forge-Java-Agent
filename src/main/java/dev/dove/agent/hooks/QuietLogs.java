package dev.dove.agent.hooks;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Informational lines old libraries print on Java 25 that say nothing a player can act on.
 *
 * <p>Netty 4.0/4.1 as Minecraft 1.8.9 and 1.12.2 ship it looks for its low-level direct buffer
 * access, which these old versions cannot find on Java 25, and logs "Your platform does not provide
 * complete low-level API for accessing direct buffers reliably" through java.util.logging. It then
 * uses ordinary heap buffers, which is safe. Only that logger's info messages are hidden; its
 * warnings still show, and Netty behaves as before.
 */
public final class QuietLogs {

    static final String NETTY_PLATFORM = "io.netty.util.internal.PlatformDependent";

    /** java.util.logging keeps loggers weakly; this keeps the level set. */
    private static Logger netty;

    private QuietLogs() {
    }

    public static void install() {
        netty = Logger.getLogger(NETTY_PLATFORM);
        netty.setLevel(Level.WARNING);
    }
}
