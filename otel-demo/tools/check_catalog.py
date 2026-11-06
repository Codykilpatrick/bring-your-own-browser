#!/usr/bin/env python3
"""Check chart/files/feature_catalog.csv against what OpenObserve has seen. Connected side only.

Pulls every page path (RUM views) and page/label pair (click, change, submit spans) from the last
N days, normalized with the same SQL the dashboards use, then fails if any of them matches more
than one catalog row (the dashboards would count it twice) and lists the ones matching none.

    python3 tools/check_catalog.py [--url http://localhost:5080] [--days 7]
"""
import argparse, base64, csv, json, re, sys, time, urllib.request
from pathlib import Path

# Keep in step with the dashboards: origin, query string, and trailing slash dropped; hash kept.
PAGE_SQL = r"""SELECT service AS app, regexp_replace(regexp_replace(view_url, '^https?://[^/]+|[?][^#]*', '', 'g'), '(.)/(#|$)', '$1$2') AS path
FROM "_rumdata" WHERE type = 'view' GROUP BY app, path"""
CONTROL_SQL = r"""SELECT service_name AS app, regexp_replace(page_path, '(.)/$', '$1') AS path, target_text AS label
FROM "default" WHERE event_type IN ('click', 'change', 'submit') AND target_text <> '' GROUP BY app, path, label"""
COLUMNS = ['feature_id', 'app', 'feature_name', 'kind', 'path_pattern', 'element_label', 'writes', 'notes']


def search(args, sql, stream_type):
    now = int(time.time() * 1e6)
    body = {'query': {'sql': sql, 'start_time': now - args.days * 86400 * 10**6, 'end_time': now, 'from': 0, 'size': 10000}}
    req = urllib.request.Request(f'{args.url}/api/{args.org}/_search?type={stream_type}', json.dumps(body).encode(),
                                 {'Content-Type': 'application/json', 'Authorization': 'Basic ' + base64.b64encode(args.auth.encode()).decode()})
    return json.load(urllib.request.urlopen(req))['hits']


def load_catalog(path):
    rows, errors = list(csv.DictReader(open(path, newline=''))), []
    if rows and list(rows[0]) != COLUMNS:
        errors.append(f'columns are {list(rows[0])}, want {COLUMNS}')
    seen = set()
    for r in rows:
        fid = r['feature_id']
        if fid in seen: errors.append(f'{fid}: duplicate feature_id')
        seen.add(fid)
        if r['kind'] not in ('page', 'control'): errors.append(f'{fid}: kind must be page or control')
        if r['writes'] not in ('y', 'n'): errors.append(f'{fid}: writes must be y or n')
        if (r['kind'] == 'control') != bool(r['element_label']): errors.append(f'{fid}: element_label is for control rows only')
        if not (r['path_pattern'].startswith('^') and r['path_pattern'].endswith('$')): errors.append(f'{fid}: anchor path_pattern with ^ and $')
        try: r['re'] = re.compile(r['path_pattern'])
        except re.error as e: errors.append(f'{fid}: bad path_pattern: {e}')
    return rows, errors


def matches(rows, kind, app, path, label=None):
    return [r['feature_id'] for r in rows if r['kind'] == kind and r['app'] == app and r.get('re') and r['re'].search(path)
            and (label is None or r['element_label'] == label)]


def check(rows, pages, controls):
    """Returns (doubles, unmatched) for the observed pages and controls."""
    doubles, unmatched = [], []
    for p in pages:
        hit = matches(rows, 'page', p['app'], p['path'])
        if len(hit) > 1: doubles.append(f"page {p['app']} {p['path']} -> {hit}")
        if not hit: unmatched.append(f"page {p['app']} {p['path']}")
    for c in controls:
        hit = matches(rows, 'control', c['app'], c['path'], c['label'])
        if len(hit) > 1: doubles.append(f"control {c['app']} {c['path']} [{c['label']}] -> {hit}")
    return doubles, unmatched


def selftest():
    rows = [dict(feature_id=i, app='a', kind=k, element_label=l, re=re.compile(p)) for i, k, p, l in [
        ('topic', 'page', '^/topics/[^/#]+(#messages)?$', ''), ('parts', 'page', '^/topics/[^/#]+#partitions$', ''),
        ('dup', 'page', '^/topics/x#partitions$', ''), ('del', 'control', '^/topics/[^/#]+$', 'Delete')]]
    d, u = check(rows, [{'app': 'a', 'path': '/topics/o'}, {'app': 'a', 'path': '/topics/x#partitions'}, {'app': 'a', 'path': '/nope'}],
                 [{'app': 'a', 'path': '/topics/o', 'label': 'Delete'}])
    assert d == ["page a /topics/x#partitions -> ['parts', 'dup']"], d
    assert u == ['page a /nope'], u


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--catalog', default=Path(__file__).resolve().parent.parent / 'chart/files/feature_catalog.csv')
    ap.add_argument('--url', default='http://localhost:5080')
    ap.add_argument('--org', default='default')
    ap.add_argument('--auth', default='admin@poc.local:ChangeMe-PoC-1')
    ap.add_argument('--days', type=int, default=7)
    args = ap.parse_args()
    selftest()
    rows, errors = load_catalog(args.catalog)
    for e in errors: print('CATALOG', e)
    apps = {r['app'] for r in rows}
    pages = [p for p in search(args, PAGE_SQL, 'logs') if p['app'] in apps]
    controls = [c for c in search(args, CONTROL_SQL, 'traces') if c['app'] in apps]
    doubles, unmatched = check(rows, pages, controls)
    for d in doubles: print('DOUBLE', d)
    for u in unmatched: print('NO MATCH', u)
    print(f'{len(rows)} catalog rows, {len(pages)} observed pages, {len(controls)} observed controls, '
          f'{len(doubles)} double matches, {len(unmatched)} uncataloged pages')
    sys.exit(1 if errors or doubles else 0)


if __name__ == '__main__':
    main()
