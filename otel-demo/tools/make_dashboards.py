#!/usr/bin/env python3
"""Writes the three usage dashboards to chart/files/dashboards/. Connected side only.

The panel boilerplate is copied from chart/files/dashboard.json so the files match what
OpenObserve v1.0.4 exports. Edit the SQL here and rerun, rather than editing the JSON.

Rules every query follows (see CLAUDE.md, "Usage dashboards"):
- One stream type per query. A catalog join next to a cross-type stream drops rows silently.
- Catalog joins need the equi key (app) in ON; a regexp-only ON clause returns nothing.
- Page paths: origin, query string, and trailing slash dropped, hash kept. Spans have no hash.
"""
import copy, json, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = json.load(open(ROOT / 'chart/files/dashboard.json'))
TEMPLATE = {p['type']: p for t in BASE['tabs'] for p in t['panels']}


def APP(col):
    return f"({col} IN ($app) OR '_o2_all_' IN ($app))"


PATH = r"regexp_replace(regexp_replace(view_url, '^https?://[^/]+|[?][^#]*', '', 'g'), '(.)/(#|$)', '$1$2')"
CPATH = r"regexp_replace(s.page_path, '(.)/$', '$1')"
INTERACTION = "s.event_type IN ('click', 'change', 'submit')"
# One row per RUM view. RUM re-sends a view as it updates, so group by view_id.
VIEWS = (f'(SELECT service, session_id, view_id, {PATH} AS path, max(view_time_spent) AS spent, '
         f'min(_timestamp) AS ts FROM "_rumdata" WHERE type = \'view\' AND {APP("service")} '
         'GROUP BY service, session_id, view_id, path)')
# Redirect hops (Console's /topics -> /topics/) last well under a second; don't count them as views.
REAL_VIEW = 'v.spent >= 1e9'
PAGE_JOIN = 'v.service = c.app AND regexp_like(v.path, c.path_pattern)'
CTRL_JOIN = f's.service_name = c.app AND {INTERACTION} AND s.target_text = c.element_label AND regexp_like({CPATH}, c.path_pattern)'
CATALOG = 'enrich.feature_catalog'


def utc(ts):
    # As text: a timestamp column that starts with nulls (never used) shows as raw microseconds.
    return f"to_char(to_timestamp_micros({ts}), '%Y-%m-%d %H:%M')"


INV_PAGES = (f'SELECT c.app, c.feature_name AS feature, count(DISTINCT v.session_id) AS sessions, '
             f'count(DISTINCT CASE WHEN {REAL_VIEW} THEN v.view_id END) AS views, {utc("max(v.ts)")} AS last_used_utc, c.feature_id '
             f'FROM {CATALOG} c LEFT JOIN {VIEWS} v ON {PAGE_JOIN} '
             f"WHERE c.kind = 'page' AND {APP('c.app')} GROUP BY c.app, c.feature_name, c.feature_id")
INV_CTRL = (f'SELECT c.app, c.feature_name AS feature, c.element_label AS label, c.writes, count(s.span_id) AS uses, '
            f'count(DISTINCT s.rum_session_id) AS sessions, {utc("max(s._timestamp)")} AS last_used_utc, c.feature_id '
            f'FROM {CATALOG} c LEFT JOIN "default" s ON {CTRL_JOIN} '
            f"WHERE c.kind = 'control' AND {APP('c.app')} GROUP BY c.app, c.feature_name, c.element_label, c.writes, c.feature_id")
SESSION_BOUNDS = (f'(SELECT session_id, min(_timestamp) AS start_us, max(_timestamp) AS end_us FROM "_rumdata" '
                  f'WHERE {APP("service")} GROUP BY session_id)')


def hours(ts):
    return f"count(DISTINCT date_trunc('hour', to_timestamp_micros({ts})))"


