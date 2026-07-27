import http from 'k6/http';
import { check, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';

export const businessErrors = new Rate('business_errors');
export const requestBlocked = new Trend('request_blocked', true);
export const requestConnecting = new Trend('request_connecting', true);
export const requestTlsHandshaking = new Trend('request_tls_handshaking', true);
export const requestSending = new Trend('request_sending', true);
export const requestWaiting = new Trend('request_waiting', true);
export const requestReceiving = new Trend('request_receiving', true);

export function baseUrl() {
  return (__ENV.PERF_BASE_URL || 'http://host.docker.internal:8080').replace(/\/$/, '');
}

export function jsonHeaders(token, extra = {}) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      ...extra,
    },
  };
}

export function parseResult(response, endpoint) {
  const metricTags = { endpoint };
  requestBlocked.add(response.timings.blocked, metricTags);
  requestConnecting.add(response.timings.connecting, metricTags);
  requestTlsHandshaking.add(response.timings.tls_handshaking, metricTags);
  requestSending.add(response.timings.sending, metricTags);
  requestWaiting.add(response.timings.waiting, metricTags);
  requestReceiving.add(response.timings.receiving, metricTags);

  let payload = null;
  try {
    payload = response.json();
  } catch (_) {
    // check() below records non-JSON responses without hiding the request failure.
  }
  const success = response.status === 200 && payload?.code === 200;
  businessErrors.add(!success, { endpoint });
  check(response, {
    [`${endpoint}: HTTP 200`]: (result) => result.status === 200,
    [`${endpoint}: business success`]: () => success,
  });
  return success ? payload.data : null;
}

export function login() {
  const username = __ENV.PERF_USERNAME;
  const password = __ENV.PERF_PASSWORD;
  if (!username || !password) {
    fail('PERF_USERNAME and PERF_PASSWORD are required');
  }
  return loginWithCredentials(username, password);
}

export function loginWithCredentials(username, password) {
  const response = http.post(
      `${baseUrl()}/api/auth/login`,
      JSON.stringify({ username, password }),
      { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'auth_login' } });
  const data = parseResult(response, 'auth_login');
  if (!data?.accessToken) {
    fail(`Login failed: HTTP ${response.status}`);
  }
  return data.accessToken;
}
