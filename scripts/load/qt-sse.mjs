// Native fetch measures concurrent QT answer subscriptions; k6 core cannot keep an SSE stream open.
import { readFileSync } from 'node:fs';

const baseUrl = (process.env.BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '');
const sessionId = process.env.QT_SESSION_ID;
const tokenFile = process.env.TOKENS_FILE || './scripts/load/tokens.local.json';
const seconds = Number(process.env.SSE_SECONDS || 15);
const tokens = JSON.parse(readFileSync(tokenFile, 'utf8'));
const vus = Number(process.env.SSE_VUS || tokens.length);

if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(baseUrl)) throw new Error('Local-only SSE test');
if (!/^[0-9a-f-]{36}$/i.test(sessionId || '')) throw new Error('QT_SESSION_ID must be a session UUID');
if (!Number.isSafeInteger(seconds) || seconds < 1 || seconds > 120) throw new Error('SSE_SECONDS must be 1..120');
if (!Array.isArray(tokens) || !tokens.length || tokens.length > 200) throw new Error('Expected 1..200 access tokens');
if (!Number.isSafeInteger(vus) || vus < 1 || vus > tokens.length) throw new Error('SSE_VUS must be 1..token count');

const selectedTokens = tokens.slice(0, vus);
const controllers = selectedTokens.map(() => new AbortController());
const answerEventsBySubscriber = selectedTokens.map(() => 0);
let connected = 0;
let earlyClosures = 0;
let failures = 0;

async function subscribe(token, controller, index) {
  const response = await fetch(`${baseUrl}/api/v1/quiet-time/sessions/${sessionId}/answers/stream`, {
    headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
    signal: controller.signal,
  });
  if (!response.ok || !response.body) throw new Error(`SSE response HTTP ${response.status}`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let pending = '';
  let sawConnected = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        if (!controller.signal.aborted) earlyClosures += 1;
        break;
      }
      pending += decoder.decode(value, { stream: true });
      const frames = pending.split(/\r?\n\r?\n/);
      pending = frames.pop() || '';
      for (const frame of frames) {
        const event = frame.match(/^event:\s*(\S+)/m)?.[1];
        if (event === 'connected' && !sawConnected) {
          sawConnected = true;
          connected += 1;
        }
        if (event === 'answer-updated') answerEventsBySubscriber[index] += 1;
      }
      if (pending.length > 8192) pending = pending.slice(-2048);
    }
  } finally {
    reader.releaseLock();
  }
  if (!sawConnected) throw new Error('SSE connected event missing');
}

const start = performance.now();
const tasks = selectedTokens.map((token, index) => subscribe(token, controllers[index], index).catch((error) => {
  if (!controllers[index].signal.aborted) {
    failures += 1;
    console.error(`Subscription ${index + 1} failed: ${error.message}`);
  }
}));

try {
  // Give every subscription time to receive its initial connected event.
  const deadline = Date.now() + 10000;
  while (connected + failures < selectedTokens.length && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  const established = connected;
  await new Promise((resolve) => setTimeout(resolve, seconds * 1000));
  controllers.forEach((controller) => controller.abort());
  await Promise.all(tasks);
  console.log(JSON.stringify({ requested: selectedTokens.length, established, earlyClosures, failures, holdSeconds: seconds, answerEvents: answerEventsBySubscriber.reduce((sum, value) => sum + value, 0), subscribersWithUpdates: answerEventsBySubscriber.filter((value) => value > 0).length, elapsedMs: Math.round(performance.now() - start) }));
  if (established !== selectedTokens.length || earlyClosures || failures) process.exitCode = 1;
} finally {
  controllers.forEach((controller) => controller.abort());
}
