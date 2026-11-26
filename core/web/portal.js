import { Peer, shareItems, uuid } from './peer.js';

const controls = document.querySelector('main');
const controlStyles = document.querySelector('#control-styles').content;
const directory = document.querySelector('#directory');
const directoryTitle = document.querySelector('#directory-title');
const directoryStatus = document.querySelector('#directory-status');
const endpoints = document.querySelector('#endpoints');
const refresh = document.querySelector('#refresh');
const endpoint = document.querySelector('#endpoint');
const connectionStatus = document.querySelector('#connection-status');
const back = document.querySelector('#back');
const shares = document.querySelector('#shares');
const itemRow = document.querySelector('#item-row');
let active;

function row(parent, text, label, action, device) {
  const line = itemRow.content.firstElementChild.cloneNode(true);
  line.querySelector('.item-name').textContent = text;
  const button = line.querySelector('button');
  button.querySelector('span').textContent = label;
  if (device) {
    const { online, endpointId } = device;
    const id = line.querySelector('.item-id');
    if (text !== endpointId && uuid.test(endpointId)) {
      id.hidden = false;
      const summary = id.querySelector('summary');
      summary.textContent = `${endpointId.slice(0, 8)}…${endpointId.slice(-4)}`;
      summary.title = endpointId;
      id.querySelector('span').textContent = endpointId;
    }
    line.querySelector('.item-icon use').setAttribute('href', '#device-icon');
    const badge = line.querySelector('.availability');
    badge.hidden = false;
    badge.textContent = online ? '在线' : '离线';
    badge.classList.add(online ? 'badge-success' : 'badge-neutral');
    button.disabled = !online;
  }
  button.addEventListener('click', action);
  parent.append(line);
}

async function listEndpoints(cursor, more) {
  if (refresh.disabled) return;
  refresh.disabled = true;
  endpoints.setAttribute('aria-busy', 'true');
  const moreButton = more?.querySelector('button');
  if (moreButton) moreButton.disabled = true;
  const url = new URL('/api/v1/dshx/chat/endpoints', location.origin);
  if (cursor) url.searchParams.set('cursor', cursor);
  directoryStatus.textContent = '正在读取端点…';
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw Error();
    const page = await response.json();
    const open = page.items.filter(item => item.capabilities?.includes('web-share'));
    if (cursor) more.remove();
    else endpoints.replaceChildren();
    for (const item of open) row(endpoints, item.displayName || item.endpointId, '查看网页', () => {
      history.pushState(null, '', `/web/?${new URLSearchParams({ endpoint: item.endpointId, list: '1' })}`);
      showRoute();
    }, item);
    if (page.nextCursor) {
      const footer = document.createElement('div');
      footer.className = 'more';
      const button = document.createElement('button');
      button.className = 'btn btn-ghost btn-sm';
      button.textContent = '加载更多';
      button.addEventListener('click', () => void listEndpoints(page.nextCursor, footer));
      footer.append(button);
      endpoints.append(footer);
    }
    directoryStatus.textContent = endpoints.childElementCount ? '' : '暂无公开网页';
  } catch {
    directoryStatus.textContent = '无法读取公开端点';
  } finally {
    endpoints.removeAttribute('aria-busy');
    refresh.disabled = false;
    if (moreButton) moreButton.disabled = false;
  }
}

function showRoute() {
  active?.close();
  active = null;
  shares.replaceChildren();
  shares.hidden = true;
  const query = new URLSearchParams(location.search);
  const hasEndpoint = query.has('endpoint');
  directory.hidden = hasEndpoint;
  endpoint.hidden = !hasEndpoint;
  if (controlStyles.childElementCount) document.head.append(controlStyles);
  controls.hidden = false;
  const endpointId = query.get('endpoint');
  if (uuid.test(endpointId)) connect(endpointId);
  else if (hasEndpoint) setConnectionStatus('端点地址无效', 'error');
  else void listEndpoints();
}

function setConnectionStatus(message, tone = 'neutral') {
  connectionStatus.dataset.tone = tone;
  connectionStatus.textContent = message;
}

function connect(endpointId) {
  setConnectionStatus('正在建立直连…');
  const connection = new Peer(endpointId, onFrame, message => {
    if (active !== connection) return;
    shares.hidden = false;
    setConnectionStatus(message, 'error');
  }, message => { if (active === connection) setConnectionStatus(message); });
  active = connection;
  function onFrame(frame) {
    if (active !== connection) return;
    const items = shareItems(frame);
    if (!items) return;
    for (const item of items) row(shares, item.name, '打开网页', () => {
      location.assign(`/web/?${new URLSearchParams({ endpoint: endpointId, share: item.id })}`);
    });
    if (frame.done === false) return;
    shares.hidden = false;
    if (shares.childElementCount) setConnectionStatus(connection.connectedLabel, connection.attempt.relay ? 'relay' : 'direct');
    else setConnectionStatus('没有可访问的网页');
  }
}

refresh.addEventListener('click', () => void listEndpoints());
back.addEventListener('click', () => {
  history.replaceState(null, '', '/web/');
  showRoute();
  directoryTitle.focus();
});
showRoute();
addEventListener('popstate', showRoute);
addEventListener('pagehide', () => active?.close());
addEventListener('pageshow', event => { if (event.persisted) showRoute(); });
