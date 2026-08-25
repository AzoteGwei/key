# KeY MCP Server

A Model Context Protocol (MCP) server that exposes the KeY theorem prover to Agents via stdio.

## Build

```sh
gradle :key.mcp:classes
gradle :key.mcp:test
gradle :key.mcp:shadowJar
```

The shadow jar is generated at `key.mcp/build/libs/key-mcp-*-all.jar`.

## Run

```sh
gradle :key.mcp:run
```

Or from the shadow jar:

```sh
java -jar key.mcp/build/libs/key-mcp-*-all.jar
```

The server uses stdio and expects the client to start it as a subprocess.

## Configuration

The server reads its configuration from environment variables:

| Variable | Default | Description |
|---|---|---|
| `KEY_MCP_WORKSPACE` | Current working directory | Workspace root used to resolve relative paths. |
| `KEY_MCP_ALLOWED_PATHS` | Workspace root | Comma-separated list of allowed path prefixes. |
| `KEY_MCP_DEFAULT_TIMEOUT_MS` | 60000 | Default timeout for proof operations. |
| `KEY_MCP_DEFAULT_MAX_STEPS` | 10000 | Default maximum rule applications. |
| `KEY_MCP_DEFAULT_MAX_HEAP` | 4g | JVM heap hint (must be set via `JAVA_OPTS` or `-Xmx` before startup). |
| `KEY_MCP_SMT_SOLVERS` | (empty) | Comma-separated list of allowed SMT solver names/paths. Empty means SMT is disabled. |
| `KEY_MCP_LOG_LEVEL` | INFO | Log level for server-side logging. |

## Example Claude Desktop configuration

```json
{
  "mcpServers": {
    "key-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/key.mcp/build/libs/key-mcp-3.1.0-dev-all.jar"
      ],
      "env": {
        "KEY_MCP_WORKSPACE": "/absolute/path/to/key",
        "KEY_MCP_ALLOWED_PATHS": "/absolute/path/to/key",
        "KEY_MCP_SMT_SOLVERS": ""
      }
    }
  }
}
```

## Docker

Build the image:

```sh
docker build -f key.mcp/Dockerfile -t key-mcp .
```

Run it (stdio) so that the client can spawn it as a subprocess:

```sh
docker run -i --rm key-mcp
```

## Protocol

- Transport: stdio JSON-RPC (MCP protocol version `2025-11-25`).
- Single-client exclusive session.
- Tools are named with a `key_` prefix.

## Tools

- `key_session_info`, `key_session_reset`, `key_session_dispose`
- `key_project_load`
- `key_contracts_list`
- `key_proof_create`
- `key_proof_auto`
- `key_proof_status`
- `key_proof_goals_list`, `key_proof_goal_get`
- `key_proof_rule_apply`, `key_proof_script_run`, `key_proof_undo`
- `key_proof_export` (formats: `proof`, `json`)
- `key_proof_smt`
- `key_proof_counterexample`
- `key_operation_wait`, `key_operation_cancel`

## Resources

- `session:///info`
- `project:///contracts`
- `proof://{proofId}/status`
- `proof://{proofId}/goals`
- `proof://{proofId}/goal/{goalId}`
- `proof://{proofId}/tree`
- `proof://{proofId}/export`
- `proof://{proofId}/smt`
- `proof://{proofId}/counterexample`
- `operation://{opId}/events`

## Prompts

- `verify_contract`: guides an agent through the standard verification workflow.
