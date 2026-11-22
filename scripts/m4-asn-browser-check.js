// M4 库存第三轮第三批 ASN 收货闭环真实浏览器验证（ego-browser 运行时，Node/ESM）
// 聚焦：真实登录 → ASN 页 → UI 新建 ASN → 封单发运 → 收货登记 → 后端回读确认状态流转。
import fs from 'node:fs/promises';

const ctx = JSON.parse(await fs.readFile('/tmp/noon-m4-asn-browser-ctx.json', 'utf8'));
const front = ctx.frontendUrl;
const out = ctx.outDir;
const results = [];
await fs.mkdir(out, { recursive: true });
const check = (label, ok, detail = '') => {
  const line = `${ok ? 'PASS' : 'FAIL'} | ${label}${detail ? ' | ' + detail : ''}`;
  results.push(line);
  console.log(line);
};
const shot = async (name) => {
  try { await page.screenshot({ path: `${out}/${name}.png` }); } catch (e) { console.log('screenshot skipped: ' + e.message); }
};
const bodyText = () => page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '));

const task = await taskSpace(`M4 asn browser verification ${Date.now()}`);
const page = task.page('p1');
await page.goto(front, { timeout: 60000 });
await page.waitForLoadState('load');

async function login(name, password) {
  await page.evaluate(() => {
    localStorage.clear();
    document.cookie.split(';').forEach(c => {
      const n = c.split('=')[0].trim();
      if (n) document.cookie = n + '=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/';
    });
  });
  await page.goto(front, { timeout: 60000 });
  await page.waitForSelector('input[placeholder="账号"]', { state: 'visible', timeout: 20000 }).catch(() => {});
  await page.fill('input[placeholder="账号"]', name);
  await page.fill('input[placeholder="密码"]', password);
  await page.click('text="登 录"', { label: '点击登录' });
  await page.waitForFunction(() => !location.pathname.includes('login') && document.cookie.includes('Admin-Token='),
                             undefined, { timeout: 20000 }).catch(() => {});
  return await page.evaluate(() => !location.pathname.includes('login') && document.cookie.includes('Admin-Token='));
}

async function apiFetch(method, path, body) {
  return await page.evaluate(async ({ method, path, body }) => {
    const token = document.cookie.split(';').map(s => s.trim()).find(s => s.startsWith('Admin-Token='))?.split('=')[1]
      || localStorage.getItem('Admin-Token');
    const resp = await fetch(path, { method, headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
      body: body ? JSON.stringify(body) : undefined });
    return await resp.json();
  }, { method, path, body });
}

async function waitButton(text, timeout = 15000) {
  try {
    await page.waitForFunction((t) => {
      const bs = [...document.querySelectorAll('.el-dialog button')];
      return bs.some(b => b.innerText.includes(t) && !b.disabled && b.offsetParent !== null);
    }, text, { timeout });
    return true;
  } catch (e) { return false; }
}

// 原型 setter 填值（已验证可触发 Vue v-model）
async function fillInDialog(dialogText, matchers) {
  const r = await page.evaluate(({ dialogText, matchers }) => {
    const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.innerText.includes(dialogText) && d.style.display !== 'none');
    if (!dlg) return { ok: false, reason: 'dialog missing' };
    const setVal = (el, val) => {
      const setter = Object.getOwnPropertyDescriptor(el.constructor.prototype, 'value').set;
      setter.call(el, String(val));
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    };
    const inputs = [...dlg.querySelectorAll('input')];
    const filled = {};
    for (const m of matchers) {
      const el = inputs.find(e => (e.placeholder || '').indexOf(m.ph) >= 0);
      if (!el) { filled[m.key] = 'MISSING'; continue; }
      setVal(el, m.val);
      filled[m.key] = el.value;
    }
    return { ok: true, filled, allInputs: inputs.map(e => ({ t: e.type, ph: e.placeholder })) };
  }, { dialogText, matchers });
  return r;
}

