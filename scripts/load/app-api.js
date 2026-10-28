import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import exec from 'k6/execution';

// One bounded run per VU keeps the local fixture and AI quota predictable.
const mode = __ENV.MODE || 'reading';
const modes = ['reading', 'qt-read', 'qt-write', 'ai-stream'];
const baseUrl = (__ENV.BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '');
const vus = Number(__ENV.VUS || 1);
const iterations = Number(__ENV.ITERATIONS || 1);
// k6 resolves open() relative to this script, not the shell's working directory.
const tokensFile = __ENV.TOKENS_FILE || './tokens.local.json';
const tokens = JSON.parse(open(tokensFile));

if (!modes.includes(mode)) throw new Error(`MODE must be one of ${modes.join(', ')}`);
if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(baseUrl)) {
  throw new Error('Local-only load test: BASE_URL must use localhost or 127.0.0.1');
}
if (!Number.isSafeInteger(vus) || vus < 1 || vus > 200 || !Number.isSafeInteger(iterations) || iterations < 1 || iterations > 100) {
  throw new Error('VUS must be 1..200 and ITERATIONS must be 1..100');
}
if (!Array.isArray(tokens) || tokens.length < vus || tokens.some((token) => typeof token !== 'string' || !token.trim())) {
  throw new Error('TOKENS_FILE must contain at least one non-empty access token per VU');
}
if (mode.startsWith('qt-') && !/^[0-9a-f-]{36}$/i.test(__ENV.QT_SESSION_ID || '')) {
  throw new Error('QT_SESSION_ID must be a session UUID');
}
if (mode === 'ai-stream' && __ENV.AI_MOCK_CONFIRMED !== '1') {
  throw new Error('Set AI_MOCK_CONFIRMED=1 after verifying AI_MOCK_ENABLED=true in the local backend');
}

const failures = new Rate('app_api_failures');
export const options = {
  scenarios: {
    app: { executor: 'per-vu-iterations', vus, iterations, maxDuration: '3m' },
  },
  thresholds: { app_api_failures: ['rate<0.01'] },
};

function requestOptions(token, tags = {}) {
  return {
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json', 'Content-Type': 'application/json' },
    tags: { mode, ...tags },
    timeout: '70s',
  };
}

function assertResponse(response, expected, name, extra = true) {
  const passed = check(response, { [name]: (value) => value.status === expected && extra });
  failures.add(!passed);
}

export default function () {
  const token = tokens[exec.vu.idInTest - 1];
  const request = requestOptions(token);

  if (mode === 'reading') {
    const progress = http.get(`${baseUrl}/api/v1/reading/progress`, request);
    assertResponse(progress, 200, 'reading progress retrieved');
    const saved = http.put(`${baseUrl}/api/v1/reading/progress`, JSON.stringify({
      version: 'KOR1910', bookCode: 'GEN', chapter: 1, lastVerse: 1, completed: false, timezone: 'Asia/Seoul',
    }), request);
    assertResponse(saved, 200, 'reading progress saved');
  } else if (mode === 'qt-read') {
    const response = http.get(`${baseUrl}/api/v1/quiet-time/sessions/${__ENV.QT_SESSION_ID}/answers`, request);
    assertResponse(response, 200, 'QT answers retrieved');
  } else if (mode === 'qt-write') {
    const category = __ENV.QT_CATEGORY || 'OBSERVATION';
    const index = Number(__ENV.QT_QUESTION_INDEX || 0);
    if (!/^[A-Z_]+$/.test(category) || !Number.isSafeInteger(index) || index < 0) throw new Error('Invalid QT question target');
    const response = http.put(
      `${baseUrl}/api/v1/quiet-time/sessions/${__ENV.QT_SESSION_ID}/answers/${category}/${index}`,
      JSON.stringify({ content: '로컬 부하 테스트 답변입니다.' }), request,
    );
    assertResponse(response, 200, 'QT answer saved');
  } else {
    const response = http.post(
      `${baseUrl}/api/v1/ai/chat/stream?liveDraft=true`,
      JSON.stringify({ question: `요한복음 3장 16절에서 하나님의 사랑은 무엇을 뜻하나요? 테스트 ${exec.vu.idInTest}-${exec.vu.iterationInScenario}` }),
      { ...request, headers: { ...request.headers, Accept: 'text/event-stream' }, responseType: 'text' },
    );
    assertResponse(response, 200, 'AI stream completed', response.body?.includes('event:done') || response.body?.includes('event: done'));
  }

  sleep(1);
}