REPLAY = [{'name': 'Open replay', 'type': 'byUrl', 'targetBlank': True, 'findBy': 'name', 'data': {
    'url': '${o2_url}/web/rum/sessions/view/${row.field.session_id}?start_time=${row.field.start_us}'
           '&end_time=${row.field.end_us}&org_identifier=default',
    'folder': '', 'dashboard': '', 'tab': '', 'passAllVariables': True, 'variables': [], 'logsMode': 'auto', 'logsQuery': ''}}]


def field(alias, timestamp=False):
    return {'label': '', 'alias': alias, 'column': alias, 'color': None, 'isDerived': False, 'havingConditions': [],
            'treatAsNonTimestamp': not timestamp, 'type': 'custom', 'functionName': None, 'args': []}


def panel(n, kind, title, desc, sql, stream, layout, text=(), nums=(), times=(), seconds=(), replay=False):
    """kind: table, metric, or a chart type (line, bar, stacked) with x_axis_1/breakdown_1/y_axis_1 columns."""
    p = copy.deepcopy(TEMPLATE['table' if kind == 'table' else 'metric' if kind == 'metric' else 'line'])
    p.update(id=f'Panel_ID{n}', type=kind, title=title, description=desc)
    p['layout'] = dict(zip('xywh', layout), i=n)
    q = p['queries'][0]
    q['query'] = ' '.join(sql.split())
    stream_type = 'traces' if stream == 'default' else 'logs'
    q['fields'].update(stream=stream, stream_type=stream_type)
    cfg = p['config']
    cfg.update(drilldown=REPLAY if replay else [], override_config=[], decimals=0)
    if kind == 'table':
        q['fields']['x'] = [field(a, a in times) for a in (*text, *times)]
        q['fields']['y'] = [field(a) for a in nums]
        # Counts as text so OpenObserve doesn't abbreviate them or hide a zero.
        cfg['override_config'] = (
            [{'field': {'matchBy': 'name', 'value': a}, 'config': [{'type': 'field_type', 'value': 'text'}, {'type': 'alignment', 'value': 'right'}]} for a in nums if a not in seconds]
            + [{'field': {'matchBy': 'name', 'value': a}, 'config': [{'type': 'unit', 'value': {'unit': 'seconds', 'customUnit': ''}}]} for a in seconds])
        cfg['table_pagination'] = False
    elif kind == 'metric':
        q['fields']['x'], q['fields']['y'], q['fields']['breakdown'] = [], [field('y_axis_1')], []
    else:
        q['fields']['x'] = [field('x_axis_1', timestamp=True)]
        q['fields']['y'] = [field('y_axis_1')]
        q['fields']['breakdown'] = [field('breakdown_1')]
    return p


def dashboard(slug, title, desc, panels):
    d = {k: copy.deepcopy(BASE[k]) for k in ('version', 'role', 'owner', 'created', 'variables')}
    d.update(dashboardId=slug, title=title, description=desc,
             defaultDatetimeDuration={'type': 'relative', 'relativeTimePeriod': '7d', 'startTime': None, 'endTime': None},
             tabs=[{'tabId': 'default', 'name': 'Default', 'panels': panels}])
    return d


W = 192  # grid width


