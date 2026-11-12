import { createDeckServer } from '../mac/server.mjs';

const token = process.env.ANDPAN_TOKEN;
const port = Number(process.env.ANDPAN_PORT || 43126);
const target = { name: '本地测试应用', pid: 42 };
let muted = true;
const deck = createDeckServer({
  token,
  desktopDriver: async request => {
    if (request.action === 'status') return { target, input: 'BlackHole 2ch', accessibility: true };
    if (request.pid !== target.pid) throw new Error('测试应用已变化');
    console.log(`desktop:${request.action}`);
    return { dispatched: true };
  },
  microphoneDriver: async request => {
    if (request.action === 'set') {
      muted = request.muted;
      console.log(`microphone:${muted ? 'muted' : 'open'}`);
    }
    return { muted };
  },
  fnDriver: async request => { console.log(`fn:${request.action}`); return { ok: true }; },
});
await deck.microphone.stop();
deck.server.listen(port, '127.0.0.1', () => console.log(`fixture:${port}`));
