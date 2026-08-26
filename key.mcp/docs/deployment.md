# KeY MCP Server — Deployment Guide

This guide is for **humans deploying the server** so that an MCP client (Claude Desktop,
an IDE agent, a custom harness) can spawn it. The Agent itself only talks JSON-RPC over
stdio; everything below is about how the process is started and what it may access.

## 1. Prerequisites

| Requirement | Notes |
|---|---|
| Java 21+ | Runtime only; no JDK features are needed. |
| Z3 (optional) | Only required for `key_proof_counterexample`. Any recent Z3 (4.x) works; must be on the `PATH` of the server process. |
| Memory | KeY proofs are memory-hungry; plan for at least 4 GB heap. |

## 2. ShadowJar deployment

Build once:

```sh
gradle :key.mcp:shadowJar
# artifact: key.mcp/build/libs/key-mcp-<version>-all.jar
```

Run:

```sh
java -Xmx4g -jar key.mcp/build/libs/key-mcp-3.1.0-dev-all.jar
```

The process speaks JSON-RPC on stdout and logs on stderr. It is meant to be spawned
and supervised by the MCP client, not run interactively.

### 2.1 Environment variables

| Variable | Default | Description |
|---|---|---|
| `KEY_MCP_WORKSPACE` | Process working directory | Root against which all **relative** paths in tool parameters are resolved. |
| `KEY_MCP_ALLOWED_PATHS` | `KEY_MCP_WORKSPACE` | Comma-separated whitelist of path prefixes. **Every** file parameter (`location`, `classPaths`, `bootClassPath`, `includes`, export `path`) must resolve inside one of them. |
| `KEY_MCP_DEFAULT_TIMEOUT_MS` | 60000 | Default proof timeout. |
| `KEY_MCP_DEFAULT_MAX_STEPS` | 10000 | Default max rule applications. |
| `KEY_MCP_SMT_SOLVERS` | (empty = disabled) | e.g. `Z3_CE` to enable counterexample extraction. |
| `KEY_MCP_LOG_LEVEL` | INFO | `DEBUG` helps diagnosing load/proof problems. |

### 2.2 Smoke test

```sh
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  | java -jar key.mcp/build/libs/key-mcp-3.1.0-dev-all.jar 2>/dev/null \
  | grep protocolVersion
```

Modern (`2026-07-28`) clients can instead probe without a handshake:

```sh
echo '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}' \
  | java -jar key.mcp/build/libs/key-mcp-3.1.0-dev-all.jar 2>/dev/null \
  | grep supportedVersions
```

## 3. How projects are located (workspace + whitelist)

All file access is governed by two rules:

1. **Relative paths resolve against `KEY_MCP_WORKSPACE`.**
   `location: "examples/demo"` with workspace `/home/me/work` loads
   `/home/me/work/examples/demo`.
2. **Every resolved path must start with one of `KEY_MCP_ALLOWED_PATHS`.**
   Attempts outside the whitelist fail with error `-32001` (`Path not allowed`).

To expose several directories (e.g. a project and a shared library-stub dir):

```sh
KEY_MCP_WORKSPACE=/home/me/work \
KEY_MCP_ALLOWED_PATHS=/home/me/work/project,/home/me/work/spec-stubs \
java -jar key-mcp.jar
```

## 4. Loading Java projects with dependencies

`key_project_load` parameters:

| Parameter | Type | Meaning |
|---|---|---|
| `location` | string (required) | Project directory or `.key` file. |
| `classPaths` | string[] (optional) | Extra classpath entries (dirs/jars) for **API/specification stub classes**. |
| `bootClassPath` | string (optional) | Replaces KeY's built-in Java redux boot classpath (advanced). |
| `includes` | string[] (optional) | Additional `.key` files to include. |

Example — project depending on stub specs for a library:

```json
{
  "name": "key_project_load",
  "arguments": {
    "location": "project",
    "classPaths": ["spec-stubs/lib"],
    "includes": ["project/extra-rules.key"]
  }
}
```

**Caveats (KeY semantics, not MCP-specific):**

- Omit `classPaths` unless the project actually needs external spec stubs. Providing a
  `classPaths` list changes how KeY resolves Java API classes and can make rule files
  fail to parse if the default API stubs are no longer found. The plain
  `location`-only load covers the common case (self-contained JML-annotated sources).
