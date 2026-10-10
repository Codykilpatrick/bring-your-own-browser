# Demo walkthrough: usage evidence for Redpanda Console and Headlamp

What the customer should leave with: for each app, a list of every cataloged feature with how
often it was used, which ones nobody touched, and proof that collection was running the whole
time. Measured per feature and per app, never per person.

Say this up front: the window is a few days of lab traffic, not a deployment period. It shows the
method works. A deprecation decision needs weeks of real use.

## Before the demo

1. Install with proxy `0.1.1` and both apps under `apps` in `values.yaml` (see the commented
   examples). Give Headlamp `privacy: mask`.
2. Load `feature_catalog` and the three dashboards (README, "Load the feature catalog and usage
   dashboards").
3. Run the click list below at least a day ahead, from a few different browsers or private
   windows, so sessions are separate.
4. Do not restart the OpenObserve pod after that. Its storage is in the pod and a restart wipes
   the traffic.

## High-side click list

Each line is one browser session. Open the app, do the steps in order, close the window.
Click the sidebar and tabs; don't type addresses, so each step is an in-app page change.

### Redpanda Console (`http://<proxy IP>:<console port>/`)

1. Overview, Topics, open a topic, Partitions tab, Consumer Groups, open a group.
2. Overview, Topics, open a topic, Consumers tab, Produce Record, Produce (sends one record to a
   test topic only).
3. Topics, open a different topic, Configuration tab, Schema Registry.
4. Overview, Topics, open a topic, Messages tab, Consumer Groups.

Skip: Security, Quotas, Connect, Shadow Links, Debug bundle, the topic ACL and Documentation tabs,
Create topic, Delete Records, Edit Group, Delete Group. These are the "never used" rows.

### Headlamp (`http://<proxy IP>:<headlamp port>/`)

Log in with a read-only service account token in every session.

1. Cluster, Namespaces, Nodes, Workloads, Pods, click a pod, Show Logs (the page icon), close
   the panels, Deployments.
2. Workloads, Deployments, click a deployment, Scale, press Escape without saving, close the
   panel, Services.
3. Workloads, Pods, click a pod, close the panel, Config Maps, Map.
4. Cluster, Nodes, Namespaces.

Skip everything else. **Never open Secrets** during the traffic run, even though replays are
masked. Don't Edit, Delete, Restart, Evict, or open a terminal on anything.

What this leaves: Console about 10 of 20 pages and 2 of 11 controls used; Headlamp about 10 of 50
pages and 2 of 10 controls used. Several features land in "low use" (one or two sessions).

## Demo sequence

1. **Usage summary.** "Apps at a glance": sessions, views, days observed, and "10 of 50 cataloged
   pages used". "Reads vs writes": most interactions are clicks; write controls were used once.
2. **Feature inventory.** The pages and controls tables start with the zero rows. Point at a few:
   Console's Security and Quotas, Headlamp's Secrets, CronJobs, Network Policies, and the
   Delete/Edit controls. "Pages used vs never used" gives the count per app.
3. **Collection coverage.** This is what makes a zero believable:
   - The three hourly charts show the proxy, the browser events, and the spans all flowing during
     the window. `_probe` is the proxy's own health check: if it's there, the proxy was up even in
     hours nobody used the apps.
   - "Is the script reaching browsers?" should be near 100 for each app.
   - "Browser errors" with `error_from = poc.js` should be empty. Errors from the apps themselves
     are normal.
4. **A used feature and its replay.** Back on Feature inventory, "Low use pages" or "Low use
   controls": click a row (for example Headlamp's Pod logs) to open that session's replay. On
   Headlamp every piece of text, including the logs and the token login, shows as `x`.

## Caveats to state if asked

- A page counts as used if a session opened it. Views count only if the page stayed open a second
  or more (redirect hops don't count).
- Controls are counted by their visible text or aria-label on a page. Controls with neither can't
  be told apart and aren't in the catalog.
- Headlamp opens pod and deployment details in a side panel without changing the address, so
  details aren't separate pages; their buttons (Show Logs, Scale, Edit) are counted as controls on
  the list page.
- Console topic tabs are told apart by the `#tab` in the address. Interaction spans don't carry
  that part, so a control on a topic counts for the topic, not the tab.
- Clicking a row in Headlamp records the resource's name (a pod name, for example) as the clicked
  label. That is why Secrets stay out of the traffic run.
