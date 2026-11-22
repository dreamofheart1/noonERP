// M2 五页真实浏览器验证（ego-browser 运行时，Node/ESM）
// 由 scripts/verify-m2-e2e.py 写入 /tmp/noon-m2-browser-ctx.json 后调用。
// 2026-11-22 改造：真实登录页登录 + 五页真实表单增改启停 + 授权边界（回应 Codex 复核）。
import fs from 'node:fs/promises';

const ctx = JSON.parse(await fs.readFile('/tmp/noon-m2-browser-ctx.json', 'utf8'));
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
const totalText = () => page.evaluate(() => document.querySelector('.el-pagination__total')?.textContent?.trim() || '');
const waitRows = (n, timeout = 10000) => page.waitForFunction(
  (expected) => [...document.querySelectorAll('.el-table__body-wrapper tbody tr')].length === expected,
  n, { timeout }).catch(() => {});

// ---- 通用表单操作：按 label 精确找到 form-item 内的控件，打临时标记后真实点击/填写 ----
async function markControl(labelText, kind) {
  return await page.evaluate(({ lbl, k }) => {
    document.querySelectorAll('[data-m2]').forEach(e => e.removeAttribute('data-m2'));
    const item = [...document.querySelectorAll('.el-dialog .el-form-item')]
      .find(e => e.querySelector('.el-form-item__label')?.textContent.trim() === lbl);
    const el = item && item.querySelector(k === 'select' ? '.el-select' : 'input');
    if (!el) return false;
    el.setAttribute('data-m2', '1');
    return true;
  }, { lbl: labelText, k: kind });
}
async function fillField(labelText, value) {
  if (!await markControl(labelText, 'input')) return false;
  await page.fill('[data-m2="1"]', value);
  return true;
}
async function pickSelect(labelText, optionText) {
  if (!await markControl(labelText, 'select')) return false;
  await page.click('[data-m2="1"]', { label: '打开下拉' });
  await page.waitForSelector('.el-select-dropdown__item', { state: 'visible', timeout: 5000 }).catch(() => {});
  // 用可见下拉项的全局索引做真实鼠标点击（DOM click 对 Element Plus 不稳定）
  const idx = await page.evaluate((txt) => {
    const all = [...document.querySelectorAll('.el-select-dropdown__item')];
    return all.findIndex(e => e.getClientRects().length && e.textContent.trim() === txt);
  }, optionText);
  if (idx < 0) return false;
  await page.click(`.el-select-dropdown__item >> nth=${idx}`, { label: '选择选项' });
  return true;
}
async function submitDialog() {
  await page.click('.el-dialog button:has-text("确 定")', { label: '提交表单' });
  await page.waitForFunction(() => !document.querySelector('.el-dialog')?.getClientRects()?.length ||
                                   document.body.innerText.includes('操作成功'),
                             undefined, { timeout: 10000 }).catch(() => {});
}

const task = await taskSpace(`M2 browser verification ${Date.now()}`);
const page = task.page('p1');
await page.goto(front, { timeout: 60000 });
await page.waitForLoadState('load');

// ---- 真实登录：清空会话，从登录页用账号密码登录（不再注入 Token）----
await page.evaluate(() => {
  localStorage.clear();
  document.cookie.split(';').forEach(c => {
    const n = c.split('=')[0].trim();
    if (n) document.cookie = n + '=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/';
  });
});
await page.goto(front, { timeout: 60000 });
await page.waitForSelector('input[placeholder="账号"]', { state: 'visible', timeout: 20000 }).catch(() => {});
await page.fill('input[placeholder="账号"]', 'admin');
await page.fill('input[placeholder="密码"]', 'admin123');
await page.click('text="登 录"', { label: '点击登录' });
await page.waitForFunction(() => !location.pathname.includes('login') && document.cookie.includes('Admin-Token='),
                           undefined, { timeout: 20000 }).catch(() => {});
let logged = await page.evaluate(() => !location.pathname.includes('login') && document.cookie.includes('Admin-Token='));
check('超管登录页真实登录(非Token注入)', logged, await page.url());
if (!logged) console.log('LOGIN_FAIL_SNAPSHOT\n' + (await page.snapshot()).slice(0, 3000));
await page.goto(front + '/index', { timeout: 60000 });
await page.waitForTimeout(3000);
await shot('01-admin-home');

