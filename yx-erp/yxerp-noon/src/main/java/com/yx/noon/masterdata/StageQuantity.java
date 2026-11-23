package com.yx.noon.masterdata;

import java.util.List;

/** 一个有真实来源的阶段数量；docs 为该数量的单据/仓库明细。 */
public record StageQuantity(String stage, String label, int qty, String source, List<QuantityDoc> docs)
{
}
