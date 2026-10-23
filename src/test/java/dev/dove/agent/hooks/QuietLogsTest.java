package dev.dove.agent.hooks;

import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuietLogsTest {

    @Test
    void hidesNettysPlatformInfoButNotItsWarnings() {
        QuietLogs.install();
        Logger netty = Logger.getLogger(QuietLogs.NETTY_PLATFORM);

        assertFalse(netty.isLoggable(Level.INFO));
        assertTrue(netty.isLoggable(Level.WARNING));
        // Other Netty loggers keep their level.
        assertTrue(Logger.getLogger("io.netty.channel.nio.NioEventLoop").isLoggable(Level.INFO));
    }
}
