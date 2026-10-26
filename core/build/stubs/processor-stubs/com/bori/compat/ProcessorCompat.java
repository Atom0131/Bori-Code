package com.bori.compat;

import java.lang.reflect.AccessibleObject;

/**
 * JDK methods the Kotlin compiler, KSP and symbol processors call that Android doesn't have
 * (Java 9+ reflection, java.beans, java.awt). copyBuildTools (for the bundled compiler and KSP)
 * and ProcessorRewriter (for downloaded processors) point those calls here.
 */
public final class ProcessorCompat {
    private ProcessorCompat() {}

    /** AccessibleObject.trySetAccessible() (Java 9). */
    public static boolean trySetAccessible(AccessibleObject target) {
        try {
            target.setAccessible(true);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** java.beans.Introspector.decapitalize: "FooBar" → "fooBar", but "URL" stays "URL". */
    public static String decapitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) return name;
        char[] chars = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    /** java.awt.EventQueue.isDispatchThread(): there's no AWT event thread on Android. */
    public static boolean isDispatchThread() {
        return false;
    }

    /** java.awt.EventQueue.invokeLater / invokeAndWait: run it now, on this thread. */
    public static void invokeLater(Runnable runnable) {
        runnable.run();
    }

    public static void invokeAndWait(Runnable runnable) {
        runnable.run();
    }
}
