#!/bin/bash
# Smoke test against a running server.
#
#   JOTTER_SERVER_TOKEN=e2e-test-token JOTTER_PORT=8099 ./gradlew run &
#   BASE=http://127.0.0.1:8099 TOKEN=e2e-test-token bash docs/e2e.sh
#
# The admin block only runs when ADMIN_USER and ADMIN_PASSWORD are exported;
# the server must have been started with the same two variables.
#
# Use a throwaway data dir: the test writes notes, deletes one and checks stats.
set -u
B=${BASE:-http://127.0.0.1:8099}
TOKEN=${TOKEN:?set TOKEN to the server sync token}

fail=0
check() { # name expected actual
  if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1 — expected [$2] got [$3]"; fail=1; fi
}
jq_get() { echo "$1" | python3 -c "import sys,json;print($2)"; }

SUFFIX=$RANDOM$RANDOM
N1="e2e-$SUFFIX"

check "health" "ok" "$(curl -s --noproxy '*' "$B/health" | python3 -c "import sys,json;print(json.load(sys.stdin)['status'])")"
check "ping without token -> 401" "401" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" "$B/api/v1/ping")"
check "ping wrong token -> 401" "401" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer wrong" "$B/api/v1/ping")"
check "ping valid token -> 200" "200" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "$B/api/v1/ping")"

sync() { curl -s --noproxy '*' -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "$1" "$B/api/v1/sync"; }

# push + echo
r=$(sync "{\"cursor\":0,\"notes\":[{\"uuid\":\"$N1\",\"title\":\"from phone\",\"content\":\"hello\",\"category\":\"trabalho-$SUFFIX\",\"createdTime\":1000,\"updatedTime\":2000}]}")
check "push creates the note" "from phone" "$(jq_get "$r" "[n for n in json.load(sys.stdin)['notes'] if n['uuid']=='$N1'][0]['title']" 2>/dev/null || echo "-")"
check "push creates the category" "trabalho-$SUFFIX" "$(jq_get "$r" "[c for c in json.load(sys.stdin)['categories'] if c=='trabalho-$SUFFIX'][0]")"

# a second device pulls from zero
r=$(sync '{"cursor":0}')
check "second device sees the note" "from phone" "$(jq_get "$r" "[n for n in json.load(sys.stdin)['notes'] if n['uuid']=='$N1'][0]['title']")"
cursor_b=$(jq_get "$r" "json.load(sys.stdin)['cursor']")

# older push loses
r=$(sync "{\"cursor\":0,\"notes\":[{\"uuid\":\"$N1\",\"title\":\"old\",\"updatedTime\":1000}]}")
check "older push ignored (LWW)" "from phone" "$(jq_get "$r" "[n for n in json.load(sys.stdin)['notes'] if n['uuid']=='$N1'][0]['title']")"

# newer push wins
r=$(sync "{\"cursor\":0,\"notes\":[{\"uuid\":\"$N1\",\"title\":\"new\",\"updatedTime\":3000}]}")
check "newer push wins (LWW)" "new" "$(jq_get "$r" "[n for n in json.load(sys.stdin)['notes'] if n['uuid']=='$N1'][0]['title']")"

# delta pull
r=$(sync "{\"cursor\":$cursor_b}")
check "delta pull returns the update" "new" "$(jq_get "$r" "[n for n in json.load(sys.stdin)['notes'] if n['uuid']=='$N1'][0]['title']")"

# delete
r=$(sync "{\"cursor\":0,\"deletions\":[{\"uuid\":\"$N1\",\"deletedAt\":4000}]}")
check "deletion creates a tombstone" "$N1" "$(jq_get "$r" "[d for d in json.load(sys.stdin)['deletions'] if d['uuid']=='$N1'][0]['uuid']")"
check "deletion removes the note" "0" "$(jq_get "$r" "len([n for n in json.load(sys.stdin)['notes'] if n['uuid']=='$N1'])")"