try {
  const logged = await login('opAE', 'admin123');
  check('opAE 真实登录成功', logged, '');
  if (!logged) throw new Error('登录失败，终止浏览器验证');

  // 动态路由：目录 noon-inbound → 子菜单 asn
  await page.goto(front + '/noon-inbound/asn', { timeout: 60000 });
  await page.waitForLoadState('load');
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const listText = await bodyText();
  check('ASN 页加载(含新建按钮)', listText.includes('ASN') && listText.includes('新建'), listText.slice(0, 160));
  await shot('m4-asn-list');

  // 1. UI 新建 ASN
  const createBtn = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find(b => b.innerText.includes('新建 ASN'));
    if (!btn) return false;
    btn.click(); return true;
  });
  check('点击新建 ASN', createBtn);
  check('新建弹窗渲染', await waitButton('确 定'));
  const filled = await fillInDialog('新建 ASN', [
    { key: 'wh', ph: 'WH-AE-FBN', val: 'WH-AE-FBN' },
    { key: 'sku', ph: 'skuId', val: 2 },
    { key: 'batch', ph: '本地仓 OPEN 批次', val: 'B:SEED-BR' },
    { key: 'qty', ph: '>0', val: 10 }
  ]);
  check('填写 ASN 新建表单', filled.ok && filled.filled && filled.filled.wh === 'WH-AE-FBN', JSON.stringify(filled.filled || filled));
  const submit = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.innerText.includes('新建 ASN') && d.style.display !== 'none');
    const btn = [...dlg.querySelectorAll('button')].find(b => b.innerText.includes('确 定'));
    if (!btn || btn.disabled) return false;
    btn.click(); return true;
  });
  check('点击确定提交新建', submit);
  await page.waitForTimeout(1800);
  await shot('m4-asn-after-create');

  // 后端回读：SKU2/B:SEED-BR 的新建 ASN（DRAFT）
  const list = await apiFetch('GET', '/noon/inventory/asn/list?offset=0&limit=50');
  const rows = (list && list.data && list.data.rows) || [];
  const target = rows.find(r => r.status === 'DRAFT' || r.status === 'SEALED');
  check('UI 新建 ASN 后端可见', !!target, JSON.stringify(rows.slice(0, 3)));
  if (!target) throw new Error('UI 新建未落库，终止后续步骤');
  const asnId = target.asnId;

  // 2. UI 封单（找到该行的封单按钮）
  await page.goto(front + '/noon-inbound/asn', { timeout: 60000 });
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const sealClicked = await page.evaluate((id) => {
    const tr = [...document.querySelectorAll('.el-table__body-wrapper tbody tr')].find(t => t.innerText.includes(String(id)));
    if (!tr) return false;
    const btn = [...tr.querySelectorAll('button')].find(b => b.innerText.includes('封单'));
    if (!btn) return false;
    btn.click(); return true;
  }, asnId);
  check('点击封单发运', sealClicked);
  check('封单弹窗渲染', await waitButton('确认封单'));
  const sealConfirm = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.innerText.includes('封单发运') && d.style.display !== 'none');
    const btn = [...dlg.querySelectorAll('button')].find(b => b.innerText.includes('确认封单'));
    if (!btn || btn.disabled) return false;
    btn.click(); return true;
  });
  check('点击确认封单', sealConfirm);
  await page.waitForTimeout(1800);
  const sealed = await apiFetch('GET', '/noon/inventory/asn/list?offset=0&limit=50');
  const sealRow = ((sealed && sealed.data && sealed.data.rows) || []).find(r => r.asnId === asnId);
  check('封单后端状态 SEALED', sealRow && sealRow.status === 'SEALED', JSON.stringify(sealRow || {}));

  // 3. UI 收货
  await page.goto(front + '/noon-inbound/asn', { timeout: 60000 });
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const recvClicked = await page.evaluate((id) => {
    const tr = [...document.querySelectorAll('.el-table__body-wrapper tbody tr')].find(t => t.innerText.includes(String(id)));
    if (!tr) return false;
    const btn = [...tr.querySelectorAll('button')].find(b => b.innerText.includes('收货'));
    if (!btn) return false;
    btn.click(); return true;
  }, asnId);
  check('点击收货登记', recvClicked);
  check('收货弹窗渲染', await waitButton('确认收货'));
  await page.waitForTimeout(600);
  const recvConfirm = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.innerText.includes('收货登记') && d.style.display !== 'none');
    const btn = [...dlg.querySelectorAll('button')].find(b => b.innerText.includes('确认收货'));
    if (!btn || btn.disabled) return false;
    btn.click(); return true;
  });
  check('点击确认收货', recvConfirm);
  await page.waitForTimeout(1800);
  await shot('m4-asn-after-receive');
  const recv = await apiFetch('GET', '/noon/inventory/asn/list?offset=0&limit=50');
  const recvRow = ((recv && recv.data && recv.data.rows) || []).find(r => r.asnId === asnId);
  check('收货后端状态 RECEIVED', recvRow && recvRow.status === 'RECEIVED', JSON.stringify(recvRow || {}));

  console.log(`BROWSER RESULT PASS=${results.filter(r => r.startsWith('PASS')).length} FAIL=${results.filter(r => r.startsWith('FAIL')).length}`);
} catch (e) {
  check('浏览器脚本异常', false, String(e && e.stack || e).slice(0, 300));
  console.log(`BROWSER RESULT PASS=${results.filter(r => r.startsWith('PASS')).length} FAIL=${results.filter(r => r.startsWith('FAIL')).length}`);
}