// ---- 五页：加载/搜索/重置/翻页/改每页条数 ----
const pages = [
  { key: 'store', name: '店铺管理', path: '/noon-masterdata/store', searchPlaceholder: '店铺编号', searchValue: 'SHARED', expectRows: 1 },
  { key: 'site', name: '站点管理', path: '/noon-masterdata/site', searchPlaceholder: '所属店铺ID', searchValue: '2', expectRows: 2 },
  { key: 'warehouse', name: '仓库管理', path: '/noon-masterdata/warehouse', searchPlaceholder: '仓库编码', searchValue: 'WH-AE', expectRows: 1 },
  { key: 'sku', name: 'SKU 管理', path: '/noon-masterdata/sku', searchPlaceholder: 'SKU编码', searchValue: 'SKU-E2E-1', expectRows: 1 },
  { key: 'partnerSku', name: 'Partner SKU 映射', path: '/noon-masterdata/partner-sku', searchPlaceholder: 'Partner SKU', searchValue: 'PS-E2E-1', expectRows: 1 },
];

for (const p of pages) {
  await page.goto(front + p.path, { timeout: 60000 });
  await page.waitForTimeout(3500);
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  const before = await tableRows();
  if (before.length === 0) console.log(`PAGE_SNAPSHOT_${p.key}\n` + (await page.snapshot()).slice(0, 2500));
  check(`${p.name} 页面加载`, before.length > 0, `rows=${before.length} total=${await totalText()}`);
  await shot(`02-${p.key}-list`);

const SEARCH_SCOPE = '.app-container > form ';
const inputSel = SEARCH_SCOPE + `input[placeholder*="${p.searchPlaceholder}"]`;
await page.fill(inputSel, p.searchValue);
  await page.click('button:has-text("搜索")', { label: '点击搜索' });
  await waitRows(p.expectRows);
  const after = await tableRows();
  const tt = await totalText();
  check(`${p.name} 搜索条件生效且 total 正确`, after.length === p.expectRows && tt.includes(String(p.expectRows)),
        `rows=${after.length} totalText=${tt}`);
  await shot(`03-${p.key}-search`);

  await page.click('button:has-text("重置")', { label: '点击重置' });
  await page.waitForFunction((n) => [...document.querySelectorAll('.el-table__body-wrapper tbody tr')].length >= n,
                             before.length, { timeout: 10000 }).catch(() => {});
  check(`${p.name} 重置恢复列表`, (await tableRows()).length >= before.length,
        `rows=${(await tableRows()).length}`);

  const pager2 = '.el-pager li:nth-child(2)';
  const hasPage2 = await page.evaluate(() => !!document.querySelector('.el-pager li:nth-child(2)'));
  if (hasPage2) {
    const firstBefore = (await tableRows())[0] || '';
    await page.click(pager2, { label: '翻到第2页' });
    await page.waitForFunction((prev) => {
      const tr = document.querySelector('.el-table__body-wrapper tbody tr');
      return tr && tr.innerText.replace(/\s+/g, ' ').trim() !== prev;
    }, firstBefore, { timeout: 10000 }).catch(() => {});
    const firstAfter = (await tableRows())[0] || '';
    check(`${p.name} 翻页数据变化且页码生效`, firstAfter !== firstBefore,
          `first_before=${firstBefore.slice(0, 40)} first_after=${firstAfter.slice(0, 40)}`);
    await shot(`04-${p.key}-page2`);
  } else {
    check(`${p.name} 翻页失败(预期有第二页却无)`, false, 'no second page');
  }

  const totalBefore = await totalText();
  const sizeSel = '.el-pagination__sizes .el-select';
  if (await page.evaluate((s) => !!document.querySelector(s), sizeSel)) {
    await page.click(sizeSel, { label: '打开每页条数下拉' });
    const opts = await page.evaluate(() => [...document.querySelectorAll('.el-select-dropdown__item')]
      .map(e => e.textContent.trim()).filter(t => /20条\/页|50条\/页/.test(t)));
    if (opts.length) {
      await page.click(`text="${opts[0]}"`, { label: '选择20条每页' });
      await page.waitForFunction((t) => document.querySelector('.el-pagination__total')?.textContent?.trim() === t,
                                 totalBefore, { timeout: 10000 }).catch(() => {});
      check(`${p.name} 改每页条数 total 不变且回到第1页`, await totalText() === totalBefore,
            `before=${totalBefore} after=${await totalText()}`);
      await shot(`05-${p.key}-size`);
    } else {
      check(`${p.name} 改每页条数下拉无可用项`, false, 'no 20/50 option');
    }
  } else {
    check(`${p.name} 无每页条数选择器`, false, 'missing size select');
  }
}

