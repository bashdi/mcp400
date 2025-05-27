# mcp400 - MCP-server for AS400/DB2 for i

A Spring Boot-based MCP server that delivers more context and metadata about IBM i (AS/400) databases. It currently allows querying tables(only tablenames and description), columns, primary keys, and indexes.

## Usage in vscode with Github Copilot

1. Download mcp400.jar
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
                "AS400_PASSWORD": "${input:AS400_PASSWORD}"
            }
        }
    }
}
```
On mcp-server-start, VSCode will ask you for your credentials and save them securely