- `bootClassPath` should only be set if you maintain your own Java API stubs; the
  built-in redux is right for virtually all projects.

## 5. Docker deployment

### 5.1 Build the image

The Dockerfile copies a **prebuilt** shadow jar (keeps the image small and the build
fast), so build the jar first:

```sh
gradle :key.mcp:shadowJar
docker build -f key.mcp/Dockerfile -t key-mcp .
```

### 5.2 Mount your Java project

Projects live on the host; mount them into the container and point the workspace at
the mount point:

```sh
docker run -i --rm \
  -v /home/me/work/project:/workspace:ro \
  -e KEY_MCP_WORKSPACE=/workspace \
  -e KEY_MCP_ALLOWED_PATHS=/workspace \
  key-mcp
```

Rules of thumb:

- **`-i` is mandatory** (stdio transport); do not use `-d`.
- Mount **read-only** (`:ro`) unless the agent should write exported `.proof` files
  via `key_proof_export(path=...)` — then drop `:ro`.
- All paths the agent passes are **container paths** (e.g. `location: "."` or
  `"src"`), not host paths.
- Mount additional spec-stub/library dirs and add them to `KEY_MCP_ALLOWED_PATHS`:
  ```sh
  -v /home/me/spec-stubs:/spec-stubs:ro \
  -e KEY_MCP_ALLOWED_PATHS=/workspace,/spec-stubs
  ```

### 5.3 Counterexamples inside Docker (Z3)

The base image has no SMT solver. Build a derived image:

```dockerfile
FROM key-mcp
RUN apt-get update && apt-get install -y --no-install-recommends z3 \
    && rm -rf /var/lib/apt/lists/*
ENV KEY_MCP_SMT_SOLVERS=Z3_CE
```

```sh
docker build -f key.mcp/Dockerfile.z3 -t key-mcp-z3 .
```

### 5.4 Memory limit

```sh
docker run -i --rm -m 6g \
  -e JAVA_TOOL_OPTIONS=-Xmx4g \
  -v /home/me/work/project:/workspace:ro \
  -e KEY_MCP_WORKSPACE=/workspace \
  key-mcp
```

## 6. MCP client configuration

### 6.1 Claude Desktop — local jar

```json
{
  "mcpServers": {
    "key-mcp": {
      "command": "java",
      "args": ["-Xmx4g", "-jar", "/abs/path/key-mcp-3.1.0-dev-all.jar"],
      "env": {
        "KEY_MCP_WORKSPACE": "/home/me/work/project",
        "KEY_MCP_ALLOWED_PATHS": "/home/me/work/project",
        "KEY_MCP_SMT_SOLVERS": "Z3_CE"
      }
    }
  }
}
```

### 6.2 Claude Desktop — Docker

```json
{
  "mcpServers": {
    "key-mcp": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "-v", "/home/me/work/project:/workspace:ro",
        "-e", "KEY_MCP_WORKSPACE=/workspace",
        "-e", "KEY_MCP_ALLOWED_PATHS=/workspace",
        "key-mcp"
      ]
    }
  }
}
```

Any other stdio-capable MCP client works analogously: configure the spawn command,
pass env vars through the client config, and make sure the client's CWD does not
matter (set `KEY_MCP_WORKSPACE` explicitly).

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `-32001 Path not allowed` | Path resolves outside the whitelist (host vs. container path confusion in Docker) | Check `KEY_MCP_WORKSPACE` / `KEY_MCP_ALLOWED_PATHS`; remember agents pass container paths |
| `-32603 Failed to load project` | KeY could not parse the project | Re-run with `KEY_MCP_LOG_LEVEL=DEBUG` and read stderr; the error `data` contains the cause chain |
| Counterexample says "No SMT solver enabled" | `KEY_MCP_SMT_SOLVERS` empty | Set `KEY_MCP_SMT_SOLVERS=Z3_CE` and install Z3 |
| Solver launch error | Z3 binary not on `PATH` of the server process | `which z3` in the same environment the client spawns |
| Process never exits after client disconnect | Fixed in current version (daemon threads); if seen, upgrade |
| OutOfMemoryError | Heap too small | `java -Xmx8g ...` / Docker `-m` + `JAVA_TOOL_OPTIONS` |
| `classPaths` load breaks API resolution | KeY changes default stub resolution when `classPaths` is set | Drop `classPaths`, or include the API stub dirs KeY needs |
