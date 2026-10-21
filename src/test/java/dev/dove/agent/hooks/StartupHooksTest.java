package dev.dove.agent.hooks;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupHooksTest {

    private static final Pattern MOD_CLASS = Pattern.compile(".*(\\.|)(mod\\_[^\\s$]+)$");

    @Test
    void modLoaderTestAgreesWithForgesRegex() {
        String[] fixed = {"", "mod_", "mod_a", "a.mod_b", "com.example.mod_Thing", "mod_a$b", "mod_ a", "mod_mod_",
                "x.mod_mod_", "mod_a mod_", "mod_a mod_b", "a$mod_b", "Mod_a", "mod__", "net.minecraft.client.Minecraft",
                "com.doveclient.client.module.PerformanceModule", "mod_\t", "a.b.c.mod_$", "mod_x$mod_y"};
        for (String name : fixed) {
            assertEquals(MOD_CLASS.matcher(name).find(), StartupHooks.find(MOD_CLASS, name), name);
        }
        // Random names built from the characters that matter to the regex.
        char[] alphabet = {'m', 'o', 'd', '_', 'a', '.', '$', ' ', '\t', 'x', 'M', '/'};
        Random random = new Random(1189);
        for (int i = 0; i < 200_000; i++) {
            StringBuilder name = new StringBuilder();
            int length = random.nextInt(14);
            for (int j = 0; j < length; j++) {
                name.append(random.nextInt(3) == 0 ? "mod_" : String.valueOf(alphabet[random.nextInt(alphabet.length)]));
            }
            String s = name.toString();
            assertEquals(MOD_CLASS.matcher(s).find(), StartupHooks.find(MOD_CLASS, s), s);
        }
    }

    @Test
    void otherPatternsAndLineTerminatorsUseTheRegex() {
        Pattern other = Pattern.compile("^abc");
        assertTrue(StartupHooks.find(other, "abcdef"));
        assertFalse(StartupHooks.find(other, "xabc"));
        assertEquals(MOD_CLASS.matcher("mod_a\n").find(), StartupHooks.find(MOD_CLASS, "mod_a\n"));
        assertEquals(MOD_CLASS.matcher("mod_ ").find(), StartupHooks.find(MOD_CLASS, "mod_ "));
    }
}
