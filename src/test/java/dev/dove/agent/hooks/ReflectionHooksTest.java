package dev.dove.agent.hooks;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReflectionHooksTest {

    enum Material {
        IRON(15);

        final int durability;

        Material(int durability) {
            this.durability = durability;
        }
    }

    static final class Holder {
        static final Object ITEM = new Object();
        static final int COUNT = Integer.parseInt("1"); // not a compile-time constant, so reads see writes
    }

    @Test
    void makeEnumRunsTheEnumConstructor() throws Exception {
        Object made = ReflectionHooks.makeEnum(Material.class, "DOVE", 1, new Class<?>[]{int.class}, new Object[]{99});

        Material material = (Material) made;
        assertEquals("DOVE", material.name());
        assertEquals(1, material.ordinal());
        assertEquals(99, material.durability);
    }

    @Test
    void setFieldWritesStaticFinals() throws Exception {
        Object replacement = new Object();
        ReflectionHooks.setField(Holder.class.getDeclaredField("ITEM"), null, replacement);
        ReflectionHooks.setField(Holder.class.getDeclaredField("COUNT"), null, 7);

        assertSame(replacement, Holder.ITEM);
        assertEquals(7, Holder.COUNT);
    }

    @Test
    void modifiersTrickWorks() throws Exception {
        // What mods do on Java 8.
        Field target = Holder.class.getDeclaredField("ITEM");
        Field modifiers = ReflectionHooks.getDeclaredField(Field.class, "modifiers");
        modifiers.setAccessible(true);
        int mods = ReflectionHooks.getInt(modifiers, target);
        ReflectionHooks.setInt(modifiers, target, mods & ~Modifier.FINAL);
        Object replacement = new Object();
        ReflectionHooks.set(target, null, replacement);

        assertEquals(target.getModifiers(), mods);
        assertSame(replacement, Holder.ITEM);
    }

    @Test
    void holderRefInvokeActsAsFieldAccessor() throws Exception {
        Field target = Holder.class.getDeclaredField("ITEM");
        Object accessor = ReflectionHooks.holderRefInvoke(null, null, new Object[]{target, false});
        Object replacement = new Object();
        ReflectionHooks.holderRefInvoke(null, accessor, new Object[]{null, replacement});

        assertSame(target, accessor);
        assertSame(replacement, Holder.ITEM);
    }
}
