import http from 'k6/http';
import { group, sleep } from 'k6';
import { baseUrl, businessErrors, jsonHeaders, login, parseResult } from './lib/api.js';

const virtualUsers = Number(__ENV.PERF_VUS || 10);
const duration = __ENV.PERF_DURATION || '30s';

export const options = {
  scenarios: {
    authenticated_reads: {
      executor: 'constant-vus',
      vus: virtualUsers,
      duration,
      gracefulStop: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    business_errors: ['rate<0.01'],
    'http_req_duration{endpoint:auth_me}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{endpoint:interview_list}': ['p(95)<800', 'p(99)<1500'],
  },
};

export function setup() {
  return { token: login() };
}

export default function (data) {
  const requestOptions = jsonHeaders(data.token);

  group('authenticated read APIs', () => {
    const meResponse = http.get(`${baseUrl()}/api/auth/me`, {
      ...requestOptions,
      tags: { endpoint: 'auth_me' },
    });
    parseResult(meResponse, 'auth_me');

    const sessionsResponse = http.get(`${baseUrl()}/api/interview/sessions`, {
      ...requestOptions,
      tags: { endpoint: 'interview_list' },
    });
    parseResult(sessionsResponse, 'interview_list');
  });

  sleep(1);
}
