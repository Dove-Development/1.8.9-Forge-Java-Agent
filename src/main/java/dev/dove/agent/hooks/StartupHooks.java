package dev.dove.agent.hooks;

import dev.dove.agent.util.ClassFiles;

import java.util.regex.Pattern;

/**
 * Shortcuts for work Forge does while the game loads that cannot change the result (see the
 * patches in {@code patches/startup}). Each answers from the class bytes alone, and says "do the
 * work" whenever it cannot be sure.
 */
public final class StartupHooks {

    public static final String OWNER = "dev/dove/agent/hooks/StartupHooks";

    private static final String SIDE_ONLY = "Lnet/minecraftforge/fml/relauncher/SideOnly;";
    private static final String SUBSCRIBE_EVENT = "Lnet/minecraftforge/fml/common/eventhandler/SubscribeEvent;";
    /** ModContainerFactory's test for ModLoader-style {@code mod_} classes. */
    private static final String MOD_CLASS_REGEX = ".*(\\.|)(mod\\_[^\\s$]+)$";

    private StartupHooks() {
    }

    /** SideTransformer only strips members annotated {@code @SideOnly}. */
    public static boolean skipSideTransformer(byte[] classFile) {
        return classFile == null || !ClassFiles.hasUtf8(classFile, SIDE_ONLY);
    }

    /** EventSubscriberTransformer only makes {@code @SubscribeEvent} methods public. */
    public static boolean skipEventSubscriberTransformer(byte[] classFile) {
        return classFile == null || !ClassFiles.hasUtf8(classFile, SUBSCRIBE_EVENT);
    }

    /** TerminalTransformer only reroutes calls to {@code System.exit}, {@code Runtime.exit} and {@code Runtime.halt}. */
    public static boolean skipTerminalTransformer(byte[] classFile) {
        return classFile == null || !ClassFiles.hasUtf8(classFile, "exit", "halt");
    }

    /**
     * Replaces {@code pattern.matcher(input).find()}. For ModContainerFactory's {@code mod_} test it
     * answers without the regex, whose leading {@code .*} backtracks over every class name in every
     * mod; anything else goes to the regex.
     */
    public static boolean find(Pattern pattern, CharSequence input) {
        if (!MOD_CLASS_REGEX.equals(pattern.pattern()) || hasLineTerminator(input)) {
            return pattern.matcher(input).find();
        }
        return isModLoaderName(input);
    }

    /**
     * What {@code .*(\.|)(mod\_[^\s$]+)$} finds in input without line terminators: "mod_" followed
     * by at least one character, none of them whitespace or '$', up to the end.
     */
    static boolean isModLoaderName(CharSequence input) {
        String s = input.toString();
        int lastBad = -1;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '$' || c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r') {
                lastBad = i;
                break;
            }
        }
        // A "mod_" whose tail starts after the last bad character; earlier ones have a longer tail.
        for (int at = s.lastIndexOf("mod_"); at >= 0 && at + 4 > lastBad; at = s.lastIndexOf("mod_", at - 1)) {
            if (at + 4 < s.length()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLineTerminator(CharSequence input) {
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\n' || c == '\r' || c == '\u0085' || c == ' ' || c == ' ') {
                return true;
            }
        }
        return false;
    }
}
