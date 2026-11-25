import { readFileSync } from 'node:fs';

export const webSignaling = '/api/v1/dshx/web/signaling';
const portal = new Map([
  ['/web/', ['portal.html', 'text/html']],
  ['/web/portal.js', ['portal.js', 'text/javascript']],
  ['/web/portal.css', ['portal.css', 'text/css']],
  ['/web/dshx-icon.svg', ['dshx-icon.svg', 'image/svg+xml']],
  ['/web/dshx-icon-dark.svg', ['dshx-icon-dark.svg', 'image/svg+xml']],
]);
const runtime = new Map([
  ['/__dshx/bridge', ['bridge.html', 'text/html']],
  ['/__dshx/bridge.js', ['bridge.js', 'text/javascript']],
  ['/__dshx/bridge.css', ['bridge.css', 'text/css']],
  ['/__dshx/sw.js', ['sw.js', 'text/javascript']],
  ['/__dshx/socket.js', ['socket.js', 'text/javascript']],
]);

export function createWebAccess(webOrigin, appDomain) {
  if (!webOrigin && !appDomain) return null;
  const origin = new URL(webOrigin);
  if (origin.protocol !== 'https:' || origin.origin !== webOrigin ||
      typeof appDomain !== 'string' || !/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{1,62}$/.test(appDomain) ||
      origin.hostname === appDomain || origin.hostname.endsWith(`.${appDomain}`)) {
    throw new Error('网页共享需要 HTTPS 平台来源和独立的应用域名。');
  }
  const port = origin.port ? `:${origin.port}` : '';
  const appSuffix = `.${appDomain}${port}`;
  return {
    visitorRequest: req => req.headers.host === origin.host && req.headers.origin === origin.origin,
    serve(req, res, url) {
      const host = req.headers.host;
      const app = host?.endsWith(appSuffix) && /^s-[a-f0-9]{32}$/.test(host.slice(0, -appSuffix.length));
      const isPortal = host === origin.host && url.pathname.startsWith('/web');
      if (!app && !isPortal) return false;
      const file = (app ? runtime : portal).get(url.pathname);
      if (!file || !['GET', 'HEAD'].includes(req.method)) {
        res.writeHead(404, { 'Cache-Control': 'no-store' }).end();
        return true;
      }
      const [name, type] = file;
      const headers = {
        'Content-Type': `${type}; charset=utf-8`, 'X-Content-Type-Options': 'nosniff',
        'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer',
        'Content-Security-Policy': app
          ? `default-src 'none'; script-src 'self'; style-src 'self'; worker-src 'self'; frame-src 'self'; frame-ancestors ${origin.origin}`
          : `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self' wss://${origin.host}; frame-src https://*.${appDomain}${port}; frame-ancestors 'none'; base-uri 'none'`,
        ...(name === 'portal.html' ? { 'X-DSHX-App-Domain': appDomain } : {}),
        ...(name === 'sw.js' ? { 'Service-Worker-Allowed': '/' } : {}),
      };
      let source = readFileSync(new URL(`./web/${name}`, import.meta.url));
      if (name.endsWith('.html')) source = source.toString()
        .replace('%%DSHX_ORIGIN%%', origin.origin).replace('%%DSHX_APP_DOMAIN%%', appDomain);
      res.writeHead(200, headers).end(req.method === 'HEAD' ? undefined : source);
      return true;
    },
  };
}
