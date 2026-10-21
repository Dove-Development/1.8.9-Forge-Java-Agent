package dev.dove.agent.patches.asm;

import dev.dove.agent.transform.DoveTransformer;
import dev.dove.agent.transform.PatchRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Runs a patched copy of the test class path's own ASM 9 {@code Type} in an isolated loader. */
class AsmTypePatchTest {

    @Test
    void unpatchedAsm9RejectsInternalNames() throws Exception {
        InvocationTargetException e = assertThrows(InvocationTargetException.class,
                () -> getType(new AsmLoader(false), "net/minecraftforge/fml/common/eventhandler/Event"));
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    @Test
    void patchedAsm9TreatsThemLikeAsm5() throws Exception {
        Object type = getType(new AsmLoader(true), "net/minecraftforge/fml/common/eventhandler/Event");

        assertEquals(11, type.getClass().getMethod("getSort").invoke(type)); // Type.METHOD
        assertEquals("net/minecraftforge/fml/common/eventhandler/Event", type.toString());
    }

    @Test
    void validDescriptorsAreUnchanged() throws Exception {
        Object type = getType(new AsmLoader(true), "Ljava/lang/String;");

        assertEquals("java.lang.String", type.getClass().getMethod("getClassName").invoke(type));
    }

    private static Object getType(ClassLoader loader, String descriptor) throws Exception {
        return loader.loadClass("org.objectweb.asm.Type").getMethod("getType", String.class).invoke(null, descriptor);
    }

    /** Child-first loader for org.objectweb.asm, so the patched Type is the one used. */
    private static final class AsmLoader extends ClassLoader {
        private final boolean patch;

        AsmLoader(boolean patch) {
            super(AsmTypePatchTest.class.getClassLoader());
            this.patch = patch;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("org.objectweb.asm.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                String internal = name.replace('.', '/');
                byte[] bytes;
                try (InputStream in = getParent().getResourceAsStream(internal + ".class")) {
                    assertNotNull(in, internal);
                    bytes = in.readAllBytes();
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
                if (patch && internal.equals("org/objectweb/asm/Type")) {
                    PatchRegistry registry = new PatchRegistry();
                    registry.register(new AsmTypePatch());
                    byte[] patched = new DoveTransformer(registry).transform(this, internal, null, null, bytes);
                    assertNotNull(patched, "AsmTypePatch did not apply");
                    bytes = patched;
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
    }
}
