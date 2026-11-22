// M3 供应商与采购关键流程真实浏览器验证（ego-browser 运行时，Node/ESM）
// 由 scripts/verify-m3-e2e.py 写 ctx 后调用。聚焦 Codex 要求的关键路径：
// 真实登录 → 采购单状态机闭环（确认→部分收货→补齐→关单）→ 跨站/无授权拒绝。
import fs from 'node:fs/promises';

const ctx = JSON.parse(await fs.readFile('/tmp/noon-m3-browser-ctx.json', 'utf8'));
const front = ctx.frontendUrl;
const back = ctx.backendUrl;
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

const task = await taskSpace(`M3 browser verification ${Date.now()}`);
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

// 页面内 fetch：用当前登录态调用后端 API（准备数据 / 回读状态）
async function apiFetch(method, path, body) {
  return await page.evaluate(async ({ m, p, b, base }) => {
    const token = document.cookie.match(/Admin-Token=([^;]+)/)?.[1] || '';
    const r = await fetch(base.replace(/\/$/, '') + p, {
      method: m,
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
      body: b ? JSON.stringify(b) : undefined
    });
    return { http: r.status, json: await r.json().catch(() => null) };
  }, { m: method, p: path, b: body, base: back });
}

// ---- 1. 超管真实登录 ----
check('超管登录页真实登录', await login('admin', 'admin123'), await page.url());
await page.goto(front + '/index', { timeout: 60000 });
await page.waitForTimeout(2000);
await shot('01-admin-home');

// ---- 2. 用后端 API 准备一张带 2 行的 DRAFT 采购单 ----
const created = await apiFetch('POST', '/noon/purchasing/order/save', {
  orderNo: 'PO-BROWSER-M3', supplierId: 1, siteCode: 'AE', currency: 'CNY', source: 'MANUAL',
  items: [
    { skuId: 1, qtyOrdered: 100, unitPrice: 12, currency: 'CNY', fxRate: 1 },
    { skuId: 2, qtyOrdered: 50, unitPrice: 5, currency: 'CNY', fxRate: 1 }
  ]
});
check('后端API准备DRAFT采购单', created.json?.code === 200, `code=${created.json?.code}`);

// ---- 3. 打开采购单页，搜索定位本次单据 ----
await page.goto(front + '/noon-purchasing/order', { timeout: 60000 });
await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
await page.waitForTimeout(1500);
await shot('02-order-list');
check('采购单页加载', (await tableRows()).length > 0, `rows=${(await tableRows()).length}`);

// 搜索框（订单编号）定位
const searched = await page.evaluate(() => {
  const el = document.querySelector('.app-container form input[placeholder*="订单"]')
          || document.querySelector('.app-container form input[placeholder*="编号"]')
          || document.querySelector('.app-container form input[type="text"]');
  if (!el) return false;
  el.setAttribute('data-m3-search', '1');
  return true;
});
if (searched) {
  await page.fill('[data-m3-search="1"]', 'PO-BROWSER-M3');
  // 触发查询（搜索按钮或回车）
  await page.click('.app-container form button:has-text("搜索")').catch(async () => {
    await page.press('[data-m3-search="1"]', 'Enter');
  });
  await page.waitForTimeout(1500);
}
const afterSearch = await tableRows();
check('浏览器搜索定位采购单', afterSearch.some(r => r.includes('PO-BROWSER-M3')),
      `rows=${afterSearch.length} first=${afterSearch[0]?.slice(0, 60)}`);
await shot('03-order-search');

// 回读本单 id（供状态机按钮定位）
const detail = await apiFetch('GET', '/noon/purchasing/order/list?orderNo=PO-BROWSER-M3');
const po = detail.json?.data?.rows?.[0] || {};
const oid = po.id;
check('回读采购单id与DRAFT状态', !!oid && po.status === 'DRAFT', `id=${oid} status=${po.status}`);

// 搜索后表格只有本单一行，行内按钮直接按文本定位即可（无需 nth 链式）
async function clickRowButton(text) {
  await page.click(`.el-table__body-wrapper tbody tr button:has-text("${text}")`, { label: `点击${text}` }).catch((e) => {
    console.log(`clickRowButton(${text}) 失败: ${e.message}`);
  });
  await page.waitForTimeout(1200);
}

