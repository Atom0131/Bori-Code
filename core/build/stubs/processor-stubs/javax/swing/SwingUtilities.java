package javax.swing;

/**
 * The part of Swing the Kotlin compiler's IntelliJ core calls while KSP runs
 * (TransactionGuardImpl). There's no Swing event thread on Android.
 */
public class SwingUtilities {
    public static boolean isEventDispatchThread() {
        return false;
    }

    public static void invokeLater(Runnable runnable) {
        runnable.run();
    }
}
