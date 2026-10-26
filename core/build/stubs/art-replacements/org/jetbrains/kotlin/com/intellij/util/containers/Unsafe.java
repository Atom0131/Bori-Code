package org.jetbrains.kotlin.com.intellij.util.containers;

import java.lang.reflect.Field;

/**
 * Replaces the Kotlin compiler's IntelliJ Unsafe wrapper (copyBuildTools swaps it into the
 * patched kotlinc.jar). The original finds each sun.misc.Unsafe method through MethodHandles,
 * which fails on Android and leaves them null; this calls ART's sun.misc.Unsafe directly.
 */
public final class Unsafe {
    private static final sun.misc.Unsafe U;

    static {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Unsafe() {}

    public static boolean compareAndSwapInt(Object o, long offset, int expected, int x) {
        return U.compareAndSwapInt(o, offset, expected, x);
    }

    public static boolean compareAndSwapLong(Object o, long offset, long expected, long x) {
        return U.compareAndSwapLong(o, offset, expected, x);
    }

    public static int getAndAddInt(Object o, long offset, int delta) {
        int v;
        do {
            v = U.getIntVolatile(o, offset);
        } while (!U.compareAndSwapInt(o, offset, v, v + delta));
        return v;
    }

    public static Object getObjectVolatile(Object o, long offset) {
        return U.getObjectVolatile(o, offset);
    }

    public static boolean compareAndSwapObject(Object o, long offset, Object expected, Object x) {
        return U.compareAndSwapObject(o, offset, expected, x);
    }

    public static void putObjectVolatile(Object o, long offset, Object x) {
        U.putObjectVolatile(o, offset, x);
    }

    public static long objectFieldOffset(Field f) {
        return U.objectFieldOffset(f);
    }

    public static int arrayIndexScale(Class<?> arrayClass) {
        return U.arrayIndexScale(arrayClass);
    }

    public static int arrayBaseOffset(Class<?> arrayClass) {
        return U.arrayBaseOffset(arrayClass);
    }

    public static void copyMemory(Object src, long srcOffset, Object dest, long destOffset, long bytes) {
        // ART's Unsafe has no object-to-object copyMemory; copy a byte at a time.
        for (long i = 0; i < bytes; i++) {
            U.putByte(dest, destOffset + i, U.getByte(src, srcOffset + i));
        }
    }
}
