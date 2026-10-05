import { spawn } from 'child_process';
import { mkdirSync, writeFileSync, readFileSync } from 'fs';

const screenshotDir = './docs/browser-evidence';
mkdirSync(screenshotDir, { recursive: true });

let initialUrl = 'http://localhost:5173/';
try {
  const token = readFileSync('data/launch-bootstrap', 'utf8').trim();
  if (token) initialUrl += '#bootstrap=' + token;
} catch (e) {}

// Start Chrome headless with remote debugging
const chrome = spawn('/usr/bin/google-chrome', [
  '--headless=new',
  '--remote-debugging-port=9333',
  '--remote-allow-origins=*',
  '--user-data-dir=/tmp/schedwise-chrome-audit',
  '--no-sandbox',
  '--disable-gpu',
  '--window-size=1440,1000',
  initialUrl
]);

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function getPageTarget() {
  for (let i = 0; i < 20; i++) {
    try {
      const res = await fetch('http://127.0.0.1:9333/json');
      const list = await res.json();
      const page = list.find(t => t.type === 'page' && t.url.includes('5173'));
      if (page && page.webSocketDebuggerUrl) return page;
    } catch (e) {}
    await sleep(300);
  }
  throw new Error('Could not find SchedWise tab in Chrome targets');
}

async function run() {
  await sleep(1500);
  const target = await getPageTarget();
  console.log(`Connected to page: ${target.title} (${target.webSocketDebuggerUrl})`);

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  let id = 1;
  const callbacks = new Map();
  const consoleErrors = [];
  const failedRequests = [];

  function send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const msgId = id++;
      callbacks.set(msgId, { resolve, reject });
      ws.send(JSON.stringify({ id: msgId, method, params }));
    });
  }

  await new Promise(r => ws.onopen = r);

  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    if (msg.id && callbacks.has(msg.id)) {
      const cb = callbacks.get(msg.id);
      callbacks.delete(msg.id);
      if (msg.error) cb.reject(msg.error);
      else cb.resolve(msg.result);
      return;
    }

    // Capture console errors
    if (msg.method === 'Runtime.consoleAPICalled') {
      if (msg.params.type === 'error' || msg.params.type === 'warning') {
        const text = msg.params.args.map(a => a.value ?? a.description ?? JSON.stringify(a)).join(' ');
        if (msg.params.type === 'error') consoleErrors.push(`[Console Error] ${text}`);
        else console.log(`[Console Warn] ${text}`);
      }
    }
    if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
      const text = msg.params.entry.text;
      if (text.includes('401') || text.includes('/api/session')) {
        console.log(`[Expected Handshake] ${text}`);
      } else {
        consoleErrors.push(`[Log Error] ${text}`);
      }
    }

    // Capture failed network requests
    if (msg.method === 'Network.responseReceived') {
      const status = msg.params.response.status;
      const url = msg.params.response.url;
      if (status >= 400 && !url.includes('/api/session')) {
        failedRequests.push(`[HTTP ${status}] ${url}`);
      }
    }
    if (msg.method === 'Network.loadingFailed') {
      if (!msg.params.canceled) {
        failedRequests.push(`[Network Failed] ${msg.params.errorText}`);
      }
    }
  };

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Network.enable');
  await send('Log.enable');

  // Let initial page load and bootstrap auth complete
  await sleep(2500);

  async function capture(name) {
    const res = await send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(`${screenshotDir}/${name}.png`, Buffer.from(res.data, 'base64'));
    console.log(`Saved screenshot: ${screenshotDir}/${name}.png`);
  }

  async function clickTab(hash, name) {
    console.log(`\nNavigating to ${name} (${hash})...`);
    await send('Runtime.evaluate', {
      expression: `window.location.hash = '${hash}';`
    });
    await sleep(2000);
    await capture(name);
  }

  // 1. Monitor Tab (Streaming Live)
  await capture('1-monitor');

  // Test Pause / Resume toggle
  console.log('\nToggling stream OFF (Pause)...');
  await send('Runtime.evaluate', {
    expression: `(() => { const btn = document.querySelector('.toggle-switch-btn'); if (btn) btn.click(); })()`
  });
  await sleep(1500);
  await capture('1-monitor-paused');

  console.log('Toggling stream back ON (Resume)...');
  await send('Runtime.evaluate', {
    expression: `(() => { const btn = document.querySelector('.toggle-switch-btn'); if (btn) btn.click(); })()`
  });
  await sleep(1500);

  // 2. Game Shield Tab
  await clickTab('/gameshield/overview', '2-gameshield-overview');
  await clickTab('/gameshield/apps', '2-gameshield-apps');
  await clickTab('/gameshield/recovery', '2-gameshield-recovery');

  // 3. Experiments Tabs
  await clickTab('/experiments/lab', '3-experiments-lab');
  await clickTab('/experiments/models', '3-experiments-models');
  await clickTab('/experiments/advisor', '3-experiments-advisor');
  await clickTab('/experiments/results', '3-experiments-results');

  // 4. System Tab
  await clickTab('/system', '4-system');
  await send('Runtime.evaluate', {
    expression: `window.scrollTo({ top: 900, behavior: 'instant' });`
  });
  await sleep(1000);
  await capture('4-system-lower');

  console.log('\n================ AUDIT REPORT ================');
  console.log(`Console Errors: ${consoleErrors.length}`);
  consoleErrors.forEach(e => console.log('  ' + e));

  console.log(`Failed Network Requests: ${failedRequests.length}`);
  failedRequests.forEach(r => console.log('  ' + r));
  console.log('==============================================\n');

  ws.close();
  chrome.kill();
  process.exit(consoleErrors.length > 0 ? 1 : 0);
}

run().catch(err => {
  console.error('Audit failed:', err);
  chrome.kill();
  process.exit(1);
});