# a stale note must not resurrect
r=$(sync "{\"cursor\":0,\"notes\":[{\"uuid\":\"$N1\",\"title\":\"ghost\",\"updatedTime\":3500}]}")
check "note older than deletion stays dead" "0" "$(jq_get "$r" "len([n for n in json.load(sys.stdin)['notes'] if n['uuid']=='$N1'])")"

# a newer edit resurrects
r=$(sync "{\"cursor\":0,\"notes\":[{\"uuid\":\"$N1\",\"title\":\"edited later\",\"updatedTime\":5000}]}")
check "note newer than deletion comes back" "edited later" "$(jq_get "$r" "[n for n in json.load(sys.stdin)['notes'] if n['uuid']=='$N1'][0]['title']")"

# encrypted note: server only ever sees ciphertext
r=$(sync "{\"cursor\":0,\"notes\":[{\"uuid\":\"enc-$SUFFIX\",\"isEncrypted\":true,\"encryptionSalt\":\"c2FsdA==\",\"encryptedContent\":\"Y2lwaGVy\",\"updatedTime\":6000}]}")
check "encrypted note keeps ciphertext" "Y2lwaGVy" "$(jq_get "$r" "[n for n in json.load(sys.stdin)['notes'] if n['uuid']=='enc-$SUFFIX'][0]['encryptedContent']")"
check "encrypted note has no plaintext" "" "$(jq_get "$r" "[n for n in json.load(sys.stdin)['notes'] if n['uuid']=='enc-$SUFFIX'][0]['content']")"

# even a buggy client that sends plain text on an encrypted note cannot leak it
r=$(sync "{\"cursor\":0,\"notes\":[{\"uuid\":\"leak-$SUFFIX\",\"isEncrypted\":true,\"title\":\"LEAKED TITLE\",\"content\":\"LEAKED BODY\",\"encryptionSalt\":\"c2FsdA==\",\"encryptedContent\":\"Y2lwaGVy\",\"updatedTime\":6000}]}")
check "server blanks plaintext for encrypted notes" "" "$(jq_get "$r" "[n for n in json.load(sys.stdin)['notes'] if n['uuid']=='leak-$SUFFIX'][0]['title']")"
check "server keeps the ciphertext only" "Y2lwaGVy" "$(jq_get "$r" "[n for n in json.load(sys.stdin)['notes'] if n['uuid']=='leak-$SUFFIX'][0]['encryptedContent']")"

