(() => {
  const host = document.querySelector('meta[name=dshx-host]').content;
  const key = location.hash.slice(1);
  if (window.parent === window || !/^[\da-f-]{36}$/.test(key)) return;
  const workerUrl = new URL('/__dshx/sw.js', location.origin).href;
  const workers = navigator.serviceWorker;
  let endpoint;
  workers?.addEventListener('message', event => {
    if (event.source !== workers.controller || event.source?.scriptURL !== workerUrl ||
        event.data?.type !== 'request' || !event.ports[0] || !endpoint) return;
    endpoint.postMessage(event.data, [event.ports[0]]);
  });

  function waitForController() {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { cleanup(); reject(Error('网页代理启动超时')); }, 8000);
      function cleanup() {
        clearTimeout(timeout);
        workers.removeEventListener('controllerchange', check);
      }
      function check() {
        if (workers.controller?.scriptURL !== workerUrl) return;
        cleanup();
        resolve();
      }
      workers.addEventListener('controllerchange', check);
      check();
    });
  }

  addEventListener('message', async event => {
    if (endpoint || event.source !== parent || event.origin !== host || event.data?.key !== key ||
        event.data.type !== 'bind' || event.ports.length !== 1) return;
    endpoint = event.ports[0];
    try {
      const path = new URL(event.data.path, location.origin);
      if (path.origin !== location.origin || /^\/__dshx(?:\/|$)/.test(path.pathname)) throw Error();
      if (!isSecureContext || !workers) throw Error('网页代理需要安全浏览器环境');
      await workers.register(workerUrl, { scope: '/', updateViaCache: 'none' });
      await waitForController();
      const page = document.createElement('iframe');
      page.title = '共享网页';
      page.src = path.href;
      document.body.replaceChildren(page);
      endpoint.postMessage({ type: 'ready' });
    } catch { endpoint.postMessage({ type: 'failed' }); }
  });
  parent.postMessage({ type: 'boot', key }, host);
})();
