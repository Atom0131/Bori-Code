package javax.swing;

/**
 * Swing's Icon interface. The Kotlin compiler's IntelliJ core gives file types and PSI elements an
 * icon (DummyIcon implements this), and loads those classes when Kotlin code uses a Java source
 * class. Nothing paints them on Android.
 */
public interface Icon {
    void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y);

    int getIconWidth();

    int getIconHeight();
}
