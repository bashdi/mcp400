# mcp400 - MCP-server for AS400/DB2 for i

An MCP server (official MCP Java SDK, stdio) that gives AI agents context about **Db2 for i** (IBM i / AS400) databases so they can find the right tables and write correct, well-performing SQL.

**It only reads the system catalog (`QSYS2`), never table data.** The connection is opened read-only. All results are returned as compact plain text (no JSON) to save context: optional values are omitted, long lists are capped, long sources are truncated.

## Tools

### Finding tables (also when names are cryptic)

| Tool | Description |
|---|---|
| `mcp400_search_columns(pattern, [schema])` | Searches column names, system names, descriptions and column headings in all user schemas. Tables are grouped and ranked by number of matching columns. Best entry point for legacy names like `KDNR`. |
| `mcp400_search_tables(pattern, [schema])` | Searches table/view names, system names and descriptions. |
| `mcp400_get_all_tables_for_schema(schema, [filter], [includeLogicals])` | Lists the objects of a schema (name, system name, type, description). Source files are skipped, logical files are hidden by default. Max. 300 entries. |
| `mcp400_get_schemas_for_table(table)` | Schemas (libraries) that contain an object with this name (SQL or system name), newest first. |

### Understanding tables

| Tool | Description |
|---|---|
| `mcp400_get_all_columns_for_table(schema, table)` | Columns in order: type, nullability, default, system name, description or heading. |
| `mcp400_get_information_about_table(schema, table)` | Object type (`T`/`P`/`L`/`V`/`A`/`M`), description, system name, approx. row count, last change/use, primary key, foreign keys, check/unique constraints, indexes and keyed logical files with ordered key columns. |
| `mcp400_get_systemnames_for_table(schema, table)` | 10 character system name of the table and of all columns whose SQL name differs. |
| `mcp400_get_dependent_objects(schema, table)` | Views/logical files over the table, child tables (foreign keys), aliases, triggers. |
| `mcp400_get_view_definition(schema, view)` | SQL source of a view (joins, filters, business rules). |

### Writing and checking SQL

| Tool | Description |
|---|---|
| `mcp400_validate_sql(sql)` | Prepares a single `SELECT`/`WITH`/`VALUES` statement **without executing it**. Returns SQLSTATE/SQLCODE and message on errors, otherwise the result columns with types. |
| `mcp400_get_index_advice(schema, table)` | Indexes recommended by the optimizer (`QSYS2.SYSIXADV`), most requested first. |

### Logic in the database

| Tool | Description |
|---|---|
| `mcp400_get_triggers_for_table(schema, table)` | Triggers with timing, event and source. |
| `mcp400_get_triggers_for_schema(schema)` | One line per trigger of all tables in a schema. |
| `mcp400_get_routines_for_schema(schema)` | Stored procedures and functions with language and comment. |
| `mcp400_get_routine_info(schema, name)` | Parameters, comment and source of a routine. |

### Knowledge base (optional, off by default)

Lets the agent keep what it learned about the database (relations without foreign keys, business rules, meaning of coded values, data quality problems) between sessions. The tools only exist when it is enabled.

| Tool | Description |
|---|---|
| `mcp400_save_knowledge(info_type, knowledge_text, [knowledge_id], [schema_name], [table_name], [column_name])` | Creates an entry, or overwrites the entry with `knowledge_id` (omitted schema/table/column keep their value). `info_type`: `RELATION`, `BUSINESS_RULE`, `ENUM_VALUES`, `DESCRIPTION`, `DATA_QUALITY`. Text max. 2000 characters (short, one topic per entry). If the same object and type already has entries, the answer names them so the agent can merge. |
| `mcp400_get_knowledge_by_database_object(schema_name, [table_name], [column_name], [knowledge_id])` | Entries for a schema, narrowed to a table and column, grouped by object. More general entries (no table/column, or no schema) are included. Only a 300 character preview per entry is returned; pass `knowledge_id` to get one entry in full. Max. 50 entries. |
| `mcp400_delete_knowledge(knowledge_id)` | Deletes an entry. |

Setup:

