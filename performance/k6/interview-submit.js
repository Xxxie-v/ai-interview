import http from 'k6/http';
import exec from 'k6/execution';
import { fail } from 'k6';
import { SharedArray } from 'k6/data';
import { baseUrl, businessErrors, jsonHeaders, login, parseResult } from './lib/api.js';

if (__ENV.PERF_ALLOW_WRITES !== 'true') {
  fail('Set PERF_ALLOW_WRITES=true only for an isolated test environment');
}

const fixturePath = __ENV.PERF_FIXTURE_FILE || './fixtures/sessions.example.json';
const sessions = new SharedArray('prepared interview sessions', () => JSON.parse(open(fixturePath)));
const virtualUsers = Math.max(1, Number(__ENV.PERF_VUS || 5));
const runId = __ENV.PERF_RUN_ID || String(Date.now());

export const options = {
  scenarios: {
    submit_answers: {
      executor: 'shared-iterations',
      vus: Math.min(virtualUsers, sessions.length),
      iterations: sessions.length,
      maxDuration: __ENV.PERF_DURATION || '2m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    checks: ['rate>0.95'],
    business_errors: ['rate<0.05'],
    'http_req_duration{endpoint:answer_submit}': ['p(95)<7000', 'p(99)<10000'],
  },
};

export function setup() {
  if (sessions.length === 0) {
    fail('At least one prepared session fixture is required');
  }
  return { token: login() };
}

export default function (data) {
  const fixture = sessions[exec.scenario.iterationInTest];
  const sessionId = fixture.sessionId;
  const headers = jsonHeaders(data.token);

  const deviceResponse = http.post(
      `${baseUrl()}/api/interviews/${sessionId}/device-check`,
      JSON.stringify({ cameraReady: true, microphoneReady: true }),
      { ...headers, tags: { endpoint: 'device_check' } });
  parseResult(deviceResponse, 'device_check');

  const questionResponse = http.get(
      `${baseUrl()}/api/interview/sessions/${sessionId}/question`,
      { ...headers, tags: { endpoint: 'question_current' } });
  const questionData = parseResult(questionResponse, 'question_current');
  const questionIndex = fixture.questionIndex ?? questionData?.question?.questionIndex;
  if (questionIndex === undefined || questionIndex === null) {
    fail(`Session ${sessionId} does not have an answerable current question`);
  }

  const submitOptions = jsonHeaders(data.token, {
    'Idempotency-Key': `k6-${runId}-${exec.scenario.iterationInTest}-${sessionId}`,
  });
  submitOptions.tags = { endpoint: 'answer_submit' };
  const submitResponse = http.post(
      `${baseUrl()}/api/interview/sessions/${sessionId}/answers`,
      JSON.stringify({
        questionIndex,
        answer: fixture.answer || '我会先明确业务指标，再通过监控数据验证方案效果和边界。',
      }),
      submitOptions);
  parseResult(submitResponse, 'answer_submit');
}
