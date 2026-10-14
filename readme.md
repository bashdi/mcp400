# mcp400 - MCP-servers for AS400/DB2 for i

MCP servers (official MCP Java SDK, stdio) that give AI agents context about **Db2 for i** (IBM i / AS400) so they can find the right tables and write correct, well-performing SQL.

**The database part only reads the system catalog (`QSYS2`), never table data.** The connection is opened read-only. All results are returned as compact plain text (no JSON) to save context: optional values are omitted, long lists are capped, long sources are truncated.

## Servers

The project builds three servers. Each one is a fat jar with its own entry point:

| Jar | Capabilities | Entry point |
|---|---|---|
| `mcp400.jar` | all (database and programming) | `com.github.bashdi.mcp400.Mcp400` |
| `mcp400-db.jar` | database only: catalog tools, SQL check, knowledge base | `com.github.bashdi.mcp400.db.Mcp400Db` |
| `mcp400-pgm.jar` | programming only: compile a local source on the IBM i | `com.github.bashdi.mcp400.pgm.Mcp400Pgm` |

Modules:

| Module | Content |
|---|---|
| `mcp400-core` | Shared code: IBM i connection and reconnect, tool builder, server start. Not released on its own. |
| `mcp400-db` | Database capability (the tools below) |
| `mcp400-pgm` | Programming capability: compile tools (below) |
| `mcp400` | Aggregates `mcp400-db` and `mcp400-pgm` into `mcp400.jar` |

Build all jars with `mvn package`. Every version tag (`v*`) builds the three jars and attaches them to the GitHub release.

## Compile tools (`mcp400.jar`, `mcp400-pgm.jar`)

Compiles a local source on the IBM i without running it. Results are plain text lines.

| Tool | Description |
|---|---|
| `prepare_compile(source_path, [source_type], [compiler_options])` | Checks the local file and copies it unchanged into a temporary member in the source file `QTEMP/TMPSRC`. Returns a `compile_id`. |
| `compile_check(compile_id)` | Compiles the prepared member (`CRTRPGMOD`, `CRTSQLRPGI`, `CRTCLMOD`), reads the joblog and the listing (`QSYSPRT`), reports errors and warnings with line numbers, and removes the temporary objects. Status: `success`, `success_with_warnings`, `failed`, `infrastructure_error`. |

- `source_type`: `RPGLE`, `SQLRPGLE` or `CLLE`; otherwise taken from the file extension (`.rpgle`, `.sqlrpgle`, `.clle`).
- Copy members: every `/copy <file>,<member>` in the source is written to `QTEMP/<FILE>` as member `<member>`, so `/copy src,X` goes to `QTEMP/SRC` and `/copy qrpglesrc,X` to `QTEMP/QRPGLESRC`. The member file is taken from the folder of the source file: the one file whose name without extension is `<member>`. `copy_members` (separated by `;`) can name extra files explicitly, they are matched by member name. A member that is neither given nor found is an error. `QTEMP` is put first in the library list. Every compile adds `QDEVTOOLS` (it holds the RPG message file `QRPGLEMSG`). Libraries with the tables of the source are passed in `libraries` (comma-separated) and added at the end. Copy members are written as a stream file under `/tmp/mcp400` and copied into their member with `CPYFRMSTMF`; the stream file is deleted afterwards. Other `/copy` files (e.g. `yajl/qrpglesrc,yajl_h`) are not touched and are looked up in the library list (warning).
- The temporary source file is always `QTEMP/TMPSRC`; the member gets the file name. Objects created by the compile stay in `QTEMP` and are deleted again by `compile_check`.
- The file name is the member name: max. 10 characters, valid IBM i name.
- Limits: 512 KB, 9999 lines, 100 characters per line. Nothing is truncated silently.
- `compiler_options`: only `*XREF`, `*NOXREF`, `*SECLVL`, `*NOSECLVL`.
- Any local file the server process can read can be prepared. The server does not restrict the path, so the agent can send any readable file to the IBM i.
- Each compile uses its own IBM i connection and job. A `compile_id` expires after 30 minutes, at most 5 are open at a time.

