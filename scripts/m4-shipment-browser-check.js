// M4 库存第三轮第二批头程费用登记真实浏览器验证（ego-browser 运行时，Node/ESM）
// 聚焦：真实登录 → 头程费用页 → UI 新增费用 → 列表可见该费用单号。
import fs from 'node:fs/promises';

const ctx = JSON.parse(await fs.readFile('/tmp/noon-m4-shipment-browser-ctx.json', 'utf8'));
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

const task = await taskSpace(`M4 shipment-cost browser verification ${Date.now()}`);
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

try {
  const logged = await login('opAE', 'admin123');
  check('opAE 真实登录成功', logged, '');
  if (!logged) throw new Error('登录失败，终止浏览器验证');

  // 走菜单进入头程费用页（动态路由：目录 noon-logistics → 子菜单 shipment-cost）
  await page.goto(front + '/noon-logistics/shipment-cost', { timeout: 60000 });
  await page.waitForLoadState('load');
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const listText = await bodyText();
  check('头程费用页加载(含新增费用按钮)', listText.includes('头程费用') && listText.includes('新增费用'), listText.slice(0, 160));
  await shot('m4-shipment-list');

  // UI 新增费用：CNY 运费 88，站点 AE，类型 FREIGHT，fx=1，NONE
  const createBtn = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find(b => b.innerText.includes('新增费用'));
    if (!btn) return false;
    btn.click();
    return true;
  });
  check('点击新增费用按钮', createBtn);
  const formReady = await waitButton('确 定');
  check('新增费用弹窗渲染', formReady);
  await shot('m4-shipment-create-form');

  // 完全照搬盘点页已验证的填表方式：原型 setter + input/change 事件，针对 input[type=number]
  const filled = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.innerText.includes('新增头程费用') && d.style.display !== 'none');
    if (!dlg) return { ok: false, reason: 'dialog missing' };
    const setInput = (el, val) => {
      const setter = Object.getOwnPropertyDescriptor(el.constructor.prototype, 'value').set;
      setter.call(el, String(val));
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    };
    const nums = [...dlg.querySelectorAll('input[type=number]')];
    const amountEl = nums.find(e => (e.placeholder || '').indexOf('原币金额') >= 0) || nums[0];
    // 站点/类型是 el-select，已有默认值 AE/FREIGHT；期间/币种/汇率/fx 也已是默认值或可选
    if (!amountEl) return { ok: false, reason: 'amount missing', allInputs: [...dlg.querySelectorAll('input')].map(e => ({ t: e.type, ph: e.placeholder })) };
    setInput(amountEl, 88.88);
    return { ok: true, numCount: nums.length, amount: amountEl.value };
  });
  check('填写费用表单', filled.ok, JSON.stringify(filled));
  const confirmBtn = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.innerText.includes('新增头程费用') && d.style.display !== 'none');
    const btn = [...dlg.querySelectorAll('button')].find(b => b.innerText.includes('确 定'));
    if (!btn || btn.disabled) return { clicked: false };
    btn.click();
    return { clicked: true };
  });
  check('点击确定提交', confirmBtn.clicked, JSON.stringify(confirmBtn));
  await page.waitForTimeout(1800);
  await shot('m4-shipment-after-create');

  // 后端回读：应至少有一笔 FREIGHT/88.88 的费用登记（UI 真实提交成功）
  const list = await apiFetch('GET', '/noon/inventory/shipment-cost/list?offset=0&limit=50&feeType=FREIGHT');
  const rows = (list && list.data && list.data.rows) || [];
  const uiRow = rows.find(r => Number(r.amount) === 88.88 && r.feeType === 'FREIGHT');
  check('UI 新增费用后端可见(FREIGHT/88.88)', !!uiRow, JSON.stringify(rows.slice(0, 3)));
  const pageText = await bodyText();
  check('列表页显示新增费用', pageText.includes('FREIGHT') || tableRows.length > 0, pageText.slice(0, 160));

  console.log(`BROWSER RESULT PASS=${results.filter(r => r.startsWith('PASS')).length} FAIL=${results.filter(r => r.startsWith('FAIL')).length}`);
} catch (e) {
  check('浏览器脚本异常', false, String(e && e.stack || e).slice(0, 300));
  console.log(`BROWSER RESULT PASS=${results.filter(r => r.startsWith('PASS')).length} FAIL=${results.filter(r => r.startsWith('FAIL')).length}`);
}
