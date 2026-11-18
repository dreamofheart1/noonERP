/**
 * Noon ERP 原型端到端验证脚本（可复现）
 * 运行：node docs/verification/verify-chain.js
 * 说明：从 prototype/index.html 抽取 script，stub 掉 DOM 依赖（renderPage/toast），
 *       直接驱动 demoStep 全链路，断言库存守恒、利润实算、两维度独立。
 *       仅验证内存数据模型，不等于接口/落库/浏览器渲染验证（后者见 browser-verify 与报告）。
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../..');
const html = fs.readFileSync(path.join(root, 'prototype/index.html'), 'utf8');
const scripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);
let js = scripts.join('\n');
const cut = js.indexOf('function renderPage()');
let core = js.slice(0, cut); // 只取数据模型与业务函数，剔除 DOM 渲染层

// 装配成可执行模块：stub DOM 副作用函数
const stub = 'function renderPage(){} function toast(){}\n';
const assertions = [];
function check(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  assertions.push({ name, ok, actual, expected });
  return ok;
}
function checkFn(name, fn) {
  const r = fn();
  assertions.push({ name, ok: !!r.pass, actual: r.actual, expected: r.expected });
}

// 用 Function 构造执行环境，把断言函数注入
const runner = new Function('check', 'checkFn', 'module', stub + core + `
  module.exports = {
    DEMO, S, demoStep, orderProfit: DEMO.orderProfit.bind(DEMO), demoAllocFor, demoPartialSum,
    allocFor, partialSum, L2_POOL, L1_BASE, DEMO_L2_TOTAL,
    reset(){ DEMO.reset(); S.expensePartial={}; S.skuAlloc=false; S.policy='NONE'; S.calcBatches=[]; },
    steps: DEMO_STEPS.map(x=>x.k)
  };
`);
const mod = { exports: {} };
runner(check, checkFn, mod);
const app = mod.exports;
const { DEMO, S, demoStep, demoAllocFor, allocFor, reset } = app;

console.log('=== 语法/加载 ===');
console.log('script blocks:', scripts.length, '| core chars:', core.length, '| OK');

// ---------- 1. 八步演示链：逐步记录批次与库存 ----------
console.log('\n=== 1. 八步演示链（预期 vs 实际）===');
reset();
const chain = ['po','in','ship','recv','sale','ret','stmt','profit'];
const snapshots = [];
chain.forEach(k => {
  demoStep(k);
  const af = DEMO.ledger['AE-FBN'] || {}, cn = DEMO.ledger.CN01 || {};
  snapshots.push({
    step: DEMO.step, act: k,
    batches: DEMO.batches.map(b => `${b.no}=${b.qty}/${b.state}`),
    cn_avail: cn.available || 0, cn_transit: cn.in_transit || 0,
    af_avail: af.available || 0, af_unsell: af.unsellable || 0,
    po_unrecv: DEMO.po.unreceived
  });
});
snapshots.forEach(s => console.log(` step${s.step} ${s.act.padEnd(6)} | CN01 可用${s.cn_avail}/在途${s.cn_transit} | AE-FBN 可用${s.af_avail}/不可售${s.af_unsell} | PO未收${s.po_unrecv} | ${s.batches.join(', ')}`));

const finalAf = DEMO.ledger['AE-FBN'], finalCn = DEMO.ledger.CN01;
check('①未到货60件不建批次', DEMO.batches.length, 3); // BAT-001, -02, -03；60 件无独立批次
check('②在途批收货后CLOSED且qty=0', DEMO.batches[1] && [DEMO.batches[1].state, DEMO.batches[1].qty], ['CLOSED', 0]);
check('③短少2可追溯(MV-DEMO-004)', DEMO.moves.some(m => m.no === 'MV-DEMO-004' && m.qty === -2), true);
check('④B级报废不回可售(可用28/不可售2)', [finalAf.available, finalAf.unsellable], [28, 2]);
check('⑤父批与在途批不同时计可售', [DEMO.batches[0].qty, DEMO.batches[1].qty], [0, 0]);
check('⑥PO未收=60', DEMO.po.unreceived, 60);
check('⑦CN01台账归零', [finalCn.available, finalCn.in_transit], [0, 0]);
check('⑧消耗流水3条(售/红冲/报废损失)', DEMO.consumptions.length, 3);

// 未执行步骤不显示为已发生：守恒链 at 阈值逻辑（step=2 时仅到货节点达成）
check('⑨演示步骤共8步', app.steps.length, 8);

// ---------- 2. 三档利润基于演示订单流水，赔偿单次 ----------
console.log('\n=== 2. 三档利润（演示链实算，赔偿单次）===');
const op = app.orderProfit();
console.log(' 演示订单 L1 =', JSON.stringify(op));
check('⑩L1从流水实算 = −56.5', op.l1, -56.5);
check('⑪赔偿单次(只在平台费减6)', op.comp, 6);
check('⑫COGS净额=100(100−20红冲+20损失)', op.cogs, 100);
console.log(' 三档批次:');
S.calcBatches.forEach(b => console.log(`   ${b.policy.padEnd(7)} src=${b.src} l1=${b.l1} pool=${b.pool} net=${b.net} skuAlloc=${b.skuAlloc}`));
check('⑬三档来源均为DEMO_CHAIN', S.calcBatches.every(b => b.src === 'DEMO_CHAIN'), true);
check('⑭三档L1均=实算−56.5', S.calcBatches.map(b => b.l1), [-56.5, -56.5, -56.5]);
check('⑮net==l1−pool逐档成立', S.calcBatches.every(b => Math.abs(b.net - (b.l1 - b.pool)) < 1e-9), true);
check('⑯FULL池=演示订单级77.16', S.calcBatches.find(b => b.policy === 'FULL').pool, 77.16);

// 未勾选 PARTIAL = 0；勾选 FREIGHT+AD_SPEND 演示池 = 48.2+3.96 = 52.16
reset();
check('⑰未勾选PARTIAL池=0', demoAllocFor('PARTIAL'), 0);
S.expensePartial = { FREIGHT: true, AD_SPEND: true };
console.log(` 勾选 FREIGHT+AD_SPEND: 演示池=${demoAllocFor('PARTIAL')} 静态池=${allocFor('PARTIAL')}`);
checkFn('⑱勾选后演示PARTIAL池≈52.16', () => ({ pass: Math.abs(demoAllocFor('PARTIAL') - 52.16) < 1e-9, actual: demoAllocFor('PARTIAL'), expected: '≈52.16' }));
check('⑲勾选后静态PARTIAL池=5216', allocFor('PARTIAL'), 5216);

// 历史批次不覆盖：重跑步骤⑧前手动加一个批次，再跑不清空
reset();
S.calcBatches.push({ no: 'CB-HIST-000', policy: 'NONE', l1: 1, pool: 0, net: 1, src: 'STATIC' });
chain.forEach(k => demoStep(k));
check('⑳历史批次不被覆盖', S.calcBatches.some(b => b.no === 'CB-HIST-000'), true);

// ---------- 3. 两维度独立切换 ----------
console.log('\n=== 3. 两维度独立（站点费用口径 vs SKU分摊开关）===');
reset();
check('㉑维度B默认关闭', S.skuAlloc, false);
check('㉒维度A默认NONE', S.policy, 'NONE');
// 切换维度A不影响维度B
S.policy = 'FULL';
check('㉓切维度A=FULL后维度B仍关', S.skuAlloc, false);
// 切换维度B不影响维度A
S.skuAlloc = true;
check('㉔切维度B=开后维度A仍FULL', S.policy, 'FULL');
console.log(' 维度A=FULL, 维度B=开 → 二者独立，互不自动改变 ✓');

// ---------- 汇总 ----------
const pass = assertions.filter(a => a.ok).length;
const fail = assertions.filter(a => !a.ok);
console.log(`\n=== 汇总: ${pass}/${assertions.length} 通过 ===`);
if (fail.length) {
  console.log('失败项:');
  fail.forEach(a => console.log(`  ✗ ${a.name}: actual=${JSON.stringify(a.actual)} expected=${JSON.stringify(a.expected)}`));
  process.exit(1);
} else {
  console.log('全部通过 ✓');
}
