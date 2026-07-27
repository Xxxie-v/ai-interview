import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import http from 'k6/http';
import exec from 'k6/execution';
import {check, fail, sleep} from 'k6';
import { SharedArray } from 'k6/data';
import {Trend} from 'k6/metrics';
import {
  baseUrl,
  jsonHeaders,
  loginWithCredentials,
  parseResult,
} from './lib/api.js';

if (__ENV.PERF_ALLOW_WRITES !== 'true') {
  fail('Set PERF_ALLOW_WRITES=true only for an isolated test environment');
}

const fixturePath = __ENV.PERF_FIXTURE_FILE || './fixtures/multi-user-sessions.local.json';
const candidates = new SharedArray(
    'independent candidate sessions',
    () => JSON.parse(open(fixturePath)));
const virtualUsers = Math.max(1, Number(__ENV.PERF_VUS || candidates.length));
const runId = __ENV.PERF_RUN_ID || String(Date.now());
const includeMedia = __ENV.PERF_INCLUDE_MEDIA === 'true';
const completeAfterAnswers = __ENV.PERF_COMPLETE_INTERVIEW !== 'false';
const waitForReport = __ENV.PERF_WAIT_FOR_REPORT !== 'false';
const reportTimeoutSeconds = Math.max(
    10,
    Number(__ENV.PERF_REPORT_TIMEOUT_SECONDS || 120));
const answersPerCandidate = Math.max(
    1,
    Number(__ENV.PERF_ANSWERS_PER_CANDIDATE || 2));
const thinkTimeMinSeconds = Math.max(
    0,
    Number(__ENV.PERF_THINK_TIME_MIN_SECONDS || 0));
const thinkTimeMaxSeconds = Math.max(
    thinkTimeMinSeconds,
    Number(__ENV.PERF_THINK_TIME_MAX_SECONDS || thinkTimeMinSeconds));
const videoChunkSize = Math.max(
    1024,
    Number(__ENV.PERF_VIDEO_CHUNK_BYTES || 1048576));
const videoFixturePath = __ENV.PERF_VIDEO_FIXTURE_FILE || '';
const videoChunk = videoFixturePath
    ? open(videoFixturePath, 'b')
    : new Uint8Array(videoChunkSize).buffer;
const videoChecksum = crypto.sha256(videoChunk, 'hex');
const screenshot = encoding.b64decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=');
const interviewStartDuration = new Trend('interview_start_duration', true);
const dynamicFollowUpDuration = new Trend('dynamic_followup_duration', true);

