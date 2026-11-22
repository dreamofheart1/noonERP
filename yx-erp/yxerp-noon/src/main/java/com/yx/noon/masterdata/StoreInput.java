package com.yx.noon.masterdata;

/** 店铺写入入参（字段名对应 md_store 列 camelCase）。契约 v1 未含 id，更新走 update(long, StoreInput)。 */
public record StoreInput(String storeNo, Long partnerId, String nameEn, String nameAr,
                         String legalEntity, String status)
{
}