def feature_inventory():
    low_pages = (f'SELECT f.app, f.feature, x.session_id, x.start_us AS started, x.start_us, x.end_us FROM '
                 f'(SELECT c.feature_id, c.app, c.feature_name AS feature FROM {CATALOG} c JOIN {VIEWS} v ON {PAGE_JOIN} '
                 f"WHERE c.kind = 'page' GROUP BY c.feature_id, c.app, c.feature_name HAVING count(DISTINCT v.session_id) <= 2) f "
                 f'JOIN (SELECT DISTINCT c.feature_id, v.session_id FROM {CATALOG} c JOIN {VIEWS} v ON {PAGE_JOIN} '
                 f"WHERE c.kind = 'page') fs ON fs.feature_id = f.feature_id "
                 f'JOIN {SESSION_BOUNDS} x ON x.session_id = fs.session_id ORDER BY f.app, f.feature, started')
    low_ctrls = (f'SELECT f.app, f.feature, f.label, s.rum_session_id AS session_id, min(s._timestamp) AS used_at, '
                 f'min(s._timestamp) - 600000000 AS start_us, max(s._timestamp) + 600000000 AS end_us FROM '
                 f'(SELECT c.feature_id, c.app, c.feature_name AS feature, c.element_label AS label, c.path_pattern FROM {CATALOG} c '
                 f'JOIN "default" s ON {CTRL_JOIN} WHERE c.kind = \'control\' AND {APP("c.app")} '
                 f'GROUP BY c.feature_id, c.app, c.feature_name, c.element_label, c.path_pattern HAVING count(DISTINCT s.rum_session_id) <= 2) f '
                 f'JOIN "default" s ON s.service_name = f.app AND {INTERACTION} AND s.target_text = f.label AND regexp_like({CPATH}, f.path_pattern) '
                 f'GROUP BY f.app, f.feature, f.label, s.rum_session_id ORDER BY f.app, f.feature, used_at')
    per_app = lambda inv, used: (f'SELECT app, count(*) AS cataloged, sum(CASE WHEN {used} > 0 THEN 1 ELSE 0 END) AS used, '
                                 f'sum(CASE WHEN {used} = 0 THEN 1 ELSE 0 END) AS never_used FROM ({inv}) GROUP BY app ORDER BY app')
    uncat_pages = (f'SELECT v.service AS app, v.path AS page, count(DISTINCT v.session_id) AS sessions, count(DISTINCT v.view_id) AS views '
                   f"FROM {VIEWS} v LEFT JOIN {CATALOG} c ON c.app = v.service AND c.kind = 'page' AND regexp_like(v.path, c.path_pattern) "
                   f'WHERE c.feature_id IS NULL AND {REAL_VIEW} GROUP BY v.service, v.path ORDER BY sessions DESC, views DESC LIMIT 25')
    uncat_ctrls = (f'SELECT s.service_name AS app, {CPATH} AS page, s.target_text AS label, count(*) AS uses, count(DISTINCT s.rum_session_id) AS sessions '
                   f"FROM \"default\" s LEFT JOIN {CATALOG} c ON c.app = s.service_name AND c.kind = 'control' AND s.target_text = c.element_label "
                   f'AND regexp_like({CPATH}, c.path_pattern) '
                   f"WHERE c.feature_id IS NULL AND {INTERACTION} AND s.target_text <> '' AND s.target_element NOT IN ('A', 'BODY', 'HTML') "
                   f"AND s.target_xpath NOT LIKE '%/a/%' AND s.target_xpath NOT LIKE '%/nav/%' "
                   f'AND {APP("s.service_name")} GROUP BY s.service_name, {CPATH}, s.target_text ORDER BY sessions DESC, uses DESC LIMIT 25')
    return dashboard('feature-inventory', 'Feature inventory',
                     'Every cataloged page and control with how often it was used, least used first. Catalog: feature_catalog enrichment table.', [
        panel(2001, 'table', 'Pages: sessions and views, least used first',
              'Every page row in the catalog. Sessions that opened the page at least once, and views that stayed open a second or more. '
              'Zero means no recorded browser opened it in the selected time range; check Collection coverage before calling it unused.',
              INV_PAGES + ' ORDER BY sessions ASC, views ASC, c.app, feature', '_rumdata', (0, 0, 96, 40),
              text=('app', 'feature', 'last_used_utc'), nums=('sessions', 'views')),
        panel(2002, 'table', 'Controls: uses and sessions, least used first',
              'Every control row in the catalog: clicks, changes, and submits on that label on that page. writes = y means it changes state. '
              'Controls with no visible text or aria-label cannot be told apart and are not in the catalog.',
              INV_CTRL + ' ORDER BY uses ASC, c.app, feature', 'default', (96, 0, 96, 40),
              text=('app', 'feature', 'label', 'writes', 'last_used_utc'), nums=('uses', 'sessions')),
        panel(2003, 'table', 'Pages: used vs never used, per app', 'Cataloged pages per app, split by whether any session opened them.',
              per_app(INV_PAGES, 'sessions'), '_rumdata', (0, 40, 96, 12), text=('app',), nums=('cataloged', 'used', 'never_used')),
        panel(2004, 'table', 'Controls: used vs never used, per app', 'Cataloged controls per app, split by whether anyone used them.',
              per_app(INV_CTRL, 'uses'), 'default', (96, 40, 96, 12), text=('app',), nums=('cataloged', 'used', 'never_used')),
        panel(2005, 'table', 'Low use pages: opened in only 1 or 2 sessions', 'One row per session. Click a row to open its replay.',
              low_pages, '_rumdata', (0, 52, 96, 24), text=('app', 'feature', 'session_id'), times=('started',), replay=True),
        panel(2006, 'table', 'Low use controls: used in only 1 or 2 sessions',
              'One row per session. Click a row to open its replay around the time the control was used.',
              low_ctrls, 'default', (96, 52, 96, 24), text=('app', 'feature', 'label', 'session_id'), times=('used_at',), replay=True),
        panel(2007, 'table', 'Pages seen that are not in the catalog',
              'Page paths (query string dropped) that matched no catalog row. Add rows for real features so they show in the inventory.',
              uncat_pages, '_rumdata', (0, 76, 96, 26), text=('app', 'page'), nums=('sessions', 'views')),
        panel(2008, 'table', 'Labelled controls used that are not in the catalog',
              'Clicked or changed controls with a label that matched no catalog row. Links and sidebar entries are left out; they show up as page views.',
              uncat_ctrls, 'default', (96, 76, 96, 26), text=('app', 'page', 'label'), nums=('uses', 'sessions')),
    ])


