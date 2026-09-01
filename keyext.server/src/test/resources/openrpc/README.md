# Vendored schemas

These are checked in so that `OpenRpcDocumentTest` can validate `openrpc.json` against the real
OpenRPC meta-schema without touching the network, and against a version that does not move under
us.

| File | Source | Version |
| --- | --- | --- |
| `meta-schema.json` | npm `@open-rpc/meta-schema`, the `openrpcDocument` export of `package/index.js` | 1.14.9 |
| `json-schema-tools.json` | <https://meta.json-schema.tools/> | fetched 2026-09-01 |

Take the meta-schema from a *release*, never from the `master` branch of `open-rpc/meta-schema`:
the checked-in `schema.json` there is a template whose `openrpc` enum holds the placeholder string
`"GENERATED FIELD: Do Not Edit …"`, so a real document can never validate against it. The released
artifact has the actual version list, `1.3.2` among it.

`meta-schema.json` has two remote `$ref`s, both to `https://meta.json-schema.tools`; that is the
only reason the second file is here. The test maps both URIs onto these copies and blocks
everything else, so nothing is fetched at test time.

To refresh, bump the version and re-extract:

```bash
curl -sL "$(curl -s https://registry.npmjs.org/@open-rpc/meta-schema/latest \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["dist"]["tarball"])')" \
  | tar xzO package/index.js
curl -s https://meta.json-schema.tools/
```
