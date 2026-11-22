// M4 库存第一轮关键流程真实浏览器验证（ego-browser 运行时，Node/ESM）
// 由 scripts/verify-m4-e2e.py 写 ctx 后调用。聚焦：真实登录 → 入库确认（生成批次/台账）→
// 批次页与台账页可见 → 跨站/无授权拒绝。
import fs from 'node:fs/promises';

const ctx = JSON.parse(await fs.readFile('/tmp/noon-m4-browser-ctx.json', 'utf8'));
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

const task = await taskSpace(`M4 browser verification ${Date.now()}`);
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

// 页面内 fetch：用当前登录态调用后端 API（查询验证）
async function apiFetch(method, path, body) {
  return await page.evaluate(async ({ method, path, body }) => {
    const token = document.cookie.split(';').map(s => s.trim()).find(s => s.startsWith('Admin-Token='))?.split('=')[1]
      || localStorage.getItem('Admin-Token');
    const resp = await fetch(path, { method, headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
      body: body ? JSON.stringify(body) : undefined });
    return await resp.json();
  }, { method, path, body });
}

try {
  // 1) 登录 opAE（有入库确认权限 + AE 站点）
  const logged = await login('opAE', 'admin123');
  check('opAE 真实登录成功', logged, '');
  if (!logged) throw new Error('登录失败，终止浏览器验证');

  // 2) 打开入库单页面（路由 /noon-inventory/inbound）
  await page.goto(front + '/noon-inventory/inbound', { timeout: 60000 });
  await page.waitForLoadState('load');
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const inboundText = await bodyText();
  check('入库单页面加载(含 M4 演示提示)', inboundText.includes('入库') && inboundText.includes('FIFO'), inboundText.slice(0, 120));
  await shot('m4-inbound-page');

  // 3) 通过 UI 新增入库：点击「新增入库」→ 填采购单ID → 加载明细 → 填仓库 → 确认
  // 用 page.evaluate 直接操作 DOM（ego-browser 的 page 无 locator/fill，只有 click/evaluate）
  const opened = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find(b => b.innerText.includes('新增入库'));
    if (btn) { btn.click(); return true; }
    return false;
  });
  check('点击新增入库按钮', opened, '');
  await page.waitForSelector('.el-dialog:visible', { state: 'visible', timeout: 15000 }).catch(() => {});

  // 用原生 setter 触发 Vue v-model：el-input-number 的 input[type=number]
  const fillDialog = await page.evaluate(() => {
    const setVal = (input, val) => {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      setter.call(input, val);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    };
    const nums = [...document.querySelectorAll('.el-dialog input[type="number"]')];
    if (nums.length < 2) return { ok: false, count: nums.length };
    setVal(nums[0], '1'); // 采购单ID
    setVal(nums[1], '1'); // 仓库ID
    return { ok: true, count: nums.length };
  });
  check('弹窗填写采购单/仓库', fillDialog.ok, JSON.stringify(fillDialog));
  // 触发“加载收货明细”
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('.el-dialog button')].find(b => b.innerText.includes('加载收货明细'));
    if (btn) btn.click();
  });
  await page.waitForTimeout(1000);
  const formRows = await page.evaluate(() =>
    [...document.querySelectorAll('.el-dialog .el-table__body-wrapper tbody tr')]
      .filter(tr => !tr.querySelector('.el-table__empty-block'))
      .map(tr => tr.innerText.replace(/\s+/g, ' ').trim()));
  check('弹窗加载出采购单1的收货明细', formRows.length >= 1, `rows=${formRows.length} ${formRows[0]?.slice(0,60) || ''}`);
  await shot('m4-inbound-form');

  // 4) 点「确认入库」→ 成功提示
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('.el-dialog button')].find(b => b.innerText.includes('确认入库'));
    if (btn) btn.click();
  });
  await page.waitForTimeout(1500);
  const afterText = await bodyText();
  check('入库确认成功提示', afterText.includes('入库成功') || afterText.includes('未重复入账'), afterText.slice(-160));
  await shot('m4-inbound-confirmed');

  // 5) 后端回读：批次/台账已生成且可用量含新入库
  const batches = await apiFetch('GET', back + '/noon/inventory/batch/list?offset=0&limit=10');
  const ledgers = await apiFetch('GET', back + '/noon/inventory/ledger/list?offset=0&limit=10');
  const batchTotal = batches?.data?.total || 0;
  const ledgerRows = ledgers?.data?.rows || [];
  check('批次列表回读有数据', batchTotal >= 1, `total=${batchTotal}`);
  check('台账回读 AE 仓可用量>0', ledgerRows.some(r => r.siteCode === 'AE' && r.qtyAvailable > 0),
        JSON.stringify(ledgerRows).slice(0, 140));

  // 6) 批次页 / 台账页 UI 可见
  await page.goto(front + '/noon-inventory/batch', { timeout: 60000 });
  await page.waitForLoadState('load');
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const batchRows = await tableRows();
  check('FIFO 批次页面有数据行', batchRows.length >= 1, `rows=${batchRows.length}`);
  await shot('m4-batch-page');
  await page.goto(front + '/noon-inventory/ledger', { timeout: 60000 });
  await page.waitForLoadState('load');
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const ledgerUiRows = await tableRows();
  check('四态台账页面有数据行', ledgerUiRows.length >= 1, `rows=${ledgerUiRows.length}`);
  await shot('m4-ledger-page');

  // 7) 跨站拒绝：opAE(仅AE) 通过 API 尝试 SA 仓入库 → 403
  const cross = await apiFetch('POST', back + '/noon/inventory/inbound/confirm', {
    purOrderId: 2, warehouseId: 2, siteCode: 'SA',
    lines: [{ purOrderItemId: 2, qtyQualified: 5 }], requestId: 'BR' + Date.now()
  });
  check('跨站入库被拒(403)', cross?.code === 403, `code=${cross?.code}`);

  const passed = results.filter(r => r.startsWith('PASS')).length;
  const failed = results.filter(r => r.startsWith('FAIL')).length;
  console.log(`\n==== BROWSER RESULT PASS=${passed} FAIL=${failed} ====`);
} catch (e) {
  console.log('FAIL | 浏览器脚本异常 | ' + e.message);
  throw e;
} finally {
  await fs.writeFile(`${out}/browser-results.txt`, results.join('\n') + '\n', 'utf8');
}
