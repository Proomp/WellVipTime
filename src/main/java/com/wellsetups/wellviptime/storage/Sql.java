package com.wellsetups.wellviptime.storage;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class Sql {

    @FunctionalInterface
    public interface Row<T> {

        T read(ResultSet result) throws SQLException;
    }

    private Sql() {}

    public static int update(Connection connection, String sql, Object... values)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    public static <T> List<T> query(Connection connection, String sql, Row<T> row, Object... values)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (var result = statement.executeQuery()) {
                List<T> list = new ArrayList<>();
                while (result.next()) {
                    list.add(row.read(result));
                }
                return List.copyOf(list);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        statement.setQueryTimeout(10);
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }
}