def usage_summary():
    glance = (f'SELECT r.app, r.first_seen, r.last_seen, r.days_observed, r.sessions, r.views, '
              f"concat(cast(p.used AS varchar), ' of ', cast(p.cataloged AS varchar)) AS cataloged_pages_used FROM "
              f"(SELECT service AS app, min(ts) AS first_seen, max(ts) AS last_seen, count(DISTINCT date_trunc('day', to_timestamp_micros(ts))) AS days_observed, "
              f'count(DISTINCT session_id) AS sessions, count(DISTINCT CASE WHEN spent >= 1e9 THEN view_id END) AS views FROM {VIEWS} GROUP BY service) r '
              f'LEFT JOIN (SELECT app, count(*) AS cataloged, sum(CASE WHEN sessions > 0 THEN 1 ELSE 0 END) AS used FROM ({INV_PAGES}) GROUP BY app) p '
              f'ON p.app = r.app ORDER BY r.app')
    interactions = (f'SELECT i.app, i.interactions, i.clicks, i.changes_and_submits, w.write_control_uses, '
                    f"concat(cast(w.used AS varchar), ' of ', cast(w.cataloged AS varchar)) AS cataloged_controls_used FROM "
                    f"(SELECT service_name AS app, count(*) AS interactions, count(*) FILTER (WHERE event_type = 'click') AS clicks, "
                    f"count(*) FILTER (WHERE event_type IN ('change', 'submit')) AS changes_and_submits FROM \"default\" s "
                    f'WHERE {INTERACTION} AND {APP("service_name")} GROUP BY service_name) i '
                    f"LEFT JOIN (SELECT app, sum(CASE WHEN writes = 'y' THEN uses ELSE 0 END) AS write_control_uses, "
                    f'sum(CASE WHEN uses > 0 THEN 1 ELSE 0 END) AS used, count(*) AS cataloged FROM ({INV_CTRL}) GROUP BY app) w '
                    f'ON w.app = i.app ORDER BY i.app')
    return dashboard('usage-summary', 'Usage summary', 'How much each app behind the otel-poc proxy is used, per app.', [
        panel(3001, 'table', 'Apps at a glance', 'First and last recorded view, days with at least one view, sessions, '
              'views that stayed open a second or more, and how many cataloged pages were opened at least once.',
              glance, '_rumdata', (0, 0, W, 12), text=('app',), times=('first_seen', 'last_seen'),
              nums=('days_observed', 'sessions', 'views', 'cataloged_pages_used')),
        panel(3002, 'table', 'Reads vs writes per app',
              'Clicks are mostly reading and navigating. Changes and submits are form edits. Write control uses count clicks on cataloged '
              'controls that change state (writes = y), such as Delete or Scale.',
              interactions, 'default', (0, 12, W, 12), text=('app',),
              nums=('interactions', 'clicks', 'changes_and_submits', 'write_control_uses', 'cataloged_controls_used')),
        panel(3003, 'stacked', 'Sessions per day, per app', 'Browser sessions with at least one recorded event that day.',
              f'SELECT histogram(_timestamp, \'1 day\') AS "x_axis_1", service AS "breakdown_1", count(DISTINCT session_id) AS "y_axis_1" '
              f'FROM "_rumdata" WHERE {APP("service")} GROUP BY x_axis_1, breakdown_1 ORDER BY x_axis_1 ASC',
              '_rumdata', (0, 24, W, 18)),
        panel(3004, 'table', 'Top 10 pages by sessions', 'Cataloged pages opened in the most sessions.',
              f'SELECT app, feature, sessions, views FROM ({INV_PAGES}) WHERE sessions > 0 ORDER BY sessions DESC, views DESC LIMIT 10',
              '_rumdata', (0, 42, 96, 22), text=('app', 'feature'), nums=('sessions', 'views')),
        panel(3005, 'table', 'Top 10 controls by sessions', 'Cataloged controls used in the most sessions.',
              f'SELECT app, feature, label, sessions, uses FROM ({INV_CTRL}) WHERE uses > 0 ORDER BY sessions DESC, uses DESC LIMIT 10',
              'default', (96, 42, 96, 22), text=('app', 'feature', 'label'), nums=('sessions', 'uses')),
    ])


