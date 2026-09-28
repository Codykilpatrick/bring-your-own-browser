# otel-poc

Usage tracking and session replay for legacy web apps on an airgapped RKE2 cluster, with no
changes to the apps. Built on the connected side (this Mac), carried across as a tar, installed
with plain `helm package` + `helm install`. No install scripts on the high side, by request.

## Layout

- `bundle/src/poc.js`: the one browser script. esbuild bundles it (OTel web SDK + OpenObserve RUM
  and logs, recorder included) into `bundle/dist/poc.js`, target `firefox115,chrome109`.
  High-side users run Firefox ESR 140.
- `proxy/Containerfile`: nginx `1.30.5-alpine` with `poc.js` baked in at `/usr/share/nginx/poc/`.
  The stand-in app reuses this image.
- `chart/`: the Helm chart. OpenObserve, the Collector, the proxy, an optional stand-in app, and
  one ingress per app plus one for OpenObserve.
- `build.sh`: builds everything for linux/amd64 into `out/` (images tar, `chart/`, `README.md`,
  `SHA256SUMS`). `otel-poc-transfer.tar` is just `out/` tarred up for the transfer.
- `README.md`: high-side install steps. Keep it short; it ships in the bundle.

## How data flows

nginx sits in front of each app (one `server` block per `apps[]` entry, matched by hostname) and
`sub_filter`s a `<script defer src="/__poc/poc.js" data-app data-version data-env data-org
data-privacy>` tag before `</head>`. Everything the browser sends stays on the app's own origin,
so there is no CORS and a CSP only needs `'self'`:

- `/__poc/otlp/` → Collector `:4318` (OTLP HTTP) → OpenObserve traces stream `default`
- `/__poc/o2/rum/` → OpenObserve `/rum/` (RUM events to `_rumdata`, replay to `_sessionreplay`)
- `/__poc/o2/token` → OpenObserve `/api/<org>/rumtoken`, GET only, nginx adds Basic auth
- nginx access log → syslog UDP `:5140` → Collector `syslog/nginx` receiver → logs stream `nginx_access`

OpenObserve flattens span attributes: query `target_text`, `page_path`, `rum_session_id`, not
`attributes_*`. `rum_session_id` on spans equals `session_id` in the replay streams.

## Decisions that look odd but are deliberate

- **RUM token fetched at runtime.** OpenObserve generates the org RUM token at startup and RUM
  ingest rejects anything else (Basic auth doesn't work there). Serving it from the proxy is what
  lets a single `helm install` work. The nginx config therefore holds credentials and is a
  **Secret**, not a ConfigMap.
- **Capture listener on `document`.** The user-interaction instrumentation only sees listeners
  added with `addEventListener`, so `onclick=` legacy pages produced no spans. `poc.js` adds a
  no-op capture listener for `click`, `change`, `submit` after registering instrumentations.
- **Nested spans dropped.** The instrumentation opens one span per listener, chained. We keep only
  the root (`if (span.parentSpanContext) return true`), so one interaction is one span.
- **Interaction spans flushed immediately.** On a click that navigates, the page-hide flush lost to
  the replay upload for the browser's shared keepalive budget. `onEnd` force-flushes any span with
  `event_type`.
- **Syslog instead of filelog** for access logs: no shared volumes or node log DaemonSet.
- **OpenObserve on emptyDir by default**: the user wants nothing on disk for now, and the namespace
  enforces "restricted", which forbids hostPath. Setting `openobserve.hostPath` switches to a node
  directory (no StorageClass exists on the cluster).
- **Config checksums** hash the rendered `*-config.yaml` templates, so template-only changes roll
  the pods. Hashing `.Values` alone missed them once.
- `poc.js` is served with `Cache-Control: no-cache`; stale cached copies hid a fix during testing.
- **Everything runs as uid/gid 1000** under the "restricted" security context (the high-side
  cluster requires it). Stock nginx can't write `/var/run` or `/var/cache/nginx` as that user, so
  both nginx configs put `pid` and all `*_temp_path`s in `/tmp`, and the stand-in listens on 8080
  with its own config at `/etc/standin/nginx.conf`. A hostPath, if set, needs `chown 1000:1000` on
  the node or OpenObserve panics with "Permission denied". Test with `kubectl label ns otel-poc pod-security.kubernetes.io/enforce=restricted`.
- **Every pod sets `priorityClassName: mission-specific`** (high-side requirement). In kind, create
  it first: `kubectl create priorityclass mission-specific --value=1000000`.

## Version pins

Proxy `0.1.0`, OpenObserve `v1.0.4`, `otelcol-contrib` `0.161.0`, nginx `1.30.5-alpine`, chart
`0.1.0`. Bumping one means editing `build.sh`, the `image:` line in `chart/templates/`, the
comment at the top of `chart/values.yaml`, and the push loop in `README.md`. Rebuild the proxy
image (and bump its tag) whenever `poc.js` changes.

## Testing locally with kind

```bash
kind create cluster --name otel-poc
(cd bundle && npm run build)
R=registry.local:5000/otel-poc
docker build -t $R/proxy:0.1.0 -f proxy/Containerfile .
docker pull public.ecr.aws/zinclabs/openobserve:v1.0.4 && docker tag public.ecr.aws/zinclabs/openobserve:v1.0.4 $R/openobserve:v1.0.4
docker pull otel/opentelemetry-collector-contrib:0.161.0 && docker tag otel/opentelemetry-collector-contrib:0.161.0 $R/otelcol-contrib:0.161.0
kind load docker-image --name otel-poc $R/proxy:0.1.0 $R/openobserve:v1.0.4 $R/otelcol-contrib:0.161.0
printf 'apps:\n  - {name: standin, version: "1.0", host: standin.localhost, upstream: "http://otel-poc-standin:8080"}\n' > /tmp/kind-values.yaml
helm package chart -d /tmp && helm install otel-poc /tmp/otel-poc-0.1.0.tgz -n otel-poc --create-namespace -f /tmp/kind-values.yaml --wait
kubectl -n otel-poc port-forward svc/otel-poc-proxy 8080:80 &
kubectl -n otel-poc port-forward svc/otel-poc-openobserve 5080:5080 &
```

Browse `http://standin.localhost:8080` (Chrome and Firefox resolve `*.localhost`) and
`http://localhost:5080` (`admin@poc.local` / `ChangeMe-PoC-1`). After a rebuild with the same
tag, `kind load` again and `kubectl rollout restart deploy/otel-poc-proxy`; port-forwards die on
every proxy rollout. Tear down with `kind delete cluster --name otel-poc`.

## Known gaps

- Replay playback in the OpenObserve UI hasn't been watched end to end; segments do arrive.
- RUM's own click `action` events don't show up in `_rumdata`. The OTel spans are the usage record.
- Release name must stay `otel-poc` unless the stand-in's `upstream` in `values.yaml` changes.
- High-side snags (SELinux on the hostPath, CIS Pod Security, upstream DNS at nginx start, the
  `Host` header) are listed in `README.md`.

## Next

Phase 4: walk a real app, build a feature catalog (name, URL pattern, selector), load it into
OpenObserve as an enrichment table, and build the "never used" dashboard sorted by ascending use.