// ---- 五页真实表单：新增 / 修改 / 启停（全部 UI 操作，无浏览器内 POST）----
const crud = [
  { key: 'store', name: '店铺管理', path: '/noon-masterdata/store', search: 'STORE-BROWSER-NEW',
    searchPlaceholder: '店铺编号',
    add: [{ t: 'input', l: '店铺编号', v: 'STORE-BROWSER-NEW' }, { t: 'input', l: 'Partner账号ID', v: '1' }, { t: 'select', l: '状态', v: '启用' }],
    update: [{ t: 'input', l: '英文名称', v: 'Browser Updated' }], verify: 'Browser Updated' },
  { key: 'site', name: '站点管理', path: '/noon-masterdata/site', search: '3',
    searchPlaceholder: '所属店铺ID',
    add: [{ t: 'select', l: '站点编码', v: 'AE 阿联酋' }, { t: 'input', l: '所属店铺ID', v: '3' },
          { t: 'input', l: '国家', v: 'UAE-BROWSER' }, { t: 'input', l: '币种', v: 'AED' }, { t: 'select', l: '状态', v: '启用' }],
    update: [{ t: 'input', l: '国家', v: 'UAE-UPDATED' }], verify: 'UAE-UPDATED' },
  { key: 'warehouse', name: '仓库管理', path: '/noon-masterdata/warehouse', search: 'WH-BROWSER-NEW',
    searchPlaceholder: '仓库编码',
    add: [{ t: 'input', l: '仓库编码', v: 'WH-BROWSER-NEW' }, { t: 'select', l: '站点', v: 'AE' },
          { t: 'input', l: '仓库名称', v: 'Browser WH' }, { t: 'select', l: '仓库类型', v: '本地仓' },
          { t: 'select', l: '履约模式', v: 'FBN' }, { t: 'select', l: '状态', v: '启用' }],
    update: [{ t: 'input', l: '仓库名称', v: 'Browser WH Updated' }], verify: 'Browser WH Updated' },
  { key: 'sku', name: 'SKU 管理', path: '/noon-masterdata/sku', search: 'SKU-BROWSER-NEW',
    searchPlaceholder: 'SKU编码',
    add: [{ t: 'input', l: 'SKU编码', v: 'SKU-BROWSER-NEW' }, { t: 'input', l: '英文名', v: 'Browser SKU' }, { t: 'select', l: '状态', v: '启用' }],
    update: [{ t: 'input', l: '英文名', v: 'Browser SKU Updated' }], verify: 'Browser SKU Updated' },
  { key: 'partnerSku', name: 'Partner SKU 映射', path: '/noon-masterdata/partner-sku', search: 'PS-BROWSER-NEW',
    searchPlaceholder: 'Partner SKU',
    add: [{ t: 'input', l: 'Partner SKU', v: 'PS-BROWSER-NEW' }, { t: 'input', l: 'SKU ID', v: '1' },
          { t: 'select', l: '站点', v: 'AE' }, { t: 'input', l: '业务模式', v: 'noon' }, { t: 'select', l: '状态', v: '启用' }],
    update: [{ t: 'input', l: '条形码', v: 'BROWSER-BARCODE' }], verify: 'BROWSER-BARCODE' },
];

for (const c of crud) {
  await page.goto(front + c.path, { timeout: 60000 });
  await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
  // 新增
  await page.click('button:has-text("新增")', { label: '打开新增弹窗' });
  await page.waitForSelector('.el-dialog', { state: 'visible', timeout: 10000 }).catch(() => {});
  let ok = true;
  for (const f of c.add) {
    const done = f.t === 'select' ? await pickSelect(f.l, f.v) : await fillField(f.l, f.v);
    if (!done) { ok = false; console.log(`ADD_FIELD_FAIL ${c.key} ${f.l}`); break; }
  }
  if (ok) {
    await submitDialog();
    await page.waitForTimeout(700);
  }
  // 提交成功的可靠信号：弹窗自动关闭。若仍开着说明校验/提交失败，记为失败并跳过该页后续。
  ok = ok && !(await page.evaluate(() => !!document.querySelector('.el-dialog')?.getClientRects()?.length));
  check(`${c.name} 真实表单新增`, ok, `search=${c.search}`);
  await shot(`11-${c.key}-add`);
  if (!ok) { console.log(`ADD_FAIL_SNAPSHOT_${c.key}\n` + (await page.snapshot()).slice(0, 2500)); continue; }

  // 搜索定位新增行
  await page.fill(`.app-container > form input[placeholder*="${c.searchPlaceholder}"]`, c.search);
  await page.click('button:has-text("搜索")', { label: '搜索新增行' });
  await page.waitForTimeout(1500);
  const found = (await tableRows()).length > 0;
  check(`${c.name} 新增行可搜索回读`, found, `rows=${(await tableRows()).length}`);
  if (!found) { console.log(`SEARCH_FAIL_SNAPSHOT_${c.key}\n` + (await page.snapshot()).slice(0, 2500)); continue; }

  // 修改
  await page.click('.el-table__body-wrapper tbody tr button:has-text("修改") >> nth=0', { label: '点击行内修改' });
  await page.waitForSelector('.el-dialog', { state: 'visible', timeout: 10000 }).catch(() => {});
  let upd = true;
  for (const f of c.update) {
    const done = f.t === 'select' ? await pickSelect(f.l, f.v) : await fillField(f.l, f.v);
    if (!done) { upd = false; console.log(`UPDATE_FIELD_FAIL ${c.key} ${f.l}`); break; }
  }
  if (upd) await submitDialog();
  await page.waitForTimeout(1000);
  let newText = await bodyText();
  if (!newText.includes(c.verify)) {
    // 重新搜索确认（列表可能已刷新）
    await page.fill(`.app-container > form input[placeholder*="${c.searchPlaceholder}"]`, c.search);
    await page.click('button:has-text("搜索")', { label: '重新搜索确认修改' });
    await page.waitForTimeout(1200);
    newText = await bodyText();
  }
  check(`${c.name} 真实表单修改并回读`, upd && newText.includes(c.verify), `verify=${c.verify}`);
  await shot(`12-${c.key}-update`);

  // 启停
  await page.fill(`.app-container > form input[placeholder*="${c.searchPlaceholder}"]`, c.search);
  await page.click('button:has-text("搜索")', { label: '搜索待停用行' });
  await page.waitForTimeout(1200);
  await page.click('.el-table__body-wrapper tbody tr button:has-text("停用") >> nth=0', { label: '点击停用' });
  await page.waitForFunction(() => [...document.querySelectorAll('.el-table__body-wrapper tbody tr .el-tag')]
                                  .some(e => e.textContent.trim() === '停用'),
                             undefined, { timeout: 10000 }).catch(() => {});
  const stopped = await page.evaluate(() => [...document.querySelectorAll('.el-table__body-wrapper tbody tr .el-tag')]
    .some(e => e.textContent.trim() === '停用'));
  check(`${c.name} 真实UI启停操作生效`, stopped, '');
  await shot(`13-${c.key}-status`);
}

