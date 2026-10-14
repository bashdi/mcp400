package com.github.bashdi.mcp400.core;

import com.ibm.as400.access.AS400;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** JDBC access to the IBM i shared by all capabilities: settings from the environment and reconnect after a lost link. */
public final class As400Connection {

    private static final List<Reconnectable> RECONNECTABLES = new CopyOnWriteArrayList<>();

    private As400Connection() {
    }

    /** Registers a service whose connection is replaced when the link to the IBM i was lost. */
    public static void register(Reconnectable reconnectable) {
        RECONNECTABLES.add(reconnectable);
    }

    /** Lets every registered service replace its connection. */
    public static void reconnectAll() {
        for (Reconnectable r : RECONNECTABLES) {
            try {
                r.reconnect();
            } catch (Exception reconnectFailed) {
                throw new IllegalStateException("Connection to the IBM i lost and reconnect failed: " + reconnectFailed.getMessage(), reconnectFailed);
            }
        }
    }

    /** True if the exception (or a cause) says that the connection to the IBM i is gone. */
    public static boolean isConnectionLost(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                String message = sql.getMessage() == null ? "" : sql.getMessage().toLowerCase();
                if ((state != null && state.startsWith("08")) || message.contains("communication link failure")
                        || message.contains("connection does not exist") || message.contains("connection is closed")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A jt400 system object for IFS access, with the same settings as openConnection. Caller disconnects it. */
    public static AS400 system() {
        String address = System.getenv("AS400_ADDRESS");
        String username = System.getenv("AS400_USER");
        String password = System.getenv("AS400_PASSWORD");
        if (address == null || username == null || password == null) {
            throw new IllegalStateException("AS400_ADDRESS, AS400_USER or AS400_PASSWORD missing!");
        }
        return new AS400(address, username, password);
    }

    /** Opens a connection from the AS400_ADDRESS, AS400_USER and AS400_PASSWORD environment variables. */
    public static Connection openConnection() throws SQLException {
        String address = System.getenv("AS400_ADDRESS");
        String username = System.getenv("AS400_USER");
        String password = System.getenv("AS400_PASSWORD");

        if (address == null || username == null || password == null) {
            throw new IllegalStateException("AS400_ADDRESS, AS400_USER or AS400_PASSWORD missing!");
        }

        String connectionString = "jdbc:as400:" + address + ";prompt=false;translate binary=true;naming=system";
        return DriverManager.getConnection(connectionString, username, password);
    }
}