def collection_coverage():
    injection = (f'SELECT n.app, n.html_loads, r.initial_loads, r.sessions, round(100.0 * r.initial_loads / n.html_loads) AS pct_with_script FROM '
                 f"(SELECT app, count(*) AS html_loads FROM \"nginx_access\" WHERE method = 'GET' AND status = 200 AND content_type LIKE 'text/html%' "
                 f"AND NOT regexp_like(uri, '^/(api|__poc)/') AND {APP('app')} GROUP BY app) n "
                 f"LEFT JOIN (SELECT service AS app, count(DISTINCT CASE WHEN view_loading_type = 'initial_load' THEN view_id END) AS initial_loads, "
                 f"count(DISTINCT session_id) AS sessions FROM \"_rumdata\" WHERE type = 'view' AND {APP('service')} GROUP BY service) r "
                 f'ON r.app = n.app ORDER BY n.app')
    errors = (f"SELECT service AS app, CASE WHEN error_stack LIKE '%/__poc/%' THEN 'poc.js' ELSE 'app' END AS error_from, "
              f'substr(error_message, 1, 140) AS message, count(*) AS errors, count(DISTINCT session_id) AS sessions '
              f"FROM \"_rumdata\" WHERE type = 'error' AND {APP('service')} GROUP BY 1, 2, 3 "
              f"ORDER BY error_from DESC, errors DESC LIMIT 30")
    traffic = (f'SELECT t.app, t.hours_with_traffic, h.hours_proxy_up, t.first_request, t.last_request FROM '
               f"(SELECT app, {hours('_timestamp')} AS hours_with_traffic, min(_timestamp) AS first_request, max(_timestamp) AS last_request "
               f"FROM \"nginx_access\" WHERE app NOT IN ('', '_probe') AND {APP('app')} GROUP BY app) t "
               f"LEFT JOIN (SELECT 1 AS k, {hours('_timestamp')} AS hours_proxy_up FROM \"nginx_access\" WHERE app = '_probe') h ON h.k = 1 "
               f'ORDER BY t.app')
    per_hour = lambda stream, col, extra='': (
        f'SELECT histogram(_timestamp, \'1 hour\') AS "x_axis_1", {col} AS "breakdown_1", count(*) AS "y_axis_1" '
        f'FROM "{stream}" WHERE {extra}{APP(col)} GROUP BY x_axis_1, breakdown_1 ORDER BY x_axis_1 ASC')
    return dashboard('collection-coverage', 'Collection coverage',
                     'Was collection running? Use this before calling a zero on Feature inventory real.', [
        panel(4001, 'bar', 'Proxy requests per hour (nginx_access)',
              'Every request through the proxy, server side. "_probe" is the proxy\'s own health check every 10 seconds: '
              'if it is there, the proxy was up even when no one used the apps.',
              f'SELECT histogram(_timestamp, \'1 hour\') AS "x_axis_1", app AS "breakdown_1", count(*) AS "y_axis_1" FROM "nginx_access" '
              f"WHERE app <> '' AND ({APP('app')} OR app = '_probe') GROUP BY x_axis_1, breakdown_1 ORDER BY x_axis_1 ASC",
              'nginx_access', (0, 0, 64, 18)),
        panel(4002, 'bar', 'Browser events per hour (_rumdata)', 'Views, errors, and resource timings sent by the injected script.',
              per_hour('_rumdata', 'service'), '_rumdata', (64, 0, 64, 18)),
        panel(4003, 'bar', 'Spans per hour (traces)', 'Interaction, page load, and request spans sent by the injected script.',
              per_hour('default', 'service_name'), 'default', (128, 0, 64, 18)),
        panel(4004, 'table', 'Is the script reaching browsers?',
              'HTML pages the proxy served (status 200) vs. page loads the injected script reported. Close to 100 means the script ran. '
              'Pages revalidated from the browser cache (304) are not counted as HTML loads, so this can go over 100. '
              'One page load covers every screen of a single-page app, so this is not compared with total views.',
              injection, 'nginx_access', (0, 18, 96, 14), text=('app',), nums=('html_loads', 'initial_loads', 'sessions', 'pct_with_script')),
        panel(4005, 'table', 'Hours with traffic per app',
              'Hours in which the app had at least one request, next to hours the proxy answered its health check. '
              'An hour with the proxy up and no traffic means nobody used the app, not that collection was down.',
              traffic, 'nginx_access', (96, 18, 96, 14), text=('app',), times=('first_request', 'last_request'),
              nums=('hours_with_traffic', 'hours_proxy_up')),
        panel(4006, 'table', 'Browser errors: tracking script vs. app',
              'error_from = poc.js are errors in the injected tracking script (/__poc/); these can mean usage went unrecorded. '
              'error_from = app are the app\'s own errors.',
              errors, '_rumdata', (0, 32, W, 22), text=('app', 'error_from', 'message'), nums=('errors', 'sessions')),
    ])


if __name__ == '__main__':
    out = ROOT / 'chart/files/dashboards'
    out.mkdir(exist_ok=True)
    for name, d in [('feature-inventory', feature_inventory()), ('usage-summary', usage_summary()), ('collection-coverage', collection_coverage())]:
        (out / f'{name}.json').write_text(json.dumps(d, indent=2) + '\n')
        if '--sql' in sys.argv:
            for p in d['tabs'][0]['panels']:
                print(json.dumps({'title': p['title'], 'stream_type': p['queries'][0]['fields']['stream_type'], 'sql': p['queries'][0]['query']}))
