# otel-poc

Page and control usage plus session replay for legacy web apps, without changing the apps.
An nginx proxy sits in front of each app and injects one script tag. Data lands in OpenObserve.

- `nginx_access` log stream: every page hit, server side, no browser code needed
- `default` trace stream: one span per click, change, or submit, with page, element, label, and `rum_session_id`
- RUM, Sessions: replays, matched to the spans by `rum_session_id`

## Build (connected side)

```bash
./build.sh
```

Writes `out/`: `otel-poc-images.tar` (linux/amd64), `chart/`, and `SHA256SUMS`. Carry `out/` across.

## Install (high side)

1. Check the transfer: `sha256sum -c SHA256SUMS`, every line `OK`.
2. Load the images and push them to your registry under one path:

   ```bash
   podman load -i otel-poc-images.tar
   for img in proxy:0.1.0 openobserve:v1.0.4 otelcol-contrib:0.161.0; do
     podman tag localhost/otel-poc/$img <registry>/otel-poc/$img
     podman push <registry>/otel-poc/$img
   done
   ```

3. In `chart/values.yaml`, set `registry: <registry>/otel-poc`, the hostnames, and `openobserve.rootPassword`.
4. Package and install:

   ```bash
   helm package chart
   helm install otel-poc otel-poc-0.1.0.tgz -n otel-poc --create-namespace
   ```

Keep the release name `otel-poc`; the stand-in app's upstream assumes it.

## Check it

Point `standin.poc.local` and `openobserve.poc.local` at the ingress. Browse the stand-in and
click around, then log into OpenObserve at `http://openobserve.poc.local`:

- Email: `admin@poc.local` (`openobserve.rootEmail`)
- Password: `ChangeMe-PoC-1` (`openobserve.rootPassword`), unless you changed it in step 3

OpenObserve creates this user on first start. With the default in-pod storage, a restart
recreates it from the current values.

What you should see:

- Logs, `nginx_access`: your page hits
- Traces: `click`, `change`, `submit` spans
- RUM, Sessions: one session with a replay

## Add a real app

Add an entry under `apps` in `values.yaml` (name, version, host, upstream), then
`helm package chart && helm upgrade otel-poc otel-poc-0.1.0.tgz -n otel-poc`.
Set `stripCsp: true` on an app if its Content-Security-Policy blocks the script.
Set `standin.enabled: false` once you don't need the stand-in.

## Known snags

- Every pod runs as uid/gid 1000 with the "restricted" security context (`podSecurityContext`,
  `containerSecurityContext` in `values.yaml`).
- Nothing is written to the node. OpenObserve keeps its data inside the pod, so a pod restart
  wipes all collected usage and replays. To keep data, see the comment on `openobserve.hostPath`
  in `values.yaml`.
- nginx resolves upstream hostnames at startup. An upstream the pods can't resolve crash-loops the proxy.
- The proxy sends the upstream's own hostname as `Host`. Apps that build absolute links from it can send users around the proxy.