// ---- 4. 真实 UI 点击「确认」 DRAFT→CONFIRMED ----
await clickRowButton('确认');
const afterConfirm = await apiFetch('GET', `/noon/purchasing/order/${oid}`);
check('真实UI确认 DRAFT→CONFIRMED', afterConfirm.json?.data?.status === 'CONFIRMED',
      `status=${afterConfirm.json?.data?.status}`);
await shot('04-order-confirmed');

// ---- 5. 真实 UI 点击「收货」：部分收货 60 + 补齐 ----
async function doReceive(quantities) {
  await page.click('.el-table__body-wrapper tbody tr button:has-text("收货")', { label: '点击收货' }).catch((e) => {
    console.log('点击收货失败: ' + e.message);
  });
  await page.waitForSelector('.el-dialog:visible', { state: 'visible', timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(500);
  // 收货弹窗表格：每行有一个数量输入框，按索引填值
  const filled = await page.evaluate((vals) => {
    const inputs = [...document.querySelectorAll('.el-dialog .el-table input[type="number"], .el-dialog .el-table input.el-input__inner')];
    if (inputs.length < vals.length) return inputs.length;
    vals.forEach((v, i) => {
      const el = inputs[i];
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(el, String(v));
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    });
    return inputs.length;
  }, quantities);
  await page.waitForTimeout(300);
  await page.click('.el-dialog button:has-text("确 定")', { label: '提交收货' }).catch(() => {});
  await page.waitForTimeout(1500);
  return filled;
}
const n1 = await doReceive([60, 0]);
const afterPartial = await apiFetch('GET', `/noon/purchasing/order/${oid}`);
check('真实UI部分收货→PARTIAL_RECEIVED', afterPartial.json?.data?.status === 'PARTIAL_RECEIVED',
      `status=${afterPartial.json?.data?.status} inputs=${n1}`);
await shot('05-order-partial');

// 补齐：若已是 PARTIAL_RECEIVED，收货按钮仍显示，收剩余 40+50
if (afterPartial.json?.data?.status === 'PARTIAL_RECEIVED') {
  await doReceive([40, 50]);
}
const afterFull = await apiFetch('GET', `/noon/purchasing/order/${oid}`);
check('真实UI补齐收货→RECEIVED', afterFull.json?.data?.status === 'RECEIVED',
      `status=${afterFull.json?.data?.status}`);
await shot('06-order-received');

// ---- 6. 真实 UI 点击「关单」 RECEIVED→CLOSED ----
await clickRowButton('关单');
const afterClose = await apiFetch('GET', `/noon/purchasing/order/${oid}`);
check('真实UI关单 RECEIVED→CLOSED', afterClose.json?.data?.status === 'CLOSED',
      `status=${afterClose.json?.data?.status}`);
await shot('07-order-closed');

// ---- 7. 收货明细台账页可见 ----
await page.goto(front + '/noon-purchasing/receipt', { timeout: 60000 });
await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
await page.waitForTimeout(1200);
check('收货记录页加载', (await tableRows()).length > 0, `rows=${(await tableRows()).length}`);
await shot('08-receipt-list');

// ---- 8. opAE 真实登录：跨站拒绝（SA 单不可见/不可确认）----
check('opAE 登录页真实登录', await login('opAE', 'admin123'), '');
await page.goto(front + '/noon-purchasing/order', { timeout: 60000 });
await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
await page.waitForTimeout(1500);
const aeRows = await tableRows();
check('opAE 采购单页不见SA单', !aeRows.some(r => r.includes('PO-SA-1')), `rows=${aeRows.length}`);
await shot('09-opAE-order-scoped');

// ---- 9. viewer 真实登录：无站点零行 ----
check('viewer 登录页真实登录', await login('viewer', 'admin123'), '');
await page.goto(front + '/noon-purchasing/order', { timeout: 60000 });
await page.waitForTimeout(1500);
const viewerRows = await tableRows();
check('viewer 采购单页零行', viewerRows.length === 0, `rows=${viewerRows.length} text=${(await bodyText()).slice(0, 80)}`);
await shot('10-viewer-order-denied');

await fs.writeFile(`${out}/browser-results.txt`, results.join('\n') + '\n', 'utf8');
await task.finish({ keep: [] });
console.log('BROWSER_RESULT_PASS=' + results.filter(r => r.startsWith('PASS')).length);
console.log('BROWSER_RESULT_FAIL=' + results.filter(r => r.startsWith('FAIL')).length);