### Object search (`mcp400.jar`, `mcp400-pgm.jar`)

| Tool | Description |
|---|---|
| `search_objects(library, [name], [object_type])` | Objects of one library by name pattern (`*` wildcard, max. 10 characters) and type (`*PGM`, `*SRVPGM`, `*FILE`, `*MODULE`, `*DTAARA`, `*DTAQ`, `*MSGF`, `*OUTQ`, `*BNDDIR`, default `*ALL`). One line per object: name, type, attribute, text. Max. 200 results. Read-only. |
| `get_binding_directory_entries(library, directory)` | Entries (service programs, modules) of a binding directory. |
| `search_procedures(pattern, [library], [binding_directory])` | Exported procedures of service programs by name (`*` wildcard, case sensitive). With `library` and `binding_directory`, only service programs bound by that directory are searched. |

The catalog views behind the binding tools (`QSYS2.BINDING_DIRECTORY_INFO`, `QSYS2.PROGRAM_EXPORT_IMPORT_INFO`) are not verified on every IBM i release. If a call fails with an SQL error, the column names do not match the release.

## Tools

The tools below are provided by `mcp400.jar` and `mcp400-db.jar`.

### Finding tables (also when names are cryptic)

| Tool | Description |
|---|---|
| `search_columns(pattern, [schema])` | Searches column names, system names, descriptions and column headings in all user schemas. Tables are grouped and ranked by number of matching columns. Best entry point for legacy names like `KDNR`. |
| `search_tables(pattern, [schema])` | Searches table/view names, system names and descriptions. |
| `get_all_tables_for_schema(schema, [filter], [includeLogicals])` | Lists the objects of a schema (name, system name, type, description). Source files are skipped, logical files are hidden by default. Max. 300 entries. |
| `get_schemas_for_table(table)` | Schemas (libraries) that contain an object with this name (SQL or system name), newest first. |

### Understanding tables

| Tool | Description |
|---|---|
| `get_all_columns_for_table(schema, table)` | Columns in order: type, nullability, default, system name, description or heading. |
| `get_information_about_table(schema, table)` | Object type (`T`/`P`/`L`/`V`/`A`/`M`), description, system name, approx. row count, last change/use, primary key, foreign keys, check/unique constraints, indexes and keyed logical files with ordered key columns. |
| `get_systemnames_for_table(schema, table)` | 10 character system name of the table and of all columns whose SQL name differs. |
| `get_dependent_objects(schema, table)` | Views/logical files over the table, child tables (foreign keys), aliases, triggers. |
| `get_view_definition(schema, view)` | SQL source of a view (joins, filters, business rules). |

### Writing and checking SQL

| Tool | Description |
|---|---|
| `validate_sql(sql)` | Prepares a single `SELECT`/`WITH`/`VALUES` statement **without executing it**. Returns SQLSTATE/SQLCODE and message on errors, otherwise the result columns with types. |
| `get_index_advice(schema, table)` | Indexes recommended by the optimizer (`QSYS2.SYSIXADV`), most requested first. |

### Logic in the database

| Tool | Description |
|---|---|
| `get_triggers_for_table(schema, table)` | Triggers with timing, event and source. |
| `get_triggers_for_schema(schema)` | One line per trigger of all tables in a schema. |
| `get_routines_for_schema(schema)` | Stored procedures and functions with language and comment. |
| `get_routine_info(schema, name)` | Parameters, comment and source of a routine. |

### Knowledge base (optional, off by default)

Lets the agent keep what it learned about the database (relations without foreign keys, business rules, meaning of coded values, data quality problems) between sessions. The tools only exist when it is enabled.

