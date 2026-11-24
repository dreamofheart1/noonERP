package com.yx.noon.sync;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Noon Seller Lab 内部只读查询客户端（FBN 库存 / Sales 销售明细；ASN 暂缓）。
 *
 * <p>严格只读：仅调用已观察到的查询接口，不做任何写操作。所有请求复用
 * {@link NoonApiClient} 的登录会话与同一个 {@link NoonRequestThrottle}，
 * 保证 3 秒间隔覆盖分页与两站，不并发绕过。</p>
 *
 * <p>非公开内部接口，正式支持与长期稳定性未确认，不得硬编码浏览器会话为生产凭据。</p>
 */
@Component
public class NoonSellerLabClient
{
    private static final String FBN_INVENTORY =
            "https://fbn.noon.partners/_vs/fn/sc-fbn-platform-api-dashboard/api/v5/seller-lab/fbn-inventory";
    private static final String SALES_LIST =
            "https://reports.noon.partners/_vs/mp/mp-inventory-health-api-sales-dashboard/sales-dashboard/list";
    private static final String ASN_LIST =
            "https://fbn-inbound.noon.partners/_vs/sc/sc-inbound-api-partner/v1/shipment/list";

    private static final Set<String> ALLOWED_SITES = Set.of("AE", "SA");

    private final NoonApiClient api;
    private final NoonRequestThrottle throttle;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private volatile boolean lastSalesTruncated = false;
    private volatile boolean lastAsnTruncated = false;

    public NoonSellerLabClient(NoonApiClient api, NoonRequestThrottle throttle)
    {
        this.api = api;
        this.throttle = throttle;
    }