# the device name is remembered for the status page
sync "{\"cursor\":0,\"deviceId\":\"dev-$SUFFIX\",\"deviceName\":\"Tester $SUFFIX\"}" > /dev/null
r=$(curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" "$B/api/v1/stats")
check "device appears in stats" "Tester $SUFFIX" "$(jq_get "$r" "[d for d in json.load(sys.stdin)['devices'] if d['id']=='dev-$SUFFIX'][0]['name']")"

# ---- agenda: own stream and cursor, same LWW contract ---------------------
as() { curl -s --noproxy '*' -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "$1" "$B/api/v1/tools/agenda/sync"; }

r=$(curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" "$B/api/v1/tools")
check "tools lists the agenda" "1" "$(jq_get "$r" "len([t for t in json.load(sys.stdin)['tools'] if t['id']=='agenda'])")"

E1="ev-$SUFFIX"
r=$(as "{\"cursor\":0,\"events\":[{\"uuid\":\"$E1\",\"title\":\"daily\",\"startTime\":1000,\"endTime\":2000,\"createdTime\":1000,\"updatedTime\":2000}]}")
check "agenda push creates the event" "daily" "$(jq_get "$r" "[e for e in json.load(sys.stdin)['events'] if e['uuid']=='$E1'][0]['title']" 2>/dev/null || echo "-")"
check "agenda event carries its times" "2000" "$(jq_get "$r" "[e for e in json.load(sys.stdin)['events'] if e['uuid']=='$E1'][0]['endTime']" 2>/dev/null || echo "-")"

r=$(as "{\"cursor\":0,\"events\":[{\"uuid\":\"$E1\",\"title\":\"old\",\"updatedTime\":1000}]}")
check "agenda older push ignored (LWW)" "daily" "$(jq_get "$r" "[e for e in json.load(sys.stdin)['events'] if e['uuid']=='$E1'][0]['title']")"

r=$(as "{\"cursor\":0,\"events\":[{\"uuid\":\"$E1\",\"title\":\"new\",\"updatedTime\":3000}]}")
check "agenda newer push wins (LWW)" "new" "$(jq_get "$r" "[e for e in json.load(sys.stdin)['events'] if e['uuid']=='$E1'][0]['title']")"

r=$(as "{\"cursor\":0,\"deletions\":[{\"uuid\":\"$E1\",\"deletedAt\":4000}]}")
check "agenda deletion removes the event" "0" "$(jq_get "$r" "len([e for e in json.load(sys.stdin)['events'] if e['uuid']=='$E1'])")"

# the notes stream must not see the agenda rows, and vice versa
r=$(sync '{"cursor":0}')
check "agenda rows stay out of the notes stream" "0" "$(jq_get "$r" "len([n for n in json.load(sys.stdin)['notes'] if n['uuid']=='$E1'])")"

# ---- links: third stream, same LWW + tombstone contract -------------------
lk() { curl -s --noproxy '*' -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "$1" "$B/api/v1/tools/jotter/links/sync"; }

L1="lk-$SUFFIX"
r=$(lk "{\"cursor\":0,\"deviceId\":\"dev-$SUFFIX\",\"links\":[{\"uuid\":\"$L1\",\"url\":\"https://example.com/a\",\"title\":\"A\",\"createdTime\":1000,\"updatedTime\":2000}]}")
check "links push creates the link" "https://example.com/a" "$(jq_get "$r" "[l for l in json.load(sys.stdin)['links'] if l['uuid']=='$L1'][0]['url']" 2>/dev/null || echo "-")"

r=$(lk "{\"cursor\":0,\"links\":[{\"uuid\":\"$L1\",\"url\":\"https://example.com/old\",\"updatedTime\":1000}]}")
check "links older push ignored (LWW)" "https://example.com/a" "$(jq_get "$r" "[l for l in json.load(sys.stdin)['links'] if l['uuid']=='$L1'][0]['url']")"

r=$(lk "{\"cursor\":0,\"deletions\":[{\"uuid\":\"$L1\",\"deletedAt\":4000}]}")
check "links deletion removes the link" "0" "$(jq_get "$r" "len([l for l in json.load(sys.stdin)['links'] if l['uuid']=='$L1'])")"

# ---- universal store: a new app needs no endpoint here -------------------
STORE_NS="e2e-store-$SUFFIX"
st() { curl -s --noproxy '*' -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "$1" "$B/api/v1/store/$STORE_NS/items/sync"; }

r=$(st "{\"cursor\":0,\"deviceId\":\"dev-$SUFFIX\",\"deviceName\":\"Store $SUFFIX\",\"records\":[{\"key\":\"r1\",\"payload\":\"um\",\"createdTime\":1000,\"updatedTime\":2000}]}")
check "store push creates the record" "um" "$(jq_get "$r" "[x for x in json.load(sys.stdin)['records'] if x['key']=='r1'][0]['payload']" 2>/dev/null || echo "-")"
check "store cursor starts at the collection" "1" "$(jq_get "$r" "json.load(sys.stdin)['cursor']")"

r=$(curl -s --noproxy '*' -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "{\"cursor\":0}" "$B/api/v1/store/$STORE_NS/other/sync")
check "another collection has its own cursor" "0" "$(jq_get "$r" "json.load(sys.stdin)['cursor']")"

r=$(st "{\"cursor\":0,\"records\":[{\"key\":\"r1\",\"payload\":\"velho\",\"updatedTime\":1500}]}")
check "store older push ignored (LWW)" "um" "$(jq_get "$r" "[x for x in json.load(sys.stdin)['records'] if x['key']=='r1'][0]['payload']")"

r=$(st "{\"cursor\":0,\"records\":[{\"key\":\"r1\",\"payload\":\"dois\",\"updatedTime\":3000}]}")
check "store newer push wins (LWW)" "dois" "$(jq_get "$r" "[x for x in json.load(sys.stdin)['records'] if x['key']=='r1'][0]['payload']")"

r=$(st "{\"cursor\":0,\"deletions\":[{\"key\":\"r1\",\"deletedAt\":4000}]}")
check "store deletion creates a tombstone" "r1" "$(jq_get "$r" "[d for d in json.load(sys.stdin)['deletions'] if d['key']=='r1'][0]['key']")"

r=$(st "{\"cursor\":0,\"records\":[{\"key\":\"r1\",\"payload\":\"fantasma\",\"updatedTime\":3500}]}")
check "store record older than deletion stays dead" "0" "$(jq_get "$r" "len([x for x in json.load(sys.stdin)['records'] if x['key']=='r1'])")"

r=$(st "{\"cursor\":0,\"records\":[{\"key\":\"r1\",\"payload\":\"voltou\",\"updatedTime\":5000}]}")
check "store record newer than deletion comes back" "voltou" "$(jq_get "$r" "[x for x in json.load(sys.stdin)['records'] if x['key']=='r1'][0]['payload']")"

r=$(curl -s --noproxy '*' -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"a":1}' "$B/api/v1/store/$STORE_NS/config/records/ui")
check "store single PUT stores the payload" '{"a":1}' "$(jq_get "$r" "json.load(sys.stdin)['payload']")"

r=$(curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" "$B/api/v1/store/$STORE_NS/config/records/ui")
check "store single GET returns it" '{"a":1}' "$(jq_get "$r" "json.load(sys.stdin)['payload']")"

r=$(curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" "$B/api/v1/store")
check "store discovery lists the namespace" "1" "$(jq_get "$r" "len([s for s in json.load(sys.stdin)['streams'] if s['namespace']=='$STORE_NS' and s['collection']=='items'])")"

# ---- universal store: files, streamed and content-addressed --------------
BLOB_NS="e2e-files-$SUFFIX"
BLOB_FILE="/tmp/atena-e2e-$SUFFIX.txt"
BLOB_DOWN="/tmp/atena-e2e-down-$SUFFIX.txt"
printf 'conteudo do e2e %s\n' "$SUFFIX" > "$BLOB_FILE"
BLOB_SIZE=$(wc -c < "$BLOB_FILE" | tr -d ' ')

r=$(curl -s --noproxy '*' -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain" --data-binary "@$BLOB_FILE" "$B/api/v1/blobs/$BLOB_NS/nota.txt")
HASH=$(jq_get "$r" "json.load(sys.stdin)['hash']")
check "blob upload returns the size" "$BLOB_SIZE" "$(jq_get "$r" "json.load(sys.stdin)['size']")"
check "blob upload keeps the content type" "text/plain" "$(jq_get "$r" "json.load(sys.stdin)['contentType']")"

curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" -o "$BLOB_DOWN" "$B/api/v1/blobs/$BLOB_NS/nota.txt"
check "blob download is byte-identical" "0" "$(cmp -s "$BLOB_FILE" "$BLOB_DOWN" && echo 0 || echo 1)"

check "blob ETag is the sha256" "1" "$(curl -s --noproxy '*' -D - -o /dev/null -H "Authorization: Bearer $TOKEN" "$B/api/v1/blobs/$BLOB_NS/nota.txt" | grep -qi "ETag: \"$HASH\"" && echo 1 || echo 0)"
check "blob revalidation -> 304" "304" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" -H "If-None-Match: \"$HASH\"" "$B/api/v1/blobs/$BLOB_NS/nota.txt")"
check "blob HEAD reports the size" "$BLOB_SIZE" "$(curl -s --noproxy '*' -I -H "Authorization: Bearer $TOKEN" "$B/api/v1/blobs/$BLOB_NS/nota.txt" | grep -i '^Content-Length:' | tr -d '\r' | cut -d' ' -f2)"

# same content under another name: same hash (deduplicated on disk)
r=$(curl -s --noproxy '*' -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain" --data-binary "@$BLOB_FILE" "$B/api/v1/blobs/$BLOB_NS/copia.txt")
check "same content deduplicates" "$HASH" "$(jq_get "$r" "json.load(sys.stdin)['hash']")"

r=$(curl -s --noproxy '*' -H "Authorization: Bearer $TOKEN" "$B/api/v1/blobs?namespace=$BLOB_NS")
check "blob list shows both names" "2" "$(jq_get "$r" "len(json.load(sys.stdin)['blobs'])")"

check "blob delete answers 204" "204" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X DELETE -H "Authorization: Bearer $TOKEN" "$B/api/v1/blobs/$BLOB_NS/copia.txt")"
check "deleted blob is gone" "404" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "$B/api/v1/blobs/$BLOB_NS/copia.txt")"
check "the other name still downloads" "200" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "$B/api/v1/blobs/$BLOB_NS/nota.txt")"
rm -f "$BLOB_FILE" "$BLOB_DOWN"

# ---- admin screen: login, token creation, revoke --------------------------
if [ -n "${ADMIN_USER:-}" ] && [ -n "${ADMIN_PASSWORD:-}" ]; then
  AUTH="-u $ADMIN_USER:$ADMIN_PASSWORD"
  check "admin without login -> 401" "401" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" "$B/admin")"
  check "admin with login -> 200" "200" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" $AUTH "$B/admin")"
  check "admin page lists the synced device" "yes" "$(curl -s --noproxy '*' $AUTH "$B/admin" | grep -q "Tester $SUFFIX" && echo yes || echo no)"

  created=$(curl -s --noproxy '*' $AUTH -X POST -d "label=e2e-$SUFFIX" "$B/admin/tokens")
  NEWTOK=$(echo "$created" | python3 -c "import sys,re;m=re.search(r'class=\"secret\">([^<]+)<',sys.stdin.read());print(m.group(1) if m else '')")
  check "created token has a secret" "1" "$( [ ${#NEWTOK} -ge 16 ] && echo 1 || echo 0 )"
  check "created token authenticates" "200" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $NEWTOK" "$B/api/v1/ping")"

  TID=$(curl -s --noproxy '*' $AUTH "$B/admin" | python3 -c "import sys,re;m=re.search(r'e2e-$SUFFIX.*?/admin/tokens/(\d+)/revoke',sys.stdin.read(),re.S);print(m.group(1) if m else '')")
  check "created token is listed for revoke" "1" "$( [ -n "$TID" ] && echo 1 || echo 0 )"
  check "revoking a token answers 200" "200" "$(curl -s --noproxy '*' $AUTH -o /dev/null -w "%{http_code}" -X POST "$B/admin/tokens/$TID/revoke")"
  check "revoked token -> 401" "401" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $NEWTOK" "$B/api/v1/ping")"
  check "master token still works" "200" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "$B/api/v1/ping")"

  # a scoped token only reaches the namespaces it names
  created=$(curl -s --noproxy '*' $AUTH -X POST -d "label=scope-$SUFFIX" -d "scope=$STORE_NS" "$B/admin/tokens")
  STOK=$(echo "$created" | python3 -c "import sys,re;m=re.search(r'class=\"secret\">([^<]+)<',sys.stdin.read());print(m.group(1) if m else '')")
  check "scoped token reaches its namespace" "200" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X POST -H "Authorization: Bearer $STOK" -H "Content-Type: application/json" -d '{"cursor":0}' "$B/api/v1/store/$STORE_NS/items/sync")"
  check "scoped token refused in another namespace" "403" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X POST -H "Authorization: Bearer $STOK" -H "Content-Type: application/json" -d '{"cursor":0}' "$B/api/v1/store/outro-$SUFFIX/items/sync")"
else
  echo "SKIP  admin checks — start the server with ADMIN_USER and ADMIN_PASSWORD"
fi

# malformed body
check "malformed JSON -> 400" "400" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"cursor": nope}' "$B/api/v1/sync")"

echo
[ $fail -eq 0 ] && echo "all checks passed" || echo "some checks failed"
exit $fail
