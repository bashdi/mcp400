package com.github.bashdi.mcp400;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Service
public class MCP400Service {

    private Connection connection;

    @PostConstruct
    public void init() throws Exception {
        String address = System.getenv("AS400_ADDRESS");
        String username = System.getenv("AS400_USER");
        String password = System.getenv("AS400_PASSWORD");

        if (address == null || username == null || password == null) {
            throw new IllegalStateException("AS400_IP, AS400_USER or AS400_PASSWORD missing!");
        }

        String connectionString = "jdbc:as400:" + address + ";prompt=false;translate binary=true;naming=system";
        this.connection = DriverManager.getConnection(connectionString, username, password);
    }

    @Tool(name = "mcp400_get_all_tables_for_schema", description = "Gets a list of all tables(name and description) in a specific schema on the database/as400/ibmi")
    public List<Table> getAllTablesForSchema(String schema) throws SQLException {
        List<Table> tables = new ArrayList<>();
        PreparedStatement preparedStatement = connection.prepareStatement("SELECT table_schema, table_name, table_text, LAST_ALTERED_TIMESTAMP\n" +
                "FROM QSYS2.SYSTABLES where table_schema = ? and table_type IN ('P', 'T') ORDER BY LAST_ALTERED_TIMESTAMP DESC");
        preparedStatement.setString(1, schema.toUpperCase());
        preparedStatement.addBatch();

        ResultSet resultSet = preparedStatement.executeQuery();
        while(resultSet.next()) {
            tables.add(new Table(resultSet.getString(1),resultSet.getString(2), resultSet.getString(3)));
        }
        resultSet.close();
        preparedStatement.close();
        return tables;
    }

    @Tool(name = "mcp400_get_information_about_table", description = "Gets a list of all columns(name, description, datatype), index and primary key by schema and tableName")
    public TableInformation getAllColumnsForTable(String schema, String tableName) throws SQLException {
        schema = schema.toUpperCase();
        tableName = tableName.toUpperCase();
        //Columns
        List<Column> columns = new ArrayList<>();
        DatabaseMetaData databaseMetaData = connection.getMetaData();
        ResultSet resultSet = databaseMetaData.getColumns(null, schema, tableName, null);

        while(resultSet.next()) {
            String columnName = resultSet.getString("COLUMN_NAME");
            String columnDatatype = resultSet.getString("TYPE_NAME");
            String columnDescription = resultSet.getString("REMARKS");
            boolean isAutoIncremented = resultSet.getString("IS_AUTOINCREMENT").equals("YES");
            columns.add(new Column(columnName, columnDescription, columnDatatype, isAutoIncremented));
        }
        resultSet.close();


        //PrimaryKey
        List<String> primaryKeyColumns = new ArrayList<>();

        resultSet = databaseMetaData.getPrimaryKeys(null, schema, tableName);
        while(resultSet.next()) {
           primaryKeyColumns.add(resultSet.getString("COLUMN_NAME"));
        }
        resultSet.close();

        //ForeignKeys
        List<ForeignKey> foreignKeysColumns = new ArrayList<>();
        resultSet = databaseMetaData.getImportedKeys(null, schema, tableName);
        while (resultSet.next()) {
            foreignKeysColumns.add(new ForeignKey(resultSet.getString("PKTABLE_SCHEM"),
                    resultSet.getString("PKTABLE_NAME"),
                    resultSet.getString("PKCOLUMN_NAME"),
                    resultSet.getString("FKCOLUMN_NAME")));
        }
        resultSet.close();

        //Indizes
        List<Index> indexList = new ArrayList<>();
        resultSet = databaseMetaData.getIndexInfo(null, schema, tableName, false, false);

        String lastIndexName = null;
        Index currentIndex = null;

        while(resultSet.next()) {
            String indexName = resultSet.getString("INDEX_NAME");
            String columnName = resultSet.getString("COLUMN_NAME");

            if (currentIndex == null || !lastIndexName.equals(indexName)) {
                currentIndex = new Index(indexName, new ArrayList<>());
                indexList.add(currentIndex);
                lastIndexName = indexName;
            }
            currentIndex.columnsInOrder().add(columnName);
        }
        resultSet.close();

        return new TableInformation(columns, primaryKeyColumns, foreignKeysColumns, indexList);
    }

    @Tool(name = "mcp400_get_schemas_for_table", description = "Gets a list of schemas containing the table")
    public List<Table> getSchemaForTable(String tableName) throws SQLException {
        List<Table> schemas = new ArrayList<>();
        PreparedStatement preparedStatement = connection.prepareStatement("SELECT table_schema, table_name, table_text, LAST_ALTERED_TIMESTAMP\n" +
                "FROM QSYS2.SYSTABLES WHERE table_name = ? ORDER BY LAST_ALTERED_TIMESTAMP desc");
        preparedStatement.setString(1, tableName.toUpperCase());
        preparedStatement.addBatch();

        ResultSet resultSet = preparedStatement.executeQuery();
        while(resultSet.next()) {
            schemas.add(new Table(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)));
        }
        resultSet.close();
        preparedStatement.close();
        return schemas;
    }

}
