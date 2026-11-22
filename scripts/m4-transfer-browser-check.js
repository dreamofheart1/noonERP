// M4 库存第二轮调拨关键流程真实浏览器验证（ego-browser 运行时，Node/ESM）
// 由 verify-m4-transfer-e2e.py 写 ctx 后经 `ego-browser nodejs` 调用。
// 聚焦：真实登录 → 调拨页 → 发运（在途拆批）→ 收货（转OPEN+短少）→ 批次/台账页可见 → 跨站拒绝。
import fs from 'node:fs/promises';

const ctx = JSON.parse(await fs.readFile('/tmp/noon-m4-transfer-browser-ctx.json', 'utf8'));
const front = ctx.frontendUrl;
const back = ctx.backendUrl;
const out = ctx.outDir;
const targetNo = ctx.browserTransferNo; // v1.2：由 create API 真实新建，不再依赖种子单
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

const task = await taskSpace(`M4 transfer browser verification ${Date.now()}`);
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

// 在包含关键字的行上点指定按钮（用 DOM evaluate，避开 locator）
async function clickRowAction(rowKeyword, buttonText) {
  return await page.evaluate(({ rowKeyword, buttonText }) => {
    const rows = [...document.querySelectorAll('.el-table__body-wrapper tbody tr')];
    const row = rows.find(tr => tr.innerText.includes(rowKeyword));
    if (!row) return { ok: false, reason: 'row not found' };
    const btn = [...row.querySelectorAll('button')].find(b => b.innerText.includes(buttonText));
    if (!btn) return { ok: false, reason: 'button not found' };
    btn.click();
    return { ok: true };
  }, { rowKeyword, buttonText });
}

