package com.yx.web.task;

import com.yx.noon.sync.NoonSellerLabSyncResult;
import com.yx.noon.sync.NoonSellerLabSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 若依定时任务：Seller Lab 只读数据同步（FBN 库存 / Sales；ASN 暂缓）。
 * 默认不在代码里自动高频执行；注册后由用户在「系统管理 → 定时任务」自行启用。
 * 调用目标示例：noonSellerLabTask.syncFbnAe()、noonSellerLabTask.syncSalesAe('2026-11-01','2026-11-30')
 */
@Component("noonSellerLabTask")
public class NoonSellerLabTask
{
    private static final Logger log = LoggerFactory.getLogger(NoonSellerLabTask.class);

    private final NoonSellerLabSyncService service;

    public NoonSellerLabTask(NoonSellerLabSyncService service)
    {
        this.service = service;
    }

    public void syncFbnAe() { syncFbn("AE"); }
    public void syncFbnSa() { syncFbn("SA"); }

    public void syncAsnAe() { syncAsn("AE"); }
    public void syncAsnSa() { syncAsn("SA"); }

    public void syncAsn(String siteCode)
    {
        NoonSellerLabSyncResult r = service.syncAsn(siteCode, 20);
        log.info("Seller Lab ASN 同步 site={} 平台行={} 写入={} 截断={}",
                r.siteCode(), r.platformRows(), r.writtenRows(), r.truncated());
    }

    public void syncFbn(String siteCode)
    {
        NoonSellerLabSyncResult r = service.syncFbnInventory(siteCode);
        log.info("Seller Lab FBN 同步 site={} 平台行={} 写入={}", r.siteCode(), r.platformRows(), r.writtenRows());
    }

    public void syncSalesAe(String fromDate, String toDate) { syncSales("AE", fromDate, toDate); }
    public void syncSalesSa(String fromDate, String toDate) { syncSales("SA", fromDate, toDate); }

    public void syncSales(String siteCode, String fromDate, String toDate)
    {
        NoonSellerLabSyncResult r = service.syncSales(siteCode, fromDate, toDate, 5);
        log.info("Seller Lab Sales 同步 site={} 平台行={} 写入={}", r.siteCode(), r.platformRows(), r.writtenRows());
    }
}