| Tool | Description |
|---|---|
| `save_knowledge(info_type, knowledge_text, [knowledge_id], [schema_name], [table_name], [column_name])` | Creates an entry, or overwrites the entry with `knowledge_id` (omitted schema/table/column keep their value). `info_type`: `RELATION`, `BUSINESS_RULE`, `ENUM_VALUES`, `DESCRIPTION`, `DATA_QUALITY`. Text max. 2000 characters (short, one topic per entry). If the same object and type already has entries, the answer names them so the agent can merge. |
| `get_knowledge_by_database_object(schema_name, [table_name], [column_name], [knowledge_id])` | Entries for a schema, narrowed to a table and column, grouped by object. More general entries (no table/column, or no schema) are included. Only a 300 character preview per entry is returned; pass `knowledge_id` to get one entry in full. Max. 50 entries. |
| `search_knowledge([pattern], [schema_name])` | Finds entries without knowing the table: terms separated by spaces (OR, substring, `%` wildcard) are searched in the text, schema, table and column; 300 character previews, max. 50 entries. Without `pattern` it returns an overview of what is documented: entries per schema, or per table and `info_type` when `schema_name` is given. |
| `merge_knowledge(knowledge_ids, info_type, knowledge_text, [schema_name], [table_name], [column_name])` | Combines two or more duplicate or overlapping entries: the first id keeps the merged text, the others are deleted. If the entries have different scopes, `schema_name`/`table_name`/`column_name` must be passed, otherwise nothing is changed. |
| `delete_knowledge(knowledge_id)` | Deletes an entry. |

To encourage the agent to save, the server reminds it at fitting moments (only when the knowledge base is enabled):

- `get_information_about_table` says when nothing is stored yet for the table. If entries exist, it tells the agent to read them before writing SQL and names the exact call.
- `validate_sql` adds at most one hint to a valid statement, in this order: the knowledge base has entries but was not read in this session, an error was just fixed ("save the cause if it was non-obvious"), a `JOIN` ("save it as `RELATION` if no foreign key covers it"), or nothing was saved in this session yet.
- The answer to `save_knowledge` names the number of entries for the table and entries that should be merged.

Setup:

- `AS400_KNOWLEDGE=true` enables the knowledge base (default: disabled).
- `AS400_KNOWLEDGE_TABLE` (optional) sets another table as `LIBRARY.TABLE` (or only `TABLE` for the user's library). Default: `MCP_KNOWLEDGE` in the library that is named like the user (`AS400_USER`).
- The table (`KNOWLEDGE_ID` identity, `SCHEMA_NAME`, `TABLE_NAME`, `COLUMN_NAME`, `INFO_TYPE`, `KNOWLEDGE_TEXT` CLOB(10K), `CREATED_BY`, `CREATED_AT`, `UPDATED_AT`, plus an index on schema/table/column) is created on first use if it does not exist. The library must exist and the user needs the authority to create tables in it, otherwise the tool returns an error that says so.
- This is the only feature that writes. It uses its own connection, the catalog tools stay on a read-only connection. Schema, table and column names are stored in upper case.

All tools that take a table accept the long SQL name **or** the 10 character system name.

## Reliability and security

- **Reconnect**: if the connection to the IBM i is lost (network, idle timeout, IBM i restart), the server reconnects once and repeats the call. No restart of the MCP server is needed.
- **Knowledge hint**: with the knowledge base enabled, `get_information_about_table` adds a line like `Knowledge: 2 entries stored for this table/its columns. Read BEFORE writing SQL: get_knowledge_by_database_object(schema_name=..., table_name=...)` (`none stored yet` if there are none). The server instructions add reading the notes as a workflow step. The notes themselves are not added to other results.
- **Untrusted text**: descriptions, comments, sources of views/routines/triggers and knowledge notes are written by other people. The server instructions tell the agent to treat them as data and never follow instructions found in them; knowledge results and routine sources are labelled as data. Restrict who may write to the knowledge table, and use an IBM i user with read-only rights for the catalog.

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

1. Download `mcp400.jar` (all capabilities) or `mcp400-db.jar` (database only) from the latest [GitHub release](../../releases/latest)
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
        "mcp400-db": {
            "type": "stdio",
            "command": "java",
            "args": [
                "-jar",
                "C:\\Users\\bashdi\\Downloads\\mcp400-db.jar"
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
On mcp-server-start, VSCode will ask you for your credentials and save them securely.
To use all capabilities, use `mcp400.jar` instead; the server name in the config is free to choose.