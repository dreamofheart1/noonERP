package com.yx.noon.transfer;

import java.time.LocalDateTime;

/**
 * 调拨单列表/详情头（契约 §3）。{@code inv_transfer} 无 site_code 列，{@code siteCode} 由源仓
 * {@code md_warehouse.site_code} 带出（授权口径同源；目标仓与源仓必须同站点，发货时强校验）。
 */
public record TransferRow(Long id, String transferNo, Long fromWarehouseId, Long toWarehouseId,
                          String status, String siteCode, Integer itemCount, LocalDateTime createdAt)
{
}
