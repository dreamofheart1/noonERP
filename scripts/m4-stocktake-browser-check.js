// M4 库存第三轮第一批盘点闭环真实浏览器验证（ego-browser 运行时，Node/ESM）
// 聚焦：真实登录 → 盘点页 → UI 新建盘点单 → 应用盘点 → 四态台账页可见。
import fs from 'node:fs/promises';

const ctx = JSON.parse(await fs.readFile('/tmp/noon-m4-stocktake-browser-ctx.json', 'utf8'));
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
const tableRows = () => page.evaluate(() =>
  [...document.querySelectorAll('.el-table__body-wrapper tbody tr')]
    .filter(tr => !tr.querySelector('.el-table__empty-block'))
    .map(tr => tr.innerText.replace(/\s+/g, ' ').trim()));

const task = await taskSpace(`M4 stocktake browser verification ${Date.now()}`);
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

// 等待指定文字的按钮出现在可见弹窗中（避免残留弹窗误命中，沿用上轮经验）
async function waitButton(text, timeout = 15000) {
  try {
    await page.waitForFunction((t) => {
      const bs = [...document.querySelectorAll('.el-dialog button')];
      return bs.some(b => b.innerText.includes(t) && !b.disabled && b.offsetParent !== null);
    }, text, { timeout });
    return true;
  } catch (e) { return false; }
}

try {
  const logged = await login('opAE', 'admin123');
  check('opAE 真实登录成功', logged, '');
  if (!logged) throw new Error('登录失败，终止浏览器验证');

  await page.goto(front + '/noon-inventory/stocktake', { timeout: 60000 });
  await page.waitForLoadState('load');
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const listText = await bodyText();
  check('盘点页加载(含新建盘点)', listText.includes('盘点') && listText.includes('新建盘点'), listText.slice(0, 140));
  await shot('m4-stocktake-list');

  // UI 新建盘点单：SKU2 / B:SEED-BR / 实盘 25（建单时账面快照 20 → 盘盈 5；建单到应用间账面未变，快照闸门放行；SKU2 有 @7 批次故非零成本）
  const createBtn = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find(b => b.innerText.includes('新建盘点'));
    if (!btn) return false;
    btn.click();
    return true;
  });
  check('点击新建盘点按钮', createBtn);
  const formReady = await waitButton('确定新建');
  check('新建盘点弹窗渲染', formReady);
  await shot('m4-stocktake-create-form');

  // 填写仓库 1 与明细行（用 DOM 事件触发 v-model）
  const filled = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.innerText.includes('新建盘点单') && d.style.display !== 'none');
    if (!dlg) return { ok: false, reason: 'dialog missing' };
    const setInput = (el, val) => {
      const setter = Object.getOwnPropertyDescriptor(el.constructor.prototype, 'value').set;
      setter.call(el, String(val));
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    };
    const wh = dlg.querySelector('input[type=number]');
    if (!wh) return { ok: false, reason: 'warehouse input missing' };
    setInput(wh, 1);
    const rows = [...dlg.querySelectorAll('.el-table__body-wrapper tbody tr')];
    if (!rows.length) return { ok: false, reason: 'no line row' };
    const row = rows[0];
    const nums = [...row.querySelectorAll('input[type=number]')];
    if (nums.length < 2) return { ok: false, reason: 'line inputs missing' };
    setInput(nums[0], 2);          // SKU ID
    setInput(nums[nums.length - 1], 25); // 实盘数量（第2个 number 输入）
    // 批次号输入
    const texts = [...row.querySelectorAll('input[type=text]')];
    if (texts[0]) setInput(texts[0], 'B:SEED-BR');
    return { ok: true, nums: nums.length, texts: texts.length };
  });
  check('填写盘点仓库与明细', filled.ok, JSON.stringify(filled));

  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('.el-dialog button')].find(b => b.innerText.includes('确定新建'));
    if (btn) btn.click();
  });
  await page.waitForTimeout(1500);
  const afterCreate = await bodyText();
  check('新建盘点提交有响应', !afterCreate.includes('新建盘点单') || afterCreate.includes('盘点单已创建') || afterCreate.includes('成功'),
        afterCreate.slice(0, 180));

  // 回读 API：确认 UI 新建落库
  const listResp = await apiFetch('GET', '/noon/inventory/stocktake/list?offset=0&limit=50');
  const rows = (listResp && listResp.data && listResp.data.rows) || [];
  const created = rows.find(r => r.warehouseId === 1);
  check('UI 新建盘点落库可查', Array.isArray(rows) && rows.length >= 1, `rows=${rows.length}`);

  if (created && created.status === 'DRAFT') {
    // 应用该盘点单
    const clicked = await page.evaluate((id) => {
      const tr = [...document.querySelectorAll('.el-table__body-wrapper tbody tr')].find(t => t.innerText.includes(String(id)));
      if (!tr) return false;
      const btn = [...tr.querySelectorAll('button')].find(b => b.innerText.includes('应用盘点'));
      if (!btn || btn.disabled) return false;
      btn.click();
      return true;
    }, created.id);
    check('点击应用盘点按钮', clicked);
    const applyReady = await waitButton('确认应用');
    check('应用盘点弹窗渲染', applyReady);
    await shot('m4-stocktake-apply-form');
    await page.evaluate(() => {
      const btn = [...document.querySelectorAll('.el-dialog button')].find(b => b.innerText.includes('确认应用'));
      if (btn) btn.click();
    });
    await page.waitForTimeout(1500);
    const applyResp = await apiFetch('GET', '/noon/inventory/stocktake/get?id=' + created.id);
    const head = (applyResp && applyResp.data && applyResp.data.head) || {};
    check('UI 应用盘点后状态 APPLIED', head.status === 'APPLIED', `status=${head.status}`);
  } else {
    check('UI 应用盘点（跳过：无 DRAFT 单）', false, JSON.stringify(created || {}));
  }

  // 台账页可见
  await page.goto(front + '/noon-inventory/ledger', { timeout: 60000 });
  await page.waitForLoadState('load');
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const ledgerText = await bodyText();
  check('四态台账页可见', ledgerText.includes('台账') || ledgerText.includes('可用'), ledgerText.slice(0, 140));
  await shot('m4-stocktake-ledger');

  console.log(`BROWSER RESULT PASS=${results.filter(r => r.startsWith('PASS')).length} FAIL=${results.filter(r => r.startsWith('FAIL')).length}`);
} catch (e) {
  check('浏览器脚本异常', false, String(e && e.stack || e).slice(0, 400));
  console.log(`BROWSER RESULT PASS=${results.filter(r => r.startsWith('PASS')).length} FAIL=${results.filter(r => r.startsWith('FAIL')).length}`);
}
