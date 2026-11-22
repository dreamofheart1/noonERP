package com.yx.noon.inventory;

import java.time.LocalDateTime;

/**
 * 入库单列表/详情头（契约 §3）。{@code inv_inbound} 无 site_code 列，
 * {@code siteCode} 由所属仓库 {@code md_warehouse.site_code} 带出（授权口径同源）。
 */
public record InboundRow(Long id, String inboundNo, Long purOrderId, Long warehouseId, String status,
                         String siteCode, Integer itemCount, LocalDateTime createdAt)
{
}
