package dev.dove.agent.hooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaVersionHooksTest {

    enum JavaVersion { JAVA_0_9, JAVA_1_7, JAVA_1_8, JAVA_17, JAVA_99, JAVA_RECENT }

    @Test
    void parsesCommonsLangConstants() {
        assertEquals(0, JavaVersionHooks.feature("JAVA_0_9"));
        assertEquals(8, JavaVersionHooks.feature("JAVA_1_8"));
        assertEquals(17, JavaVersionHooks.feature("JAVA_17"));
    }

    @Test
    void comparesAgainstRunningJava() {
        assertTrue(JavaVersionHooks.isJavaVersionAtLeast(JavaVersion.JAVA_1_7));
        assertTrue(JavaVersionHooks.isJavaVersionAtLeast(JavaVersion.JAVA_17));
        assertTrue(JavaVersionHooks.isJavaVersionAtLeast(JavaVersion.JAVA_RECENT));
        assertFalse(JavaVersionHooks.isJavaVersionAtLeast(JavaVersion.JAVA_99));
    }
}
