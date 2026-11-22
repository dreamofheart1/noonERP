package com.yx.noon.purchasing;

/**
 * 采购单列表筛选（契约 4.3）：orderNo 模糊、supplierId 精确、siteCode 与授权闸门取交集、
 * status 五态之一、source 三值之一；空白不过滤。
 */
public record OrderQuery(String orderNo, Long supplierId, String siteCode, String status, String source)
{
}
