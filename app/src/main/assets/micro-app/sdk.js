/* DSHX 应用接口 v1；由宿主先于应用脚本注入。 */
(() => {
  'use strict';
  if (window.dshx) return;
  let port, sequence = 0, connected = false;
  const pending = new Map(), listeners = new Set();
  let resolveReady;
  const ready = new Promise(resolve => { resolveReady = resolve; });
  window.addEventListener('message', event => {
    if (connected || event.data !== 'dshx-connect' || !event.ports?.[0]) return;
    connected = true; port = event.ports[0];
    port.onmessage = event => {
      let data;
      try { data = JSON.parse(event.data); } catch { return; }
      if (data.event === 'screen') {
        for (const listener of listeners) Promise.resolve().then(() => listener(data.data)).catch(console.error);
        return;
      }
      const call = pending.get(data.id);
      if (!call) return;
      pending.delete(data.id); clearTimeout(call.timeout);
      if (data.ok) call.resolve(data.result); else call.reject(new Error(data.error || '调用失败。'));
    };
    port.start?.(); resolveReady();
  });
  function call(method, args = {}) {
    if (pending.size >= 16) throw new Error('待处理请求过多。');
    const id = String(++sequence);
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { pending.delete(id); reject(new Error('调用超时，实际结果需核实，不要自动重试动作。')); }, 10000);
      pending.set(id, { resolve, reject, timeout });
      ready.then(() => {
        if (!pending.has(id)) return; // 建链前已超时的请求不得补发。
        try { port.postMessage(JSON.stringify({ id, method, args })); }
        catch (error) { pending.delete(id); clearTimeout(timeout); reject(error); }
      });
    });
  }
  window.dshx = Object.freeze({
    ready,
    info: () => call('info'),
    storage: Object.freeze({
      get: key => call('storage.get', { key }).then(row => row.value),
      set: (key, value) => call('storage.set', { key, value }),
      remove: key => call('storage.remove', { key }),
    }),
    screen: Object.freeze({
      observe: () => call('screen.observe'),
      click: (snapshot, node) => call('screen.click', { snapshot, node }),
      onChange: listener => { if (typeof listener !== 'function') throw new TypeError('需要回调函数。'); listeners.add(listener); return () => listeners.delete(listener); },
    }),
  });
})();