- `AS400_KNOWLEDGE=true` enables the knowledge base (default: disabled).
- `AS400_KNOWLEDGE_TABLE` (optional) sets another table as `LIBRARY.TABLE` (or only `TABLE` for the user's library). Default: `MCP_KNOWLEDGE` in the library that is named like the user (`AS400_USER`).
- The table (`KNOWLEDGE_ID` identity, `SCHEMA_NAME`, `TABLE_NAME`, `COLUMN_NAME`, `INFO_TYPE`, `KNOWLEDGE_TEXT` CLOB(10K), `CREATED_BY`, `CREATED_AT`, `UPDATED_AT`, plus an index on schema/table/column) is created on first use if it does not exist. The library must exist and the user needs the authority to create tables in it, otherwise the tool returns an error that says so.
- This is the only feature that writes. It uses its own connection, the catalog tools stay on a read-only connection. Schema, table and column names are stored in upper case.

All tools that take a table accept the long SQL name **or** the 10 character system name.

## Reliability and security

- **Reconnect**: if the connection to the IBM i is lost (network, idle timeout, IBM i restart), the server reconnects once and repeats the call. No restart of the MCP server is needed.
- **Knowledge hint**: with the knowledge base enabled, `mcp400_get_information_about_table` adds a line like `Knowledge: 2 entries stored for this table/its columns`, so the agent knows it should read them.
- **Untrusted text**: descriptions, comments, sources of views/routines/triggers and knowledge notes are written by other people. The server instructions tell the agent to treat them as data and never follow instructions found in them; knowledge results and routine sources are labelled as data. Restrict who may write to the knowledge table, and use an IBM i user with read-only rights for the catalog.

## Releases

Pushing a tag that starts with `v` builds the jar with Maven (Java 17) and publishes it as `mcp400.jar` on a GitHub release (`.github/workflows/release.yml`):

```
git tag v0.1.0
git push origin v0.1.0
```

## Context efficiency

Everything the agent receives is kept short:

- Notation, type legend and SQL guidance are sent **once** in the server instructions; tool descriptions are one sentence and results do not repeat legends or headers.
- Results are plain text, empty values are omitted, lists and sources are capped and truncated.
- Knowledge: entries are limited to 2000 characters, reads return grouped 300 character previews (full text on demand), and saving points out entries that should be merged so the knowledge base does not grow with duplicates.

## Db2 for i specifics

The server sends instructions to the agent and marks the following in its output:

- **Two names for everything**: SQL name (up to 128 characters) and system name (10 characters). Both are shown when they differ and both are accepted by the tools and by SQL.
- **Object types**: `T` SQL table, `P` DDS physical file, `L` DDS logical file (alternate access path, hidden by default), `V` view, `A` alias, `M` materialized query table. Source physical files (`QRPGLESRC`, ...) are filtered out.
- **Descriptions**: `COLUMN_TEXT` and `COLUMN_HEADING` are the main documentation in DDS-based systems, so they are returned and searched.
- **Data types**: `NUMERIC` is zoned decimal, `DECIMAL` is packed decimal; dates are often stored as `YYYYMMDD`/`CYYMMDD` numbers or strings; `CCSID 65535` columns are flagged because they are not converted.
- **Performance hints**: index key columns are listed in order (filter on the leading columns), the optimizer's index advice is available, SQL hints like `FETCH FIRST n ROWS ONLY`, `OPTIMIZE FOR n ROWS`, `WITH UR` are part of the agent instructions.
- **Naming**: the connection uses `naming=system`; the agent is told to qualify tables as `SCHEMA.TABLE`.

### Limitations

- RPG/COBOL/CL programs that use a file are not visible through SQL catalogs.
- Row counts are approximate catalog statistics.
- Some catalog columns/views depend on the IBM i release and technology refresh. Optional parts are skipped (or reported as "unavailable") instead of failing the whole call.
- `validate_sql` accepts one read-only statement; a `;` inside a string literal is rejected.

## Usage in vscode with Github Copilot

1. Download `mcp400.jar` from the latest [GitHub release](../../releases/latest)
2. Follow the instructions: https://code.visualstudio.com/docs/copilot/chat/mcp-servers


You need to specify the environment-variables:
AS400_ADDRESS, AS400_USER and AS400_PASSWORD.

### Example .vscode/.mcp.json

```
{
    "inputs": [
        {
            "type": "promptString",
            "id": "AS400_ADDRESS",
            "description": "as400 address",
            "password": false
        },
        {
            "type": "promptString",
            "id": "AS400_USER",
            "description": "as400 user",
            "password": false
        },
        {
            "type": "promptString",
            "id": "AS400_PASSWORD",
            "description": "as400 password",
            "password": true
        }
    ],
    "servers": {
        "mcp400": {
            "type": "stdio",
            "command": "java",
            "args": [
                "-jar",
                "C:\\Users\\bashdi\\Downloads\\mcp400.jar"
            ],
            "env": {
                "AS400_ADDRESS": "${input:AS400_ADDRESS}",
                "AS400_USER": "${input:AS400_USER}",
                "AS400_PASSWORD": "${input:AS400_PASSWORD}",
                "AS400_KNOWLEDGE": "false"
            }
        }
    }
}
```
On mcp-server-start, VSCode will ask you for your credentials and save them securely