// ---- 授权边界：viewer 真实登录，只读/拒绝；opAE 真实登录，跨站数据隔离 ----
async function switchUser(name, password) {
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
  await page.click('text="登 录"', { label: '切换用户登录' });
  await page.waitForFunction(() => !location.pathname.includes('login') && document.cookie.includes('Admin-Token='),
                             undefined, { timeout: 20000 }).catch(() => {});
  return await page.evaluate(() => !location.pathname.includes('login') && document.cookie.includes('Admin-Token='));
}

check('viewer 登录页真实登录', await switchUser('viewer', 'admin123'), '');
await page.goto(front + '/noon-masterdata/store', { timeout: 60000 });
await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
const viewerRows = await tableRows();
check('viewer 店铺页零行且无新增按钮',
      viewerRows.length === 0 && await page.evaluate(() => ![...document.querySelectorAll('button')].some(b => b.textContent.includes('新增'))),
      `rows=${viewerRows.length} total=${await totalText()}`);
await shot('07-viewer-store-denied');
await page.goto(front + '/noon-masterdata/sku', { timeout: 60000 });
await page.waitForTimeout(2000);
const viewerSku = await bodyText();
check('viewer SKU 页无授权被拒绝', (await tableRows()).length === 0 && /无权限|没有权限|403|授权/.test(viewerSku),
      `rows=${(await tableRows()).length} msg=${viewerSku.slice(0, 120)}`);
await shot('08-viewer-sku-denied');

check('opAE 登录页真实登录', await switchUser('opAE', 'admin123'), '');
await page.goto(front + '/noon-masterdata/store', { timeout: 60000 });
await page.waitForSelector('.el-table', { state: 'visible', timeout: 20000 }).catch(() => {});
await page.waitForTimeout(1500);
const aeRows = await tableRows();
const aeTotal = await totalText();
// 用页面内 fetch 读取当前登录态下后端真实授权总数，与 UI 显示比对（避免用 CRUD 前的旧快照）
const aeBackend = await page.evaluate(async () => {
  const r = await fetch('/noon/masterdata/store/list?limit=1&offset=0', {
    headers: { 'Authorization': 'Bearer ' + (document.cookie.match(/Admin-Token=([^;]+)/)?.[1] || '') }
  });
  const j = await r.json();
  return j?.data?.total;
});
const expectAe = Number(aeBackend || 0);
check('opAE 店铺页跨站数据隔离(UI与后端授权口径一致)',
      expectAe > 0 && aeRows.length === Math.min(expectAe, 10) && aeTotal.includes(String(expectAe)),
      `ui_rows=${aeRows.length} ui_total=${aeTotal} backend_total=${expectAe}`);
await shot('14-opAE-store-scoped');

await fs.writeFile(`${out}/browser-results.txt`, results.join('\n') + '\n', 'utf8');
await task.finish({ keep: [] });
console.log('BROWSER_RESULT_PASS=' + results.filter(r => r.startsWith('PASS')).length);
console.log('BROWSER_RESULT_FAIL=' + results.filter(r => r.startsWith('FAIL')).length);
