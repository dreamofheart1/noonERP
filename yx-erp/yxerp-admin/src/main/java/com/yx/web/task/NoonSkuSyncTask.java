package com.yx.web.task;

import com.yx.noon.sync.NoonSkuSyncResult;
import com.yx.noon.sync.NoonSkuSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 若依定时任务：Noon AE 商品同步。
 * 在「系统管理 → 定时任务」中新建，任务名称任意，调用目标填：
 *   noonSkuSyncTask.syncAe()
 * 无参、无登录上下文；执行结果写入若依 sys_job_log。
 */
@Component("noonSkuSyncTask")
public class NoonSkuSyncTask
{
    private static final Logger log = LoggerFactory.getLogger(NoonSkuSyncTask.class);

    private final NoonSkuSyncService service;

    public NoonSkuSyncTask(NoonSkuSyncService service)
    {
        this.service = service;
    }

    public void syncAe()
    {
        sync("AE");
    }

    /** 兼容带参调用方式：noonSkuSyncTask.sync('AE')。 */
    public void sync(String siteCode)
    {
        NoonSkuSyncResult result = service.sync(siteCode);
        log.info("Noon SKU 同步完成 site={} 清单={} Offer={} SKU新增={} SKU更新={} 映射新增={} 映射更新={} 跳过={} 失败={} 说明={}",
                result.siteCode(), result.catalogSkus(), result.offersFound(),
                result.skuCreated(), result.skuUpdated(), result.mappingCreated(), result.mappingUpdated(),
                result.skipped(), result.failed(), result.message());
    }
}
