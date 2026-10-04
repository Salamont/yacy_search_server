#!/usr/bin/env python3
"""Read-only crawl report API checks against report-live-smoke.py fixtures; agent creation is disposable only. GPL-2.0-or-later."""
import sys
sys.dont_write_bytecode = True
import json
import unittest
import urllib.parse
from test_agent_api import BASE, admin_opener, http, agent_call, create_agent, error_code

JOB = '5b0f4c1e-7a2d-4c6b-9f1e-2d3c4b5a6f70'
ORPHAN = '8e1d2c3b-4a5f-4e6d-8c7b-9a0f1e2d3c4b'


class ReportApi(unittest.TestCase):
    def get(self, path, query=None, method='GET', opener=True):
        status, _, text = http(method, BASE + '/scoutro/api/v1/reports/' + path + ('?' + urllib.parse.urlencode(query) if query else ''),
                               opener=admin_opener() if opener else None)
        try:
            return status, json.loads(text)
        except ValueError:
            return status, None

    def hosts(self, query):
        status, data = self.get('collections/visible/hosts', query)
        self.assertEqual(status, 200, data)
        return data, [item['host'] for item in data['items']]

    def test_jobs(self):
        status, data = self.get('jobs'); self.assertEqual(status, 200)
        jobs = {job['id']: job for job in data['jobs']}
        self.assertEqual(set(jobs), {JOB, ORPHAN})
        self.assertTrue(jobs[JOB]['known']); self.assertEqual(jobs[JOB]['stale_after_days'], 30)
        self.assertEqual(jobs[JOB]['collections'], ['visible']); self.assertEqual(jobs[JOB]['hosts'], 34)
        self.assertFalse(jobs[ORPHAN]['known']); self.assertIsNone(jobs[ORPHAN]['name']); self.assertEqual(jobs[ORPHAN]['collections'], ['secret'])
        status, job = self.get('jobs/' + JOB); self.assertEqual(status, 200)
        self.assertEqual(job['table']['stale'], 1); self.assertEqual(job['table']['latest_attempt_precheck'], 1)
        types = [m['type'] for line in job['rollups'] for m in line.get('markers', [])]
        self.assertEqual(sorted(types), ['job_changed', 'version_changed'])
        self.assertGreaterEqual(len(job['rollups']), 8)
        status, short = self.get('jobs/' + JOB, {'from': job['to'], 'to': job['to']}); self.assertEqual(status, 200)
        self.assertLessEqual(len(short['rollups']), 1)
        for query in [{'from': '2026-1-1'}, {'from': '2026-10-04', 'to': '2026-01-01'}, {'from': '2020-01-01', 'to': '2026-01-01'}, {'collection': 'visible'}]:
            self.assertEqual(self.get('jobs/' + JOB, query)[0], 400, query)
        self.assertEqual(self.get('jobs/' + JOB.upper())[0], 400)

    def test_collection_and_hosts(self):
        status, data = self.get('collections/visible'); self.assertEqual(status, 200)
        t, index = data['table'], data['index']
        self.assertEqual([t[k] for k in ['hosts', 'crawled', 'stale', 'precheck_only', 'coverage_partial']], [34, 33, 1, 1, 1])
        self.assertEqual(t['outcomes'], {'indexed': 31, 'partial': 1, 'not_indexed': 1})
        self.assertEqual(data['index_source'], 'live')
        self.assertEqual([index[k] for k in ['documents', 'ok', 'hosts']], [9, 8, 2])
        self.assertEqual(index['canonical'], {'with': 5, 'without': 3, 'self': 4, 'elsewhere': 1})
        self.assertEqual(index['titles'], {'with': 7, 'missing': 1})          # no cross-host title groups
        self.assertEqual(index['descriptions'], {'with': 4, 'missing': 4})
        self.assertEqual(index['unavailable'], [])
        self.assertEqual(index['duplicates']['exact_groups'], 1)
        self.assertIn('text/html<img src=x onerror=alert(1)>', [b['value'] for b in index['content_type']])
        data, names = self.hosts({'filter': 'stale'}); self.assertEqual(names, ['b.example']); self.assertEqual(data['items'][0]['age_days'], 40)
        self.assertEqual(data['items'][0]['scheme'], 'http')
        self.assertEqual(self.hosts({'filter': 'precheck'})[1], ['c.example'])
        self.assertEqual(self.hosts({'filter': 'not_indexed'})[1], ['d.example'])
        self.assertEqual(self.hosts({'filter': 'coverage_partial'})[1], ['d.example'])
        data, names = self.hosts({'limit': 25}); self.assertEqual(data['total'], 34); self.assertEqual(len(names), 25)
        self.assertEqual(names[:4], ['a.example', 'b.example', 'c.example', 'd.example'])
        self.assertEqual(len(self.hosts({'limit': 25, 'offset': 25})[1]), 9)
        self.assertEqual(self.get('collections/secret')[1]['table']['hosts'], 1)

    def test_host(self):
        status, data = self.get('hosts/A.example', {'collection': 'visible'}); self.assertEqual(status, 200)
        self.assertEqual(data['host'], 'a.example'); self.assertEqual(data['status'], 'found')
        current = data['row']['current']
        self.assertEqual(current['labels']['outcome'], 'partial'); self.assertEqual(current['counters']['pages_ok'], 4)
        self.assertFalse(current['stale']); self.assertEqual(data['row']['previous']['labels']['outcome'], 'indexed')
        self.assertEqual(data['index']['documents'], 8); self.assertEqual(data['index']['not_reloaded'], 0)
        self.assertEqual(data['row']['current']['labels']['scheme'], 'https')
        titles = data['index']['titles']
        self.assertEqual([titles[k] for k in ['with', 'missing', 'same_groups', 'same_urls']], [6, 1, 1, 2])
        self.assertEqual(data['index']['descriptions']['same_urls'], 2)
        self.assertEqual(data['index']['canonical'], {'with': 4, 'without': 3, 'self': 3, 'elsewhere': 1})
        self.assertEqual([(d['directory'], d['documents'], d['ok']) for d in data['directories']['items']], [('/', 6, 5), ('/docs/', 2, 2)])
        self.assertFalse(data['directories']['truncated'])
        self.assertEqual(data['referring_hosts']['items'], [{'host': 'blog.example', 'links': 2}, {'host': 'news.example', 'links': 1}])
        self.assertNotIn('referring_hosts_scope', data)
        status, pre = self.get('hosts/c.example', {'collection': 'visible'})
        self.assertIsNone(pre['row']['current']); self.assertEqual(pre['row']['precheck']['result'], 'robots'); self.assertEqual(pre['row']['latest_attempt'], 'precheck')
        self.assertEqual(self.get('hosts/absent.example', {'collection': 'visible'})[1]['status'], 'absent')
        self.assertEqual(self.get('hosts/a.example', {'collection': 'secret'})[1]['status'], 'absent')

    def test_controlled_requests_and_auth(self):
        for path, query in [('collections/visible', {'q': '*:*'}), ('collections/visible/hosts', {'filter': 'random()'}),
                            ('collections/visible/hosts', {'limit': 101}), ('collections/visible/hosts', {'offset': 10001}),
                            ('collections/bad%20name', None), ('hosts/a.example', None), ('hosts/127.0.0.1', {'collection': 'visible'}),
                            ('hosts/a.example', {'collection': 'visible', 'fq': 'x'}), ('jobs/not-a-job', None)]:
            status, data = self.get(path, query); self.assertEqual(status, 400, path); self.assertEqual(error_code(data), 'invalid_request')
        self.assertEqual(self.get('collections/visible/pages')[0], 404)
        self.assertEqual(self.get('status')[0], 404)
        self.assertEqual(self.get('jobs', opener=False)[0], 401)
        self.assertEqual(self.get('jobs', method='POST')[0], 405)
        self.assertEqual(self.get('collections/visible', method='DELETE')[0], 405)

    def test_explicit_agent_grant_and_scope(self):
        token, _, _ = create_agent('Report disposable', collections=('visible',), extra_actions=('report.read',))
        status, data, _ = agent_call(token, 'GET', '/reports/jobs'); self.assertEqual(status, 200, data)
        self.assertEqual([job['id'] for job in data['jobs']], [JOB])
        self.assertEqual(agent_call(token, 'GET', '/reports/jobs/' + JOB)[0], 200)
        self.assertEqual(agent_call(token, 'GET', '/reports/jobs/' + ORPHAN)[0], 404)
        status, data, _ = agent_call(token, 'GET', '/reports/collections/visible'); self.assertEqual(status, 200); self.assertEqual(data['table']['hosts'], 34)
        status, data, _ = agent_call(token, 'GET', '/reports/collections/visible/hosts', {'filter': 'stale'})
        self.assertEqual([item['host'] for item in data['items']], ['b.example'])
        status, data, _ = agent_call(token, 'GET', '/reports/collections/secret'); self.assertEqual((status, error_code(data)), (403, 'collection_not_in_scope'))
        self.assertEqual(agent_call(token, 'GET', '/reports/collections/secret/hosts')[0], 403)
        self.assertEqual(agent_call(token, 'GET', '/reports/hosts/e.example', {'collection': 'secret'})[0], 403)
        status, scoped, _ = agent_call(token, 'GET', '/reports/hosts/a.example', {'collection': 'visible'})
        self.assertEqual(scoped['status'], 'found'); self.assertEqual(scoped['directories']['directories'], 2)
        self.assertIsNone(scoped['referring_hosts']); self.assertEqual(scoped['referring_hosts_scope'], 'complete_index_required')
        self.assertEqual(agent_call(token, 'POST', '/reports/jobs', body={})[0], 405)
        self.assertEqual(agent_call(token, 'GET', '/reports/collections/visible/pages')[0], 404)
        status, data, _ = agent_call(token, 'GET', '/capabilities')
        self.assertIn('report.read', [a['name'] for a in data['actions']])
        whole, _, _ = create_agent('Report complete disposable', collections=(), extra_actions=('report.read',), all_collections=True)
        status, data, _ = agent_call(whole, 'GET', '/reports/jobs'); self.assertEqual(sorted(job['id'] for job in data['jobs']), sorted([JOB, ORPHAN]))
        self.assertEqual(agent_call(whole, 'GET', '/reports/collections/secret')[0], 200)
        status, data, _ = agent_call(whole, 'GET', '/reports/hosts/a.example', {'collection': 'visible'})
        self.assertEqual(data['referring_hosts']['hosts'], 2)
        plain, _, _ = create_agent('No report disposable', collections=('visible',))
        status, data, _ = agent_call(plain, 'GET', '/reports/jobs'); self.assertEqual((status, error_code(data)), (403, 'action_not_granted'))
        self.assertEqual(agent_call(None, 'GET', '/reports/jobs')[0], 401)


if __name__ == '__main__':
    unittest.main()
