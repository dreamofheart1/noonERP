package com.yx.noon.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Noon Partner API 连接参数（不含任何密钥）。密钥文件按路径在运行时读取，仓库不保存。
 */
@Component
@ConfigurationProperties(prefix = "noon.api")
public class NoonApiProperties
{
    /** API 网关根地址。 */
    private String baseUrl = "https://noon-api-gateway.noon.partners";
    /** 凭据 JSON 路径，包含 key_id/private_key/project_code；相对路径基于进程工作目录。 */
    private String credentialsPath = "config/secrets/noon_credentials_sensitive.json";
    /** 所有 Noon 请求必须带 User-Agent。 */
    private String userAgent = "NoonErpSync/1.0";
    /** 只同步 UAE 站点，导出参数使用 Noon 国家码。 */
    private String country = "AE";
    /** 批量导出类目：FBN 库存台账摘要含 partner_sku / sku / partner_barcode。 */
    private String exportCategory = "fbn_inventoryv2_ledgersummaryview";
    /** 导出任务轮询间隔（毫秒）。 */
    private long pollIntervalMs = 3000L;
    /** 导出任务最多轮询次数，防止任务卡住。 */
    private int maxPollAttempts = 30;
    /** 单个 Offer API 超时（秒）。 */
    private int offerTimeoutSeconds = 20;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getCredentialsPath() { return credentialsPath; }
    public void setCredentialsPath(String credentialsPath) { this.credentialsPath = credentialsPath; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getExportCategory() { return exportCategory; }
    public void setExportCategory(String exportCategory) { this.exportCategory = exportCategory; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public int getMaxPollAttempts() { return maxPollAttempts; }
    public void setMaxPollAttempts(int maxPollAttempts) { this.maxPollAttempts = maxPollAttempts; }

    public int getOfferTimeoutSeconds() { return offerTimeoutSeconds; }
    public void setOfferTimeoutSeconds(int offerTimeoutSeconds) { this.offerTimeoutSeconds = offerTimeoutSeconds; }
}
