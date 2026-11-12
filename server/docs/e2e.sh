#!/bin/bash
# Smoke test against a running server.
#
#   JOTTER_SERVER_TOKEN=e2e-test-token JOTTER_PORT=8099 ./gradlew run &
#   BASE=http://127.0.0.1:8099 TOKEN=e2e-test-token bash docs/e2e.sh
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

# malformed body
check "malformed JSON -> 400" "400" "$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"cursor": nope}' "$B/api/v1/sync")"

echo
[ $fail -eq 0 ] && echo "all checks passed" || echo "some checks failed"
exit $fail