    /** 站点白名单：平台对未知 Country-Code 会回退 SA，必须在 ERP 侧拒绝。 */
    public static String requireSite(String siteCode)
    {
        if (siteCode == null || !ALLOWED_SITES.contains(siteCode.trim().toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("Seller Lab 查询仅支持 AE/SA，收到: " + siteCode);
        return siteCode.trim().toUpperCase(Locale.ROOT);
    }

    /** 读取该站点 FBN 库存全部页（嵌套 pagination），返回平台原始行。 */
    public List<FbnInventoryRow> listFbnInventory(String siteCode)
    {
        String site = requireSite(siteCode);
        api.ensureLoggedIn(); // 会话复用；内部已受统一限速保护
        List<FbnInventoryRow> rows = new ArrayList<>();
        int page = 1;
        int totalPages = 1;
        while (page <= totalPages)
        {
            ObjectNode body = json.createObjectNode();
            body.put("inventory_tab_name", "all");
            body.set("filters", json.createObjectNode());
            ObjectNode pagination = json.createObjectNode();
            pagination.put("page", page);
            pagination.put("items_per_page", 20);
            body.set("pagination", pagination);

            JsonNode root = post(FBN_INVENTORY, site, body.toString());
            JsonNode arr = root.path("data").path("fbn_inventory");
            JsonNode pageInfo = root.path("pagination");
            // 合法空结果必须有 data.fbn_inventory 数组 + pagination 结构；
            // 缺字段/类型错误视为平台响应异常，不能当空列表落库。
            if (!arr.isArray() || !pageInfo.isObject()
                    || pageInfo.path("total_pages").isMissingNode()
                    || pageInfo.path("page").isMissingNode())
                throw new IllegalStateException("FBN 库存响应结构异常（site=" + site + ", page=" + page + "），未视为空结果");
            for (JsonNode n : arr) rows.add(FbnInventoryRow.from(n, site));
            totalPages = Math.max(1, pageInfo.path("total_pages").asInt(1));
            int returnedPage = pageInfo.path("page").asInt(-1);
            if (returnedPage != page)
                throw new IllegalStateException("FBN 库存分页未返回请求页码（请求=" + page + "，返回=" + returnedPage + "），停止以免留下不完整快照");
            page++;
        }
        return rows;
    }

    /** 读取该站点 Sales 明细（指定日期区间），按页返回；页结果会随时间变化，调用方需幂等。 */
    public List<SalesItemRow> listSales(String siteCode, String fromDate, String toDate, int maxPages)
    {
        String site = requireSite(siteCode);
        api.ensureLoggedIn();
        List<SalesItemRow> rows = new ArrayList<>();
        boolean truncated = true;
        for (int p = 1; p <= Math.max(1, maxPages); p++)
        {
            ObjectNode body = json.createObjectNode();
            body.put("country_code", site.toLowerCase(Locale.ROOT));
            body.put("from_date", fromDate);
            body.put("to_date", toDate);
            body.put("page", p);
            body.put("per_page", 20);
            body.set("filters", json.createObjectNode());

            JsonNode root = post(SALES_LIST, site, body.toString());
            JsonNode hits = root.path("hits");
            // 合法空结果必须有 hits 数组与 total；缺字段视为异常，不落不完整快照
            if (!hits.isArray() || root.path("total").isMissingNode())
                throw new IllegalStateException("Sales 响应结构异常（site=" + site + ", page=" + p + "），未视为空结果");
            for (JsonNode n : hits) rows.add(SalesItemRow.from(n, site));
            if (hits.size() < 20)
            {
                truncated = false; // 已取完最后一页
                break;
            }
        }
        // 达到 maxPages 且最后一页仍是满页 → 可能还有数据，标记为部分同步
        this.lastSalesTruncated = truncated;
        return rows;
    }

    /** 上次 Sales 同步是否因 maxPages 截断（用于前端明确提示部分同步）。 */
    public boolean isLastSalesTruncated() { return lastSalesTruncated; }

    /** 读取该站点 ASN 列表（next_token 分页）；平台编号与 ERP 内部编号分开保存。 */
    public List<PlatformAsnRow> listAsn(String siteCode, int maxPages)
    {
        String site = requireSite(siteCode);
        api.ensureLoggedIn();
        List<PlatformAsnRow> rows = new ArrayList<>();
        String nextToken = null;
        for (int p = 1; p <= Math.max(1, maxPages); p++)
        {
            StringBuilder url = new StringBuilder(ASN_LIST)
                    .append("?country_code=").append(site.toLowerCase(Locale.ROOT))
                    .append("&page_size=20");
            if (nextToken != null && !nextToken.isBlank())
                url.append("&next_token=").append(java.net.URLEncoder.encode(nextToken, StandardCharsets.UTF_8));

            JsonNode root = get(url.toString(), site);
            JsonNode shipments = root.path("shipments");
            if (!shipments.isArray())
                throw new IllegalStateException("ASN 响应结构异常（site=" + site + "），未视为空结果");
            for (JsonNode n : shipments) rows.add(PlatformAsnRow.from(n, site));
            JsonNode nt = root.path("next_token");
            nextToken = nt.isMissingNode() || nt.isNull() ? null : nt.asText();
            if (nextToken == null || nextToken.isBlank()) break;
        }
        this.lastAsnTruncated = nextToken != null && !nextToken.isBlank();
        return rows;
    }

    /** 上次 ASN 同步是否因 maxPages 截断。 */
    public boolean isLastAsnTruncated() { return lastAsnTruncated; }

    private JsonNode get(String url, String site)
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "NoonErpSync/1.0")
                .header("Accept", "application/json")
                .header("Cookie", api.sessionCookie())
                .header("X-Project", api.projectCode())
                .header("Country-Code", site.toLowerCase(Locale.ROOT))
                .header("X-Locale", "en-" + site.toLowerCase(Locale.ROOT))
                .GET().timeout(Duration.ofSeconds(25)).build();
        return send(request);
    }

    private JsonNode post(String url, String site, String body)
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "NoonErpSync/1.0")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Cookie", api.sessionCookie())
                .header("X-Project", api.projectCode())
                .header("Country-Code", site.toLowerCase(Locale.ROOT))
                .header("X-Locale", "en-" + site.toLowerCase(Locale.ROOT))
                .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(25)).build();
        return send(request);
    }

    /** 统一执行：同一 throttle、状态校验与 JSON 解析。空响应体视为无效响应而非空结果。 */
    private JsonNode send(HttpRequest request)
    {
        NoonRequestThrottle.Response<String> response = throttle.run(() ->
        {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new NoonRequestThrottle.Response<>(r.statusCode(), r.body());
        });
        api.require2xxPublic(response.status(), response.body());
        if (response.body().isBlank())
            throw new IllegalStateException("Seller Lab 查询返回空响应体，未视为空结果");
        try
        {
            return json.readTree(response.body());
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Seller Lab 查询响应解析失败", e);
        }
    }
}
