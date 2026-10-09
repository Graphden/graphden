# Function identity HTTP API

`GET /api/entities/fn/:id` reads the current identity of one function without
fetching its implementation graph. It uses the normal authentication middleware
and the request's principal-scoped storage visibility policy. Supply the same
credentials as other protected editor API requests.

The `X-Graphden-Branch` header selects the branch, as on other graph reads.
Without that header the normal default branch applies. The UUID must identify
a function visible on that branch; an identity that exists only on another
branch is absent here. Reading metadata does not grant permission to delete.

A successful response is HTTP 200 with `Content-Type: application/json` and
exactly these fields:

```json
{"id":"6a365b30-6936-4671-b772-a409623a492a","name":"example","namespace-id":null}
```

`id` is the immutable function UUID. `name` and `namespace-id` reflect the
current branch's row; the namespace is null for a root-level function. No
bindings, implementation, description, or secret fields are returned.

Absent or deleted functions, invalid UUIDs, and unsupported entity types return
HTTP 404 with this JSON body:

```json
{"error":"function-not-found"}
```

Authentication and branch-routing failures retain their ordinary responses.
A generic 404 must not be treated as `function-not-found`; clients must inspect
the error code. Cleanup clients should disable their HTTP cache, re-read the
exact UUID, and match its current name and namespace against their creation
receipt before each ordinary DELETE. Keep the receipt when the response is
denied, malformed, or mismatched. A search result with the same name does not
replace the recorded UUID.
