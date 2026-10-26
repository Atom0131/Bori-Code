package org.sqlite;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Stands in for sqlite-jdbc's driver when symbol processors run on the phone (copyBuildTools
 * bundles it ahead of downloaded processors). Room's processor opens an SQLite database through
 * it to check queries at build time; sqlite-jdbc needs a native library that can't be loaded
 * here, and fails in a way Room doesn't catch. This driver fails with an SQLException instead,
 * so Room warns that it skipped query verification and generates the code as usual.
 */
public class JDBC implements Driver {
    private static final String MESSAGE = "SQLite isn't available to symbol processors on Android; Room skips build-time query verification";

    static {
        try {
            java.sql.DriverManager.registerDriver(new JDBC());
        } catch (SQLException ignored) {
        }
    }

    public static boolean isValidURL(String url) {
        return url != null && url.toLowerCase().startsWith("jdbc:sqlite:");
    }

    public static SQLiteConnection createConnection(String url, Properties prop) throws SQLException {
        throw new SQLException(MESSAGE);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        throw new SQLException(MESSAGE);
    }

    @Override
    public boolean acceptsURL(String url) {
        return isValidURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 3;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
