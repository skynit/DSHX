(() => {
  const host = document.querySelector('meta[name=dshx-host]').content;
  const key = location.hash.slice(1);
  if (window.parent === window || !/^[\da-f-]{36}$/.test(key)) return;
  let endpoint;
  navigator.serviceWorker?.addEventListener('message', event => {
    if (event.source !== navigator.serviceWorker.controller || event.data?.type !== 'request' || !event.ports[0] || !endpoint) return;
    endpoint.postMessage(event.data, [event.ports[0]]);
  });
  addEventListener('message', async event => {
    if (endpoint || event.source !== parent || event.origin !== host || event.data?.key !== key ||
        event.data.type !== 'bind' || event.ports.length !== 1) return;
    endpoint = event.ports[0];
    try {
      const path = new URL(event.data.path, location.origin);
      if (path.origin !== location.origin || /^\/__dshx(?:\/|$)/.test(path.pathname)) throw Error();
      await navigator.serviceWorker.register('/__dshx/sw.js', { scope: '/', updateViaCache: 'none' });
      if (!navigator.serviceWorker.controller) await new Promise(resolve => {
        navigator.serviceWorker.addEventListener('controllerchange', resolve, { once: true });
      });
      const page = document.createElement('iframe');
      page.title = '共享网页';
      page.src = path.href;
      document.body.replaceChildren(page);
      endpoint.postMessage({ type: 'ready' });
    } catch { endpoint.postMessage({ type: 'failed' }); }
  });
  parent.postMessage({ type: 'boot', key }, host);
})();