export const options = {
  scenarios: {
    submit_answers: {
      executor: 'shared-iterations',
      vus: Math.min(virtualUsers, candidates.length),
      iterations: candidates.length,
      maxDuration: __ENV.PERF_DURATION || '2m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    checks: ['rate>0.95'],
    business_errors: ['rate<0.05'],
    interview_start_duration: ['p(95)<2000'],
    dynamic_followup_duration: ['p(95)<7000'],
    'http_req_duration{endpoint:answer_submit}': ['p(95)<7000', 'p(99)<10000'],
    'http_req_duration{endpoint:video_chunk_upload}': ['p(95)<10000', 'p(99)<20000'],
    'http_req_duration{endpoint:screenshot_upload}': ['p(95)<10000', 'p(99)<20000'],
    'http_req_duration{endpoint:interview_complete}': ['p(95)<3000', 'p(99)<5000'],
  },
};

export function setup() {
  if (candidates.length === 0) {
    fail('At least one candidate fixture is required');
  }
  const tokens = {};
  for (const candidate of candidates) {
    if (!candidate.username || !candidate.password || !candidate.sessionId) {
      fail('Each fixture requires username, password, and sessionId');
    }
    tokens[candidate.username] = loginWithCredentials(
        candidate.username,
        candidate.password);
  }
  return { tokens };
}

export default function (data) {
  const iteration = exec.scenario.iterationInTest;
  const candidate = candidates[iteration];
  const token = data.tokens[candidate.username];
  const headers = jsonHeaders(token);
  const interviewStartAt = Date.now();

  const deviceResponse = http.post(
      `${baseUrl()}/api/interviews/${candidate.sessionId}/device-check`,
      JSON.stringify({cameraReady: true, microphoneReady: true}),
      {...headers, tags: {endpoint: 'device_check'}});
  parseResult(deviceResponse, 'device_check');
  if (!isBusinessSuccess(deviceResponse)) {
    logFailure(candidate.sessionId, 'device_check', deviceResponse);
    return;
  }

  for (let answerNumber = 0; answerNumber < answersPerCandidate; answerNumber++) {
    const questionResponse = http.get(
        `${baseUrl()}/api/interview/sessions/${candidate.sessionId}/question`,
        {...headers, tags: {endpoint: 'question_current'}});
    const questionData = parseResult(questionResponse, 'question_current');
    const questionIndex = questionData?.question?.questionIndex;
    if (questionIndex === undefined || questionIndex === null) {
      logFailure(candidate.sessionId, 'question_current', questionResponse);
      return;
    }
    if (answerNumber === 0) {
      interviewStartDuration.add(Date.now() - interviewStartAt);
    }
    if (thinkTimeMaxSeconds > 0) {
      sleep(thinkTimeMinSeconds
          + Math.random() * (thinkTimeMaxSeconds - thinkTimeMinSeconds));
    }

    const submitOptions = jsonHeaders(token, {
      'Idempotency-Key': [
        'k6', runId, iteration, answerNumber, candidate.sessionId,
      ].join('-'),
    });
    submitOptions.tags = {endpoint: 'answer_submit'};
    const answerBody = JSON.stringify({
      questionIndex,
      answer: candidate.answer
          || `我会先确认目标和约束，再通过监控与实验验证方案。这是第${answerNumber + 1}次回答。`,
    });

    let submitResponse;
    if (includeMedia && answerNumber === 0) {
      const responses = http.batch([
        {
          method: 'POST',
          url: `${baseUrl()}/api/interview/sessions/${candidate.sessionId}/answers`,
          body: answerBody,
          params: submitOptions,
        },
        {
          method: 'POST',
          url: `${baseUrl()}/api/interviews/${candidate.sessionId}/videos/chunks`,
          body: {
            chunkIndex: '0',
            durationMs: '25000',
            checksum: videoChecksum,
            file: http.file(videoChunk, 'chunk-0.webm', 'video/webm'),
          },
          params: {
            headers: {Authorization: `Bearer ${token}`},
            tags: {endpoint: 'video_chunk_upload'},
          },
        },
        {
          method: 'POST',
          url: `${baseUrl()}/api/interviews/${candidate.sessionId}/proctor/events`,
          body: {
            clientEventId: eventId(iteration, answerNumber),
            eventType: 'SCREEN_CAPTURED',
            videoOffsetMs: '10000',
            metadata: JSON.stringify({source: 'k6', runId}),
            evidence: http.file(screenshot, 'screen-evidence.png', 'image/png'),
          },
          params: {
            headers: {Authorization: `Bearer ${token}`},
            tags: {endpoint: 'screenshot_upload'},
          },
        },
      ]);
      submitResponse = responses[0];
      const videoData = parseResult(responses[1], 'video_chunk_upload');
      parseResult(responses[2], 'screenshot_upload');
      if (videoData !== null) {
        const videoCompleteResponse = http.post(
            `${baseUrl()}/api/interviews/${candidate.sessionId}/videos/complete`,
            null,
            {
              headers: {Authorization: `Bearer ${token}`},
              tags: {endpoint: 'video_complete'},
            });
        parseResult(videoCompleteResponse, 'video_complete');
      }
    } else {
      submitResponse = http.post(
          `${baseUrl()}/api/interview/sessions/${candidate.sessionId}/answers`,
          answerBody,
          submitOptions);
    }

    const submitData = parseResult(submitResponse, 'answer_submit');
    if (submitData === null) {
      logFailure(candidate.sessionId, 'answer_submit', submitResponse);
      return;
    }
    if (submitData.nextQuestion?.isFollowUp === true) {
      dynamicFollowUpDuration.add(submitResponse.timings.duration);
    }
    if (!submitData.hasNextQuestion) break;
  }

  if (!completeAfterAnswers) return;

  const completeResponse = http.post(
      `${baseUrl()}/api/interview/sessions/${candidate.sessionId}/complete`,
      null,
      {...headers, tags: {endpoint: 'interview_complete'}});
  if (!isBusinessSuccess(completeResponse)) {
    parseResult(completeResponse, 'interview_complete');
    logFailure(candidate.sessionId, 'interview_complete', completeResponse);
  } else {
    parseResult(completeResponse, 'interview_complete');
  }

  if (!waitForReport) return;

  let reportReady = false;
  const reportDeadline = Date.now() + reportTimeoutSeconds * 1000;
  while (Date.now() < reportDeadline) {
    const sessionResponse = http.get(
        `${baseUrl()}/api/interview/sessions/${candidate.sessionId}`,
        {...headers, tags: {endpoint: 'report_status'}});
    const sessionData = parseResult(sessionResponse, 'report_status');
    if (sessionData?.evaluateStatus === 'COMPLETED') {
      reportReady = true;
      break;
    }
    if (sessionData?.evaluateStatus === 'FAILED') {
      logFailure(candidate.sessionId, 'report_status', sessionResponse);
      break;
    }
    sleep(2);
  }
  check(reportReady, {'report generated': ready => ready === true});
}

function eventId(iteration, answerNumber) {
  const sequence = iteration * answersPerCandidate + answerNumber + 1;
  return `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`;
}

function isBusinessSuccess(response) {
  try {
    return response.status === 200 && response.json()?.code === 200;
  } catch (_) {
    return false;
  }
}

function logFailure(sessionId, endpoint, response) {
  const body = String(response.body || '').replace(/\s+/g, ' ').slice(0, 300);
  console.error(
      `[${endpoint}] session=${sessionId} status=${response.status} body=${body}`);
}
