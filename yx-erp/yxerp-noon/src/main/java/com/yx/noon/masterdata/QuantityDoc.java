package com.yx.noon.masterdata;

import java.util.List;

/**
 * 单据明细：让展开面板可追溯到具体采购单/ASN/仓库，而不是只给一个汇总数字。
 * docId 用于前端跳转，detail 为行级补充（如仓库名）。
 */
public record QuantityDoc(String docNo, Long docId, String detail, int qty)
{
}
