#!/usr/bin/env bash
set -euo pipefail
base="${1:-http://127.0.0.1:8080}"
[[ "$(curl -fsS "$base/health")" == "ok" ]]
python3 - "$base" <<'PY_CHECK'
import json,sys,urllib.request,urllib.error
base = sys.argv[1]
for path,ident in (("/json",42),("/users/123",123)):
    with urllib.request.urlopen(base+path, timeout=5) as r:
        assert r.status == 200, (path, r.status)
        assert 'application/json' in r.headers.get('Content-Type',''), (path, dict(r.headers))
        obj=json.loads(r.read())
        assert obj == {'id':ident,'name':'benchmark-user','active':True}, (path,obj)
try:
    urllib.request.urlopen(base+'/users/invalid', timeout=5)
    raise AssertionError('Expected HTTP 400')
except urllib.error.HTTPError as e:
    assert e.code == 400, (e.code, e.read())
print('Smoke checks passed:', base)
PY_CHECK
