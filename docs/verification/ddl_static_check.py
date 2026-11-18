#!/usr/bin/env python3
"""
Noon ERP schema v0.4 静态校验（可复现）
运行：python3 docs/verification/ddl_static_check.py
说明：本机无 MySQL/Docker/sqlglot，无法实际执行 DDL。此脚本只做静态一致性检查：
      括号平衡、表/视图数量与 PRD 对照、主键存在、唯一键/普通索引字段是否在表内存在、
      视图引用的表是否存在、初始化 INSERT 字段是否与表字段匹配。
      通过 ≠ DDL 可在 MySQL 8 执行；仅覆盖语法结构与引用一致性。
"""
import re, sys

SQL_PATH = 'docs/noon-erp-schema-v0.4.sql'
sql = open(SQL_PATH, encoding='utf-8').read()
results = []
def check(name, ok, detail=''):
    results.append((name, bool(ok), detail))

# 1. 括号平衡
check('括号平衡 ()', sql.count('(') == sql.count(')'),
      f"左={sql.count('(')} 右={sql.count(')')}")

# 2. 表/视图数量
tables = re.findall(r'CREATE TABLE IF NOT EXISTS\s+(\w+)\s*\(', sql)
views = re.findall(r'CREATE OR REPLACE VIEW\s+(\w+)\s+AS', sql)
check('表数量=45', len(tables) == 45, f'实际={len(tables)}')
check('视图数量=4', len(views) == 4, f'实际={len(views)}')
check('无重名表', len(tables) == len(set(tables)), '')

# 3. 每张表有主键、有 ENGINE、每列有类型
for t in tables:
    m = re.search(rf'CREATE TABLE IF NOT EXISTS {t} \((.*?)\n\) ENGINE', sql, re.S)
    body = m.group(1) if m else ''
    check(f'{t}: 有 PRIMARY KEY', 'PRIMARY KEY' in body)
    check(f'{t}: 有 ENGINE=InnoDB', bool(re.search(rf'CREATE TABLE IF NOT EXISTS {t}.*?ENGINE=InnoDB', sql, re.S)))

# 4. 唯一键/KEY 引用字段在表内存在（粗略：字段名出现在 body）
for t in tables:
    m = re.search(rf'CREATE TABLE IF NOT EXISTS {t} \((.*?)\n\) ENGINE', sql, re.S)
    body = m.group(1) if m else ''
    cols = set(c for c, _ in re.findall(r'^\s*(\w+)\s+(BIGINT|INT|TINYINT|VARCHAR|CHAR|DECIMAL|DATETIME|DATE|JSON|TEXT)', body, re.M))
    for km in re.finditer(r'(UNIQUE KEY|KEY)\s+\w+\s*\(([^)]+)\)', body):
        for col in re.findall(r'`?(\w+)`?', km.group(2)):
            col = col.strip()
            if col and col not in cols and not col.isdigit():
                # 支持前缀索引 col(10)
                check(f'{t}: 索引字段 {col} 存在', False, f'未在列定义中找到')

# 5. 视图引用表存在
all_objs = set(tables) | set(views)
for v in views:
    m = re.search(rf'CREATE OR REPLACE VIEW {v} AS(.*?);', sql, re.S)
    body = m.group(1) if m else ''
    for ref in re.findall(r'\b(FROM|JOIN)\s+(\w+)', body):
        check(f'{v}: 引用表 {ref[1]} 存在', ref[1] in all_objs)

# 6. 初始化 INSERT 表存在、字段匹配
for ins in re.finditer(r'INSERT INTO\s+(\w+)\s*\(([^)]+)\)', sql):
    t, cols = ins.group(1), [c.strip() for c in ins.group(2).split(',')]
    check(f'INSERT 目标表 {t} 存在', t in tables)
    m = re.search(rf'CREATE TABLE IF NOT EXISTS {t} \((.*?)\n\) ENGINE', sql, re.S)
    if m:
        defcols = set(c for c, _ in re.findall(r'^\s*(\w+)\s+(BIGINT|INT|TINYINT|VARCHAR|CHAR|DECIMAL|DATETIME|DATE|JSON|TEXT)', m.group(1), re.M))
        for c in cols:
            check(f'INSERT {t}.{c} 字段存在', c in defcols)

# 汇总
passed = sum(1 for _, ok, _ in results if ok)
failed = [(n, d) for n, ok, d in results if not ok]
print(f'静态校验: {passed}/{len(results)} 通过')
if failed:
    print('失败项:')
    for n, d in failed:
        print(f'  ✗ {n} {d}')
    sys.exit(1)
else:
    print('全部通过 ✓（注意：静态通过 ≠ MySQL 8 实际可执行）')
