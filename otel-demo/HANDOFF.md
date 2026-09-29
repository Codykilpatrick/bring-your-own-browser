# Handoff — 28 Sep 2026

Usage tracking PoC for a legacy Vue app (`sonsym` / sysvue) on airgapped RKE2. No app changes.
The proxy injects one script and ships clicks plus session replay to OpenObserve.

Cody left mid-check of the first real-app load. Images are already on the high-side registry.
Only chart updates have been crossing since this morning.

## Where things stand

The stack is installed and running. Browsers do **not** use ingress hostnames; they use Multus IPs.

| What | How you reach it |
|---|---|
| OpenObserve UI | `http://<OpenObserve Multus IP>:5080` |
| sysvue through the proxy | `http://<proxy Multus IP>:8081/sysvue/` |
| sysvue without tracking | `https://sysvue:8443/sysvue/` |

OpenObserve login: `admin@poc.local` / `ChangeMe-PoC-1` (unless `rootPassword` was changed).

The first page of sysvue **does load** through the proxy. Then the UI says connection unavailable.
Cody's last proxy log:

```
connect() failed (111: Connection refused) while connecting to upstream
GET /__poc/o2/token
upstream http://10.161.101.118:5080/api/default/rumtoken
```

That token error is OpenObserve, not sysvue. It only turns replay off. The "connection unavailable"
after a flash of the real UI is almost certainly sysvue's own live WebSocket dying. The **current
high-side chart does not pass WebSockets**. That fix is in the latest chart-only tar and has **not**
been applied on the high side yet.

## Apply first thing tomorrow

`otel-demo/otel-poc-chart-update.tar`, checksum `961514e5…f030` in
`otel-poc-chart-update.tar.sha256`. Chart only. Images are unchanged (`proxy:0.1.0`,
OpenObserve `v1.0.4`, `otelcol-contrib` `0.161.0`).

This tar includes WebSocket passthrough (`Upgrade` / `Connection`, HTTP/1.1, 1h read timeout).
In kind, the old chart returned `200` on a WebSocket upgrade; this one returns `101` and the
message gets through.

It **overwrites** `chart/` and `README.md`. If Cody hand-edited `proxy-config.yaml` on the high
side, that edit is gone. Keep high-side settings in a file **outside** `chart/` (`my-values.yaml`)
so the next extract does not wipe them.

```bash
sha256sum -c otel-poc-chart-update.tar.sha256   # OK
tar -xf otel-poc-chart-update.tar
helm package chart
helm upgrade otel-poc otel-poc-0.1.0.tgz -n <namespace> -f my-values.yaml
```

Then confirm nginx has the port and the upgrade map:

```bash
kubectl -n <ns> exec deploy/otel-poc-proxy -- grep -n "listen 8081\|connection_upgrade" /etc/nginx/nginx.conf
```

## Values that should be on the high side

Release name must stay `otel-poc`. Namespace is whatever `otel-ns.yaml` created.

```yaml
apps:
  - name: standin          # keep or set standin.enabled: false
    ...
  - name: sonsym
    version: "4.2"
    host: sym.poc.local
    upstream: https://sysvue:8443    # no /sysvue path
    port: 8081
    stripCsp: true                   # tried; did not fix the drop
proxy:
  hostAliases:
    - ip: <sysvue IP>                # or put the IP in upstream
      hostnames: [sysvue]
  networkAttachments:
    enabled: true
    entries: [<same shape as other Multus charts>]
openobserve:
  networkAttachments:
    enabled: true
    entries: [<own IP if they pin IPs; do not share with the proxy>]
```

`networkAttachments` is the team pattern: `enabled` plus `entries`, rendered as
`toJson | quote` on `k8s.v1.cni.cncf.io/networks`. Separate blocks per pod.

`port: 8081` lives on the **app** entry, not under `proxy`. Without it, a bare Multus IP
matches no `server_name` and you get a blank/404.

## After the upgrade

1. Get the proxy Multus IP (not the `default` CNI one):

   ```bash
   kubectl -n <ns> get pod -l app=otel-poc-proxy \
     -o jsonpath='{.items[0].metadata.annotations.k8s\.v1\.cni\.cncf\.io/network-status}'
   ```

2. Open `http://<proxy Multus IP>:8081/sysvue/`. View source should show `data-app="sonsym"`.
   Stay on the page. If the live UI holds, WebSockets were the issue.

3. If it still drops, from inside the pod:

   ```bash
   kubectl -n <ns> exec deploy/otel-poc-proxy -- \
     wget -qO- http://127.0.0.1:8081/sysvue/ | grep -o '<script defer src="/__poc/poc.js"[^>]*>'
   kubectl -n <ns> logs deploy/otel-poc-proxy --tail=30
   ```

4. Fix OpenObserve for the token/replay path:

   ```bash
   kubectl -n <ns> get pods -l app=otel-poc-openobserve
   kubectl -n <ns> get endpoints otel-poc-openobserve
   ```

   Empty endpoints plus a working Multus UI usually means the attachment stole the default
   route, so kubelet health checks fail and the Service stops sending cluster traffic.
   Attachment should not install a default route (or should only route the lab subnet).

5. Click a normal workflow, then in OpenObserve:

   - Logs → `nginx_access`: `app: sonsym`
   - Traces → `default`: `click` spans, `service_name: sonsym`
   - RUM → Sessions: a replay (needs the token endpoint working)

6. Watch the address bar as screens change. `/sysvue/ports` is fine. `/sysvue/#/ports` means
   every screen records as `/sysvue/` and the script needs a one-line change plus a **new
   proxy image** before collecting real data.

## Do not

- Restart the OpenObserve pod. Data is emptyDir. A restart wipes usage and replays.
- Put `/sysvue` on `upstream`. Users open that path on the proxy URL.
- Expect `*.poc.local` to work. Ingress DNS is unused here.

## Already on the high side (do not redo)

Images pushed. Restricted security context (uid 1000). `priorityClassName: mission-specific`.
CPU limits. emptyDir storage. `hostAliases` or IP upstream (proxy used to crash-loop on
`host not found in upstream "sysvue"`). Multus `networkAttachments`. `port: 8081`.

## Then (Phase 4)

Walk sysvue, list features (name, URL, selector). Build the "never used" dashboard from:

```sql
SELECT page_path, target_text, count(*) AS uses FROM "default"
WHERE service_name = 'sonsym' AND event_type IS NOT NULL
GROUP BY page_path, target_text ORDER BY uses
```

## Source on the connected Mac

`bring-your-own-browser/otel-demo/`. `CLAUDE.md` is the code notes. `README.md` is the install
card. `./build.sh` rebuilds `out/` (images + chart). Chart-only transfers are
`otel-poc-chart-update.tar`.
