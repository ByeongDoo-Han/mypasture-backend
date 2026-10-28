// Native fetch measures concurrent QT answer subscriptions; k6 core cannot keep an SSE stream open.
import { readFileSync } from 'node:fs';

const baseUrl = (process.env.BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '');
const sessionId = process.env.QT_SESSION_ID;
const tokenFile = process.env.TOKENS_FILE || './scripts/load/tokens.local.json';
const seconds = Number(process.env.SSE_SECONDS || 15);
const tokens = JSON.parse(readFileSync(tokenFile, 'utf8'));

if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(baseUrl)) throw new Error('Local-only SSE test');
if (!/^[0-9a-f-]{36}$/i.test(sessionId || '')) throw new Error('QT_SESSION_ID must be a session UUID');
if (!Number.isSafeInteger(seconds) || seconds < 1 || seconds > 120) throw new Error('SSE_SECONDS must be 1..120');
if (!Array.isArray(tokens) || !tokens.length || tokens.length > 200) throw new Error('Expected 1..200 access tokens');

const controllers = tokens.map(() => new AbortController());
let connected = 0;
let earlyClosures = 0;
let failures = 0;

async function subscribe(token, controller) {
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
      if (!sawConnected && /event:\s*connected\b/.test(pending)) {
        sawConnected = true;
        connected += 1;
      }
      if (pending.length > 8192) pending = pending.slice(-2048);
    }
  } finally {
    reader.releaseLock();
  }
  if (!sawConnected) throw new Error('SSE connected event missing');
}

const start = performance.now();
const tasks = tokens.map((token, index) => subscribe(token, controllers[index]).catch((error) => {
  if (!controllers[index].signal.aborted) {
    failures += 1;
    console.error(`Subscription ${index + 1} failed: ${error.message}`);
  }
}));

try {
  // Give every subscription time to receive its initial connected event.
  const deadline = Date.now() + 10000;
  while (connected + failures < tokens.length && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  const established = connected;
  await new Promise((resolve) => setTimeout(resolve, seconds * 1000));
  controllers.forEach((controller) => controller.abort());
  await Promise.all(tasks);
  console.log(JSON.stringify({ requested: tokens.length, established, earlyClosures, failures, holdSeconds: seconds, elapsedMs: Math.round(performance.now() - start) }));
  if (established !== tokens.length || earlyClosures || failures) process.exitCode = 1;
} finally {
  controllers.forEach((controller) => controller.abort());
}
