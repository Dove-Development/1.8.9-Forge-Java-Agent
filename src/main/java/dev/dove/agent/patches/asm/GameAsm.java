package dev.dove.agent.patches.asm;

/**
 * Internal names of the game's (unshaded) ASM classes.
 *
 * <p>Built at runtime on purpose: the jar's shading relocates every {@code org/objectweb/asm}
 * string literal to the agent's own relocated copy, which would point these patches at the wrong ASM.
 */
public final class GameAsm {

    public static final String PACKAGE = String.join("/", "org", "objectweb", "asm");
    public static final String TYPE = PACKAGE + "/Type";
    public static final String CLASS_READER = PACKAGE + "/ClassReader";
    /** Mixin 0.7 bundles its own ASM 5.2, relocated here. */
    public static final String MIXIN_CLASS_READER = "org/spongepowered/asm/lib/ClassReader";

    private GameAsm() {
    }
}
