import crypto from 'k6/crypto';
import http from 'k6/http';
import exec from 'k6/execution';
import { fail } from 'k6';
import { SharedArray } from 'k6/data';
import { baseUrl, businessErrors, login, parseResult } from './lib/api.js';

if (__ENV.PERF_ALLOW_WRITES !== 'true') {
  fail('Set PERF_ALLOW_WRITES=true only for an isolated test environment');
}

const fixturePath = __ENV.PERF_FIXTURE_FILE || './fixtures/sessions.example.json';
const sessions = new SharedArray('video upload sessions', () => JSON.parse(open(fixturePath)));
const chunkSize = Math.max(1024, Number(__ENV.PERF_VIDEO_CHUNK_BYTES || 262144));
const chunk = new Uint8Array(chunkSize);
const checksum = crypto.sha256(chunk.buffer, 'hex');
const virtualUsers = Math.max(1, Number(__ENV.PERF_VUS || 5));

export const options = {
  scenarios: {
    upload_chunks: {
      executor: 'shared-iterations',
      vus: Math.min(virtualUsers, sessions.length),
      iterations: sessions.length,
      maxDuration: __ENV.PERF_DURATION || '2m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    checks: ['rate>0.98'],
    business_errors: ['rate<0.02'],
    'http_req_duration{endpoint:video_chunk_upload}': ['p(95)<2000', 'p(99)<4000'],
  },
};

export function setup() {
  if (sessions.length === 0) {
    fail('At least one owned session fixture is required');
  }
  return { token: login() };
}

export default function (data) {
  const fixture = sessions[exec.scenario.iterationInTest];
  const chunkIndex = fixture.chunkIndex ?? exec.scenario.iterationInTest;
  const response = http.post(
      `${baseUrl()}/api/interviews/${fixture.sessionId}/videos/chunks`,
      {
        chunkIndex: String(chunkIndex),
        durationMs: String(fixture.durationMs || 10000),
        checksum,
        file: http.file(chunk.buffer, `chunk-${chunkIndex}.webm`, 'video/webm'),
      },
      {
        headers: { Authorization: `Bearer ${data.token}` },
        tags: { endpoint: 'video_chunk_upload' },
      });
  parseResult(response, 'video_chunk_upload');
}
