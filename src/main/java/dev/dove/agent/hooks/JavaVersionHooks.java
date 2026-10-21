package dev.dove.agent.hooks;

/**
 * Old commons-lang3 (3.3.2 ships with Minecraft 1.8.9) can't parse Java versions past 1.8: its
 * {@code SystemUtils.JAVA_SPECIFICATION_VERSION_AS_ENUM} is {@code null} on Java 25, so
 * {@code SystemUtils.isJavaVersionAtLeast} throws a NullPointerException.
 */
public final class JavaVersionHooks {

    public static final String OWNER = "dev/dove/agent/hooks/JavaVersionHooks";

    private static final int CURRENT = Runtime.version().feature();

    private JavaVersionHooks() {
    }

    /**
     * Replaces {@code SystemUtils.isJavaVersionAtLeast(JavaVersion)}. Takes the enum as Object so
     * the agent doesn't link against the game's commons-lang3.
     */
    public static boolean isJavaVersionAtLeast(Object requiredVersion) {
        return CURRENT >= feature(((Enum<?>) requiredVersion).name());
    }

    /** Feature number of a commons-lang3 JavaVersion constant: JAVA_1_8 is 8, JAVA_17 is 17. */
    static int feature(String constant) {
        if (constant.equals("JAVA_RECENT")) {
            return CURRENT;
        }
        String[] parts = constant.split("_");
        try {
            if (parts.length == 3 && parts[1].equals("1")) {
                return Integer.parseInt(parts[2]);
            }
            if (parts.length == 3 && parts[1].equals("0")) {
                return 0; // JAVA_0_9
            }
            return Integer.parseInt(parts[1]);
        } catch (RuntimeException e) {
            return CURRENT; // unknown constant: don't fail the caller
        }
    }
}
