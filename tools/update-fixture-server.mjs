// Usage: node tools/update-fixture-server.mjs <isolated-fixture-directory>
// Loopback only; no directory listing or arbitrary file paths. Stop with Ctrl+C.
import http from 'node:http';
import { createReadStream, statSync, realpathSync } from 'node:fs';
import { resolve, dirname } from 'node:path';

if (process.argv.length !== 3) throw new Error('Expected isolated fixture directory');
const directory = realpathSync(process.argv[2]);
const routes = new Map([
  ['/candidate.apk', 'candidate.apk'], ['/baseline.apk', 'baseline.apk'],
  ['/interrupted.apk', 'candidate.apk'], ['/slow.apk', 'candidate.apk'],
]);
let interrupted = false;
const server = http.createServer((request, response) => {
  const name = routes.get(request.url);
  if (!name || !['GET', 'HEAD'].includes(request.method)) {
    response.writeHead(404).end();
    return;
  }
  try {
    const file = realpathSync(resolve(directory, name));
    if (dirname(file) !== directory) throw new Error('Fixture escapes directory');
    const size = statSync(file).size;
    const range = request.headers.range?.match(/^bytes=(\d+)-$/);
    if (request.headers.range && !range) { response.writeHead(416).end(); return; }
    const start = range ? Number(range[1]) : 0;
    if (!Number.isSafeInteger(start) || start >= size) { response.writeHead(416).end(); return; }
    response.writeHead(start ? 206 : 200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': size - start, 'Accept-Ranges': 'bytes',
      ETag: '"omn-synthetic-candidate"',
      ...(start ? { 'Content-Range': `bytes ${start}-${size - 1}/${size}` } : {}),
    });
    console.log(JSON.stringify({ route: request.url, start, size }));
    if (request.method === 'HEAD') { response.end(); return; }
    const stream = createReadStream(file, { start, highWaterMark: 64 * 1024 });
    const cut = request.url === '/interrupted.apk' && !interrupted;
    if (cut) interrupted = true;
    let sent = 0;
    const timer = setTimeout(() => { stream.destroy(); response.destroy(); }, 120_000);
    response.on('close', () => { clearTimeout(timer); stream.destroy(); });
    stream.on('error', () => response.destroy());
    stream.on('end', () => response.end());
    stream.on('data', chunk => {
      stream.pause();
      response.write(chunk);
      sent += chunk.length;
      if (cut && sent >= 512 * 1024) { response.destroy(); return; }
      setTimeout(() => stream.resume(), request.url === '/slow.apk' ? 150 : 5);
    });
  } catch {
    if (!response.headersSent) response.writeHead(500);
    response.end();
  }
});
server.listen(18765, '127.0.0.1', () => console.log('OMN fixture server on loopback port 18765'));
server.on('error', error => { console.error(error.message); process.exitCode = 1; });
