# KeY Server

A headless KeY. It loads Java sources and their JML specifications, runs proofs, and answers
questions about them over JSON-RPC 2.0, so that a program — a script, an editor, an agent — can
drive the prover without a GUI.

It embeds `key.core` directly, so a proof it runs is the proof KeY runs. There is no Swing
anywhere in the module.

## Building

```bash
./gradlew :keyext.server:shadowJar
```

This produces a self-contained jar in `build/libs`:

```
keyext.server-<version>-exe.jar
```

## Running

```bash
java -Xmx4g -jar keyext.server-<version>-exe.jar --port 0 --workspace /path/to/project
```

KeY is memory-hungry — a non-trivial proof will exhaust the default heap — so give the JVM a real
`-Xmx`. 4 GB is a reasonable starting point.

Options:

| Option | Meaning |
| --- | --- |
| `--port` | Port to bind on `127.0.0.1`. `0`, the default, lets the OS pick a free one. |
| `--workspace` | Directory the instance is anchored to. Relative paths in requests are taken against it. Defaults to the current directory. |
| `--idle-timeout` | Shut down after this many seconds without a request. Defaults to 1800; `0` disables it. |
| `--threads` | Prover worker threads. Fixed for the instance, because it is backed by a JVM-global switch — run several instances to prove in parallel. |

The server binds to the loopback address only. To reach one on another machine, forward the port
over SSH.

### Finding a running instance

Because `--port 0` means the port is not known until the process is up, every instance writes a
record of itself — its port, its workspace, its process id — into a per-user directory:

```
$XDG_STATE_HOME/keyext-server/instances   # %LOCALAPPDATA% on Windows,
                                         # ~/.local/state otherwise
```

A client discovers a server by reading that directory rather than by being told a port. The
record is removed when the instance stops.

### Requirements

A JDK 21 or newer that includes the `java.desktop` module. The server has no user interface, but
parts of `key.core` still reach into AWT classes, so a headless-stripped JDK will not start it.

## The protocol

The full interface — every method, its parameters, its result, the errors it can return, and a
worked example of each — is described in one OpenRPC 1.3.2 document:

```
src/main/resources/openrpc.json
```

A running instance serves that same document, so a client can fetch the protocol from the server
it is about to talk to rather than from a file it hopes is current:

```bash
curl -s -X POST http://127.0.0.1:<port>/rpc \
  -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"server.describe"}'
```

Calls are `POST /rpc`. A `GET /rpc` on the same path opens a server-sent event stream carrying
progress for long-running work.

A first call, to check what is on the other end:

```bash
curl -s -X POST http://127.0.0.1:<port>/rpc \
  -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"server.version"}'
```

```json
{
  "apiVersion": "1.1.0",
  "keyVersion": "3.1.0-dev (internal: 6ff141b217c5dee4fe0200f24ef06cdb3028727e)",
  "instanceId": "inst-36up600s",
  "threads": 1
}
```

`apiVersion` is the version of the protocol document; clients should check it.

## Clients

A Python client and MCP adapter for this server lives in
[`key-lib/key-agent-client`](../key-lib/key-agent-client).
