package com.yx.noon.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

/**
 * Noon → 本地 SKU/Partner SKU 同步编排。仅 AE 站点；先读取平台真实清单与 Offer，
 * 再在一个事务内落库。单个商品失败不会中断整批，失败数单独返回供定时任务日志核查。
 */
@Service
public class NoonSkuSyncService
{
    private final NoonApiClient client;
    private final NoonSkuSyncWriter writer;

    public NoonSkuSyncService(NoonApiClient client, NoonSkuSyncWriter writer)
    {
        this.client = client;
        this.writer = writer;
    }

    public NoonSkuSyncResult sync(String siteCode)
    {
        String site = siteCode == null || siteCode.isBlank() ? "AE" : siteCode.trim().toUpperCase(Locale.ROOT);
        if (!"AE".equals(site))
            throw new IllegalArgumentException("Noon SKU 同步当前仅支持 AE 站点，收到: " + siteCode);

        List<NoonCatalogSku> catalog = client.listPartnerSkus(site);
        if (catalog.isEmpty())
            return NoonSkuSyncResult.empty(site, "Noon 导出清单为空，未写入任何数据");

        List<NoonOfferInfo> offers = new ArrayList<>();
        int skipped = 0;
        int failed = 0;
        for (NoonCatalogSku row : catalog)
        {
            try
            {
                NoonOfferInfo offer = client.getOffer(row.partnerSku());
                if (offer == null)
                {
                    skipped++;
                    continue;
                }
                offers.add(new NoonOfferInfo(
                        offer.partnerSku(), offer.noonSku(), row.barcode(), offer.title(), offer.brand(),
                        offer.offerCode(), offer.isActive(), offer.liveStatus(),
                        offer.issuesJson(), offer.priceAmount(), offer.currency(), offer.imageUrl()));
            }
            catch (RuntimeException e)
            {
                failed++;
            }
        }

        if (offers.isEmpty())
            return new NoonSkuSyncResult(site, catalog.size(), 0, 0, 0, 0, 0,
                    skipped, failed, "未取得 AE Offer，未写入任何数据");

        NoonSkuSyncWriter.Counters counters = writer.persist(site, offers);
        return new NoonSkuSyncResult(site, catalog.size(), offers.size(),
                counters.skuCreated(), counters.skuUpdated(), counters.mappingCreated(), counters.mappingUpdated(),
                skipped, failed, "同步完成");
    }
}
