package dev.dove.agent.hooks;

import jdk.internal.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Replacements for the Java 8 reflection tricks Forge and mods use to write final fields and
 * create enum constants at runtime.
 *
 * <p>Java 8 code removed {@code FINAL} through the hidden {@code Field.modifiers} field and then used
 * {@code sun.reflect.ReflectionFactory} accessors. Both are gone. Static final fields are written
 * here through the JDK's internal Unsafe instead, which is what those accessors did underneath.
 */
public final class ReflectionHooks {

    public static final String OWNER = "dev/dove/agent/hooks/ReflectionHooks";

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final Field MODIFIERS;

    static {
        try {
            MODIFIERS = ModifiersStandIn.class.getDeclaredField("modifiers");
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ReflectionHooks() {
    }

    // ---- Field writes ---------------------------------------------------------------------------

    /**
     * Sets a field even if it is static final, like Forge's {@code EnumHelper.setFailsafeFieldValue}
     * did on Java 8.
     */
    public static void setField(Field field, Object target, Object value) throws IllegalAccessException {
        int mods = field.getModifiers();
        if (Modifier.isStatic(mods) && Modifier.isFinal(mods)) {
            writeStaticFinal(field, value);
            return;
        }
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void writeStaticFinal(Field field, Object value) {
        Class<?> owner = field.getDeclaringClass();
        UNSAFE.ensureClassInitialized(owner);
        Object base = UNSAFE.staticFieldBase(field);
        long offset = UNSAFE.staticFieldOffset(field);
        Class<?> type = field.getType();
        if (!type.isPrimitive()) {
            if (value != null && !type.isInstance(value)) {
                throw new IllegalArgumentException("Cannot set " + type.getName() + " field " + owner.getName() + "."
                        + field.getName() + " to " + value.getClass().getName());
            }
            UNSAFE.putReferenceVolatile(base, offset, value);
        } else if (type == int.class) {
            UNSAFE.putIntVolatile(base, offset, ((Number) value).intValue());
        } else if (type == boolean.class) {
            UNSAFE.putBooleanVolatile(base, offset, (Boolean) value);
        } else if (type == long.class) {
            UNSAFE.putLongVolatile(base, offset, ((Number) value).longValue());
        } else if (type == float.class) {
            UNSAFE.putFloatVolatile(base, offset, ((Number) value).floatValue());
        } else if (type == double.class) {
            UNSAFE.putDoubleVolatile(base, offset, ((Number) value).doubleValue());
        } else if (type == byte.class) {
            UNSAFE.putByteVolatile(base, offset, ((Number) value).byteValue());
        } else if (type == short.class) {
            UNSAFE.putShortVolatile(base, offset, ((Number) value).shortValue());
        } else {
            UNSAFE.putCharVolatile(base, offset, (Character) value);
        }
    }

    // ---- Enum constants -------------------------------------------------------------------------

    /**
     * Creates a new enum constant, replacing Forge's {@code EnumHelper.makeEnum}. Method handles may
     * call enum constructors, which {@code Constructor.newInstance} refuses to do.
     */
    public static Object makeEnum(Class<?> enumClass, String name, int ordinal, Class<?>[] paramTypes,
                                  Object[] paramValues) throws Exception {
        Class<?>[] types = new Class<?>[paramTypes.length + 2];
        types[0] = String.class;
        types[1] = int.class;
        System.arraycopy(paramTypes, 0, types, 2, paramTypes.length);
        Object[] args = new Object[paramValues.length + 2];
        args[0] = name;
        args[1] = ordinal;
        System.arraycopy(paramValues, 0, args, 2, paramValues.length);

        MethodHandle constructor = MethodHandles.privateLookupIn(enumClass, MethodHandles.lookup())
                .findConstructor(enumClass, MethodType.methodType(void.class, types));
        try {
            return constructor.invokeWithArguments(args);
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    // ---- Forge's ObjectHolderRef / ItemStackHolderRef ---------------------------------------------

    /**
     * Replaces both {@code Method.invoke} calls in the holder refs' {@code apply()}:
     * {@code newFieldAccessor.invoke(factory, field, false)} returns the field itself as the
     * "accessor", and {@code fieldAccessorSet.invoke(accessor, null, value)} writes it.
     */
    public static Object holderRefInvoke(Method method, Object target, Object[] args) throws Exception {
        if (args != null && args.length == 2) {
            if (args[0] instanceof Field field && args[1] instanceof Boolean) {
                return field;
            }
            if (target instanceof Field field) {
                setField(field, args[0], args[1]);
                return null;
            }
        }
        return method.invoke(target, args);
    }

    // ---- The Field.modifiers trick in mods --------------------------------------------------------

    /** Replaces {@code Class.getDeclaredField}: hands out a stand-in for {@code Field.modifiers}. */
    public static Field getDeclaredField(Class<?> owner, String name) throws NoSuchFieldException {
        if (owner == Field.class && "modifiers".equals(name)) {
            return MODIFIERS;
        }
        return owner.getDeclaredField(name);
    }

    /** Replaces {@code Field.getInt}: reading the stand-in returns the target field's modifiers. */
    public static int getInt(Field field, Object target) throws IllegalAccessException {
        if (field == MODIFIERS && target instanceof Field targetField) {
            return targetField.getModifiers();
        }
        return field.getInt(target);
    }

    /**
     * Replaces {@code Field.setInt}: writing the stand-in is a no-op, since {@link #set} below
     * handles final fields whether or not their modifiers were "changed".
     */
    public static void setInt(Field field, Object target, int value) throws IllegalAccessException {
        if (field == MODIFIERS && target instanceof Field) {
            return;
        }
        if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
            writeStaticFinal(field, value);
            return;
        }
        field.setInt(target, value);
    }

    /** Replaces {@code Field.set} in classes using the modifiers trick: static finals go through Unsafe. */
    public static void set(Field field, Object target, Object value) throws IllegalAccessException {
        if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
            writeStaticFinal(field, value);
            return;
        }
        field.set(target, value);
    }

    /** Owner of the field returned in place of {@code Field.modifiers}. */
    @SuppressWarnings("unused")
    private static final class ModifiersStandIn {
        private int modifiers;
    }
}