try {
  const logged = await login('opAE', 'admin123');
  check('opAE 真实登录成功', logged, '');
  if (!logged) throw new Error('登录失败，终止浏览器验证');

  // 打开调拨页
  await page.goto(front + '/noon-inventory/transfer', { timeout: 60000 });
  await page.waitForLoadState('load');
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const listText = await bodyText();
  check('调拨单页面加载(含发运/收货)', listText.includes('调拨') && listText.includes('发运'), listText.slice(0, 130));
  const rows0 = await tableRows();
  check('调拨单列表有数据行', rows0.length >= 1, `rows=${rows0.length}`);
  await shot('m4-transfer-list');

  // v1.2：真实 UI 新建调拨单（Codex 要求：新建→发运→收货全流程，不依赖种子）
  const createBtn = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find(b => b.innerText.includes('新建调拨'));
    if (!btn) return false; btn.click(); return true;
  });
  check('点击新建调拨按钮', createBtn);
  await page.waitForFunction(() => [...document.querySelectorAll('.el-dialog')].some(d => d.innerText.includes('新建调拨单')),
                             undefined, { timeout: 15000 }).catch(() => {});
  await shot('m4-transfer-create-form');
  const createdNo = await page.evaluate(() => {
    const dlg = [...document.querySelectorAll('.el-dialog')].find(d => d.innerText.includes('新建调拨单'));
    if (!dlg) return null;
    const setVal = (el, v) => { const s = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; s.call(el, v); el.dispatchEvent(new Event('input', { bubbles: true })); };
    const nums = [...dlg.querySelectorAll('.el-input-number input')];
    setVal(nums[0], '1'); setVal(nums[1], '3');           // 源仓1 → 目标仓3
    const txts = [...dlg.querySelectorAll('.el-input input')];
    setVal(txts[0], 'B:SEED-BR');                          // 来源批次
    const qty = [...dlg.querySelectorAll('.el-input-number input')];
    setVal(qty[2], '5');                                   // 计划量
    const btn = [...dlg.querySelectorAll('button')].find(b => b.innerText.includes('确认新建'));
    if (btn) btn.click();
    return true;
  });
  check('填写并提交新建表单', createdNo === true);
  await page.waitForTimeout(1200);
  const createdList = await apiFetch('GET', back + '/noon/inventory/transfer/list?offset=0&limit=50');
  const uiCreated = (createdList?.data?.rows || []).find(r => r.itemCount === 1 && r.status === 'DRAFT'
    && r.fromWarehouseId === 1 && r.toWarehouseId === 3
    && !r.transferNo.includes('TR-AE-1') && r.transferNo.startsWith('TR:AE:'));
  check('UI 新建调拨单落库', !!uiCreated, JSON.stringify(uiCreated || {}).slice(0, 160));
  await shot('m4-transfer-created');

  // 发运浏览器专用单（由 create API 预建，单号来自 ctx）：点该行「发运」→ 弹窗 → 确认发运
  await page.evaluate(() => [...document.querySelectorAll('.el-dialog')].forEach(d => { const b = [...d.querySelectorAll('button')].find(x => x.innerText.includes('取 消') || x.innerText.includes('取消')); if (b && d.innerText.includes('新建调拨单')) b.click(); }));
  const shipClicked = await clickRowAction(targetNo, '发运');
  check('点击调拨发运按钮', shipClicked.ok, JSON.stringify(shipClicked));
  await page.waitForFunction(() => [...document.querySelectorAll('.el-dialog')].some(d => d.innerText.includes('调拨发运') && [...d.querySelectorAll('button')].some(b => b.innerText.includes('确认发运'))),
                             undefined, { timeout: 15000 }).catch(() => {});
  await shot('m4-transfer-ship-form');
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('.el-dialog button')].find(b => b.innerText.includes('确认发运'));
    if (btn) btn.click();
  });
  await page.waitForTimeout(1500);
  const afterShip = await bodyText();
  check('发运成功提示', afterShip.includes('发运成功') || afterShip.includes('未重复'), afterShip.slice(-160));
  await shot('m4-transfer-shipped');

  // 后端回读：调拨3 状态 IN_TRANSIT
  const detail1 = await apiFetch('GET', back + '/noon/inventory/transfer/list?offset=0&limit=20');
  const tr3 = (detail1?.data?.rows || []).find(r => r.transferNo === targetNo);
  check('发运后调拨状态 IN_TRANSIT', tr3?.status === 'IN_TRANSIT', JSON.stringify(tr3).slice(0, 120));

  // 收货：点该行「收货」→ 弹窗 → 确认收货
  const recvClicked = await clickRowAction(targetNo, '收货');
  check('点击调拨收货按钮', recvClicked.ok, JSON.stringify(recvClicked));
  // 发运弹窗 DOM 可能残留，通用 .el-dialog:visible 会误命中；必须等收货弹窗自己的按钮出现。
  await page.waitForFunction(() => [...document.querySelectorAll('.el-dialog button')].some(b => b.innerText.includes('确认收货')),
                             undefined, { timeout: 15000 }).catch(() => {});
  await page.evaluate(() => {
    const btn = [...document.querySelectorAll('.el-dialog button')].find(b => b.innerText.includes('确认收货'));
    if (btn) btn.click();
  });
  await page.waitForTimeout(1500);
  const afterRecv = await bodyText();
  check('收货成功提示', afterRecv.includes('收货成功') || afterRecv.includes('未重复'), afterRecv.slice(-160));
  await shot('m4-transfer-received');

  const detail2 = await apiFetch('GET', back + '/noon/inventory/transfer/list?offset=0&limit=20');
  const tr3b = (detail2?.data?.rows || []).find(r => r.transferNo === targetNo);
  check('收货后调拨状态 RECEIVED', tr3b?.status === 'RECEIVED', JSON.stringify(tr3b).slice(0, 120));

  // 批次页 / 台账页 UI 可见
  await page.goto(front + '/noon-inventory/batch', { timeout: 60000 });
  await page.waitForLoadState('load');
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const batchRows = await tableRows();
  check('FIFO 批次页面有数据行', batchRows.length >= 1, `rows=${batchRows.length}`);
  await shot('m4-transfer-batch-page');
  await page.goto(front + '/noon-inventory/ledger', { timeout: 60000 });
  await page.waitForLoadState('load');
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const ledgerRows = await tableRows();
  check('四态台账页面有数据行', ledgerRows.length >= 1, `rows=${ledgerRows.length}`);
  await shot('m4-transfer-ledger-page');

  // 跨站拒绝：opAE(仅AE) 通过 API 尝试 SA 仓发运 → 403/400
  const cross = await apiFetch('POST', back + '/noon/inventory/transfer/ship', {
    transferId: 2, fromWarehouseId: 1, toWarehouseId: 2, siteCode: 'AE',
    lines: [{ itemId: 2, qtyShipped: 5 }], requestId: 'BRTR' + Date.now()
  });
  check('跨站调拨被拒(403/400)', cross?.code === 403 || cross?.code === 400, `code=${cross?.code}`);

  const passed = results.filter(r => r.startsWith('PASS')).length;
  const failed = results.filter(r => r.startsWith('FAIL')).length;
  console.log(`\n==== BROWSER RESULT PASS=${passed} FAIL=${failed} ====`);
} catch (e) {
  console.log('FAIL | 浏览器脚本异常 | ' + e.message);
  throw e;
} finally {
  await fs.writeFile(`${out}/browser-results.txt`, results.join('\n') + '\n', 'utf8');
}
