package org.sqlite;

/** Room calls this before using the driver; there's no native library to load (see JDBC). */
public class SQLiteJDBCLoader {
    public static synchronized boolean initialize() {
        return true;
    }
}
