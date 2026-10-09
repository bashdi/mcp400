package com.github.bashdi.mcp400;

import java.sql.SQLException;

/** A service that owns a JDBC connection which can be replaced after the connection to the IBM i was lost. */
public interface Reconnectable {

    void reconnect() throws SQLException;
}
