# KeY MCP Server

A Model Context Protocol (MCP) server that exposes the KeY theorem prover to Agents via stdio.

An Agent connected to this server can perform the full verification workflow autonomously:
load a project, enumerate contracts, run automatic proofs, inspect open goals, apply rules
interactively, run proof scripts, extract counterexamples and export proof artifacts.

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
| `KEY_MCP_SMT_SOLVERS` | (empty) | Comma-separated list of allowed SMT solver names (e.g. `Z3_CE`). Empty means SMT is disabled. |
| `KEY_MCP_LOG_LEVEL` | INFO | Log level for server-side logging (always written to stderr). |

The JVM heap size cannot be changed at runtime; set it at startup, e.g. `java -Xmx4g -jar ...`.

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
        "KEY_MCP_SMT_SOLVERS": "Z3_CE"
      }
    }
  }
}
```

## Docker

Build the image (requires a prebuilt shadow jar, see above):

```sh
docker build -f key.mcp/Dockerfile -t key-mcp .
```

Run it (stdio) so that the client can spawn it as a subprocess:

```sh
docker run -i --rm key-mcp
```

## Protocol

- Transport: stdio JSON-RPC (MCP protocol version `2025-11-25`).
- Single-client exclusive session: one KeY environment per process.
- Tools are named with a `key_` prefix.
- Tool results are returned both as MCP `content[]` (JSON text) and `structuredContent`.

### Error codes

| Code | Meaning |
|---|---|
| `-32700` | Parse error (malformed JSON). |
| `-32600` | Invalid request, e.g. a second `initialize`. |
| `-32601` | Unknown method or prompt. |
| `-32602` | Invalid parameters: missing/blank required parameter, unknown contract, unknown export format, bad goal id. |
| `-32603` | Internal error: project load failure, script failure, SMT failure, no open goals. |
| `-32001` | Path rejected by the whitelist (`KEY_MCP_ALLOWED_PATHS`). |
| `-32002` | Proof, goal or resource not found. |
| `-32003` | Operation not found. |

## Tools

### Session

- `key_session_info` — session id, whether a project is loaded, contract/proof counts.
- `key_session_reset` — dispose all proofs and the environment, keeping the MCP session.
- `key_session_dispose` — dispose everything (same as reset, reports `disposed: true`).

### Project

- `key_project_load(location, classPaths?, bootClassPath?, includes?)` — load a project
  directory or `.key` file. `location` is resolved against the workspace and must be inside
  `KEY_MCP_ALLOWED_PATHS`. Returns `loadedTypes` and `contractCount`.
- `key_contracts_list()` — list all contracts: `contractId`, `targetName`, `displayName`,
  `type`. Contract IDs are deterministic (sorted by target name).

### Proof lifecycle

- `key_proof_create(contractId)` — create a proof without starting auto mode.
- `key_proof_auto(contractId, timeoutMs, maxSteps, strategyOptions?, async?)` — create a
  proof and run auto mode. With `async: true` (default) returns immediately with an
  `operationId`; poll with `key_operation_wait`. With `async: false` blocks up to
  `timeoutMs` and returns the final operation status.
- `key_proof_status(proofId)` — `closed`, `openGoals`, `usedSteps`.

### Interactive proving

- `key_proof_goals_list(proofId)` — list open goals (`goalId`, `serialNr`, `sequent`).
- `key_proof_goal_get(proofId, goalId)` — sequent of one open goal.
- `key_proof_rules_list(proofId, filter?)` — discover rule names for
  `key_proof_rule_apply`. Returns all built-in rules (`name`, `displayName`) and the number
  of active taclets. Pass `filter` (case-insensitive substring) to list matching taclet
  names (capped at 200).
- `key_proof_rule_apply(proofId, goalId, ruleName, parameters?)` — apply a rule via a
  generated proof script (`select number=<goalId>; rule <ruleName> ...;`). `parameters`
  is a map of KeY script options, e.g. `{"on": "x + y", "occ": 1, "inst_x": "0"}`.
  Returns the generated `script` for transparency.
- `key_proof_script_run(proofId, script)` — run an arbitrary KeY proof script. Useful
  commands: `auto;`, `select number=N;`, `rule <name> on="...";`, `cut "...";`,
  `instantiate var=x with="...";`, `macro <name>;`, `tryclose;`, `smt;`.
- `key_proof_undo(proofId, goalId)` — prune the proof back to the given open goal,
  undoing all rule applications that led away from it.

### Artifacts

- `key_proof_export(proofId, format?, path?)` — `format: "proof"` returns the KeY `.proof`
  file as `content` (or writes it to `path`, which must pass the whitelist);
  `format: "json"` returns the proof tree as structured JSON.
- `key_proof_smt(proofId)` — SMT-LIB translation of the first open goal.
- `key_proof_counterexample(proofId, solver?)` — see below.

### Operations

- `key_operation_wait(operationId, timeoutMs?)` — poll events of an async operation;
  returns `state` (`running`/`completed`/`cancelled`/`timeout`/`error`) and the full
  `events` list (progress events every second, plus a terminal event).
- `key_operation_cancel(operationId)` — interrupt the worker thread and mark the
  operation cancelled.

### Counterexample extraction

`key_proof_counterexample` runs the configured SMT solver against the first open goal
of a proof. Because KeY's SMT translation only accepts first-order goals, you should
usually run `key_proof_auto` (or a proof script) first so the remaining goals are
simplified. If the solver reports the sequent as falsifiable, the returned model
contains constants, heaps, location sets and sequences in a human-readable format.

Requires `KEY_MCP_SMT_SOLVERS` (e.g. `Z3_CE`) and the solver binary on the `PATH`.

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

- `verify_contract`: verifies a single contract end-to-end.
- `verify_all_contracts`: verifies all contracts of a loaded project and summarizes the results.
- `diagnose_open_goals`: inspects open goals and suggests next interactive steps.
- `extract_counterexample`: extracts a counterexample via the SMT solver for falsifiable goals.

## Typical agent workflow

1. `initialize`
2. `key_project_load` → `key_contracts_list`
3. `key_proof_auto(async: true)` → poll `key_operation_wait` until `state != running`
4. If `closed: true`: `key_proof_export` (and optionally `key_proof_smt` for the record).
5. If goals remain open: `key_proof_goals_list` / `key_proof_goal_get` to inspect,
   `key_proof_rules_list` to find rules, `key_proof_rule_apply` / `key_proof_script_run`
   to advance, `key_proof_undo` to backtrack.
6. If the contract looks wrong: `key_proof_counterexample` for a falsifying model.

## Known limitations

- **Single client, single session.** Concurrent tool calls are not serialized around
  KeY's (not thread-safe) `Proof` objects: do not call goal-inspecting tools while an
  async auto-mode operation is still running.
- **Timeouts are best-effort.** Cancelling a proof interrupts the worker thread; if KeY's
  auto mode ignores the interrupt, the worker may keep running in the background.
- **Counterexamples need first-order goals.** Goals containing updates or program
  modalities must be simplified first (`key_proof_auto` or a script).
- **Whitelist and symlinks.** Path validation uses normalization, not real-path
  resolution; symlinks pointing outside the whitelist are not specially handled.
- **Operation history is unbounded.** Events and finished operations accumulate for the
  lifetime of the session; use `key_session_reset` for long sessions.
