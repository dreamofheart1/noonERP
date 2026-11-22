package com.yx.noon.masterdata;

/** 店铺读模型（id + md_store 业务列，不含 DB 生成时间列）。 */
public record StoreRow(long id, String storeNo, Long partnerId, String nameEn, String nameAr,
                       String legalEntity, String status)
{
}
