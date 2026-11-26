import { Peer, shareItems, uuid } from './peer.js';

const query = new URLSearchParams(location.search);
const endpoint = query.get('endpoint');
const share = query.get('share');
let active;

function openShare() {
  if (!uuid.test(endpoint) || (share !== null && !uuid.test(share))) {
    console.warn('共享网址无效');
    return;
  }
  let selected = false;
  const connection = new Peer(endpoint, frame => {
    if (selected) return;
    const items = shareItems(frame);
    if (!items) return;
    const item = share === null ? items[0] : items.find(item => item.id === share);
    if (!item) {
      if (frame.done !== false) connection.fail('没有找到可访问的网页');
      return;
    }
    selected = true;
    document.title = item.name;
    void connection.view(item).catch(() => {
      if (!connection.closed) connection.fail('无法初始化网页入口');
    });
  }, message => console.warn(message));
  active = connection;
}

openShare();
addEventListener('pagehide', () => active?.close());
addEventListener('pageshow', event => { if (event.persisted) openShare(); });
