// M5 订单与退货真实浏览器验证（ego-browser 运行时，Node/ESM）
// 聚焦：真实登录 → 订单退货页 → UI 订单导入 → 列表可见该订单号 → 退货 tab 可见。
import fs from 'node:fs/promises';

const ctx = JSON.parse(await fs.readFile('/tmp/noon-m5-sales-browser-ctx.json', 'utf8'));
const front = ctx.frontendUrl;
const out = ctx.outDir;
const results = [];
await fs.mkdir(out, { recursive: true });
const check = (label, ok, detail = '') => {
  const line = `${ok ? 'PASS' : 'FAIL'} | ${label}${detail ? ' | ' + detail : ''}`;
  results.push(line);
  console.log(line);
};
const shot = async (name) => { try { await page.screenshot({ path: `${out}/${name}.png` }); } catch (e) {} };
const bodyText = () => page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '));

const task = await taskSpace(`M5 sales browser verification ${Date.now()}`);
const page = task.page('p1');
await page.goto(front, { timeout: 60000 });
await page.waitForLoadState('load');

async function login(name, password) {
  await page.evaluate(() => {
    localStorage.clear();
    document.cookie.split(';').forEach(c => { const n = c.split('=')[0].trim(); if (n) document.cookie = n + '=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/'; });
  });
  await page.goto(front, { timeout: 60000 });
  await page.waitForSelector('input[placeholder="账号"]', { state: 'visible', timeout: 20000 }).catch(() => {});
  await page.fill('input[placeholder="账号"]', name);
  await page.fill('input[placeholder="密码"]', password);
  await page.click('text="登 录"', { label: '点击登录' });
  await page.waitForFunction(() => !location.pathname.includes('login') && document.cookie.includes('Admin-Token='), undefined, { timeout: 20000 }).catch(() => {});
  return await page.evaluate(() => !location.pathname.includes('login') && document.cookie.includes('Admin-Token='));
}

async function apiFetch(method, path, body) {
  return await page.evaluate(async ({ method, path, body }) => {
    const token = document.cookie.split(';').map(s => s.trim()).find(s => s.startsWith('Admin-Token='))?.split('=')[1] || localStorage.getItem('Admin-Token');
    const resp = await fetch(path, { method, headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token }, body: body ? JSON.stringify(body) : undefined });
    return await resp.json();
  }, { method, path, body });
}

try {
  const logged = await login('opAE', 'admin123');
  check('opAE 真实登录成功', logged, '');
  if (!logged) throw new Error('登录失败，终止浏览器验证');

  // 动态路由：目录 noon-sales → 子菜单 order（component noon/sales/flow/index，未设 redirect）
  await page.goto(front + '/noon-sales/order', { timeout: 60000 });
  await page.waitForLoadState('load');
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const pageText = await bodyText();
  check('订单退货页加载(含订单导入按钮)', pageText.includes('销售订单') && pageText.includes('订单导入'), pageText.slice(0, 160));
  await shot('m5-sales-list');

  // UI 订单导入：填订单号/门店7/SKU1/PSKU-1/金额150/数量5；站点AE/币种AED为默认值
  const createBtn = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find(b => b.innerText.includes('订单导入') && !b.disabled);
    if (!btn) return false;
    btn.click();
    return true;
  });
  check('点击订单导入按钮', createBtn);
  await page.waitForTimeout(500);
  const dialogReady = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.innerText.includes('订单导入') && d.style.display !== 'none');
    return !!dlg;
  });
  check('订单导入弹窗渲染', dialogReady);

  const filled = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.innerText.includes('订单导入') && d.style.display !== 'none');
    if (!dlg) return { ok: false, reason: 'dialog missing' };
    const setInput = (el, val) => {
      const setter = Object.getOwnPropertyDescriptor(el.constructor.prototype, 'value').set;
      setter.call(el, String(val));
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    };
    const byPh = (kw) => [...dlg.querySelectorAll('input')].find(e => (e.placeholder || '').indexOf(kw) >= 0);
    const orderNoEl = byPh('平台订单号');
    const storeEl = byPh('md_store');
    const skuEl = byPh('md_sku');
    const pskuEl = byPh('md_partner_sku');
    const amtEl = byPh('原币金额');
    const qtyEl = byPh('>0');
    if (!orderNoEl || !storeEl || !skuEl || !pskuEl || !amtEl || !qtyEl) {
      return { ok: false, reason: 'inputs missing', inputs: [...dlg.querySelectorAll('input')].map(e => e.placeholder) };
    }
    setInput(orderNoEl, 'M5-BROWSER-O-1');
    setInput(storeEl, 7);
    setInput(skuEl, 1);
    setInput(pskuEl, 'PSKU-1');
    setInput(amtEl, 150);
    setInput(qtyEl, 5);
    return { ok: true, orderNo: orderNoEl.value, qty: qtyEl.value };
  });
  check('填写订单导入表单', filled.ok, JSON.stringify(filled));

  const submitted = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.innerText.includes('订单导入') && d.style.display !== 'none');
    if (!dlg) return false;
    const btn = [...dlg.querySelectorAll('button')].find(b => (b.innerText.replace(/\s/g, '').includes('确定')) && !b.disabled);
    if (!btn) return false;
    btn.click();
    return true;
  });
  check('点击确定提交订单', submitted);
  await page.waitForTimeout(1800);
  await shot('m5-sales-after-import');

  // 后端回读：订单 M5-BROWSER-O-1 应可见
  const list = await apiFetch('GET', '/noon/sales/order/list?offset=0&limit=50');
  const rows = (list && list.data && list.data.rows) || [];
  const uiRow = rows.find(r => r.orderNo === 'M5-BROWSER-O-1');
  check('UI 导入订单后端可见(M5-BROWSER-O-1)', !!uiRow, JSON.stringify(rows.slice(0, 3)));
  const afterText = await bodyText();
  check('列表页显示导入订单', afterText.includes('M5-BROWSER-O-1'), afterText.slice(0, 180));

  // 切到退货 tab，验证退货页可加载
  const tabClicked = await page.evaluate(() => {
    const tab = [...document.querySelectorAll('.el-tabs__item')].find(t => t.innerText.includes('退货单'));
    if (!tab) return false;
    tab.click();
    return true;
  });
  await page.waitForTimeout(800);
  const returnTabText = await bodyText();
  check('退货 tab 加载(含退货导入按钮)', tabClicked && returnTabText.includes('退货导入'), returnTabText.slice(0, 160));

  console.log(`BROWSER RESULT PASS=${results.filter(r => r.startsWith('PASS')).length} FAIL=${results.filter(r => r.startsWith('FAIL')).length}`);
} catch (e) {
  check('浏览器脚本异常', false, String(e && e.stack || e).slice(0, 300));
  console.log(`BROWSER RESULT PASS=${results.filter(r => r.startsWith('PASS')).length} FAIL=${results.filter(r => r.startsWith('FAIL')).length}`);
}
