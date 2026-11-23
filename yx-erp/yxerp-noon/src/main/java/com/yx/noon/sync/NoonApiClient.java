package com.yx.noon.sync;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Noon Partner API 只读客户端：RS256 JWT 登录 → 批量导出 partner_sku 清单 →
 * 按 partner_sku 回读 Offer API。不写平台数据；密钥仅在运行时从本地凭据文件读取，
 * 不进入日志、不进入数据库。
 */
@Component
public class NoonApiClient
{
    private static final String LOGIN_PATH = "/identity/public/v1/api/login";
    private static final String EXPORT_CREATE_PATH = "/impex/v1/export/create";
    private static final String EXPORT_STATUS_PATH = "/impex/v1/export/status";

    private final NoonApiProperties properties;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final NoonRequestThrottle throttle;

    private volatile String sessionCookie;
    private volatile String projectCode;
    private volatile String keyId;
    private volatile PrivateKey privateKey;

    public NoonApiClient(NoonApiProperties properties, NoonRequestThrottle throttle)
    {
        this.properties = properties;
        this.throttle = throttle == null ? NoonRequestThrottle.defaults() : throttle;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * 导出 AE 站点的 partner_sku 清单。来源为 FBN 库存台账摘要，
     * 该文件含 partner_sku / sku / partner_barcode；按 partner_sku 去重。
     */
    public List<NoonCatalogSku> listPartnerSkus(String siteCode)
    {
        if (!"AE".equalsIgnoreCase(siteCode))
            throw new IllegalArgumentException("Noon SKU 同步当前仅支持 AE 站点");
        login();
        ObjectNode params = json.createObjectNode();
        params.put("country_code", properties.getCountry());
        ObjectNode body = json.createObjectNode();
        body.put("export_category_code", properties.getExportCategory());
        body.set("params", params);

        JsonNode created = call("POST", EXPORT_CREATE_PATH, body.toString());
        String exportCode = created.path("export_code").asText(null);
        if (exportCode == null || exportCode.isBlank())
            throw new IllegalStateException("Noon 导出任务未返回 export_code: " + created);

        String downloadUrl = null;
        for (int attempt = 0; attempt < properties.getMaxPollAttempts(); attempt++)
        {
            ObjectNode statusBody = json.createObjectNode();
            statusBody.put("export_code", exportCode);
            JsonNode status = call("POST", EXPORT_STATUS_PATH, statusBody.toString());
            String state = status.path("export_status").asText("");
            if ("COMPLETE".equalsIgnoreCase(state))
            {
                downloadUrl = status.path("download_url").asText(null);
                break;
            }
            if ("ERROR".equalsIgnoreCase(state))
                throw new IllegalStateException("Noon 导出任务失败: export_code=" + exportCode + " resp=" + status);
            sleep(properties.getPollIntervalMs());
        }
        if (downloadUrl == null || downloadUrl.isBlank())
            throw new IllegalStateException("Noon 导出任务超时未完成: export_code=" + exportCode);

        byte[] csv = download(downloadUrl);
        return parseCatalogCsv(new String(csv, StandardCharsets.UTF_8));
    }

    /** 回读单个 partner_sku 的 Offer；只返回 AE + noon 业务模式的 offer。 */
    public NoonOfferInfo getOffer(String partnerSku)
    {
        login();
        String path = "/offer/v1/product/" + URLEncoder.encode(partnerSku, StandardCharsets.UTF_8);
        JsonNode body = call("GET", path, null);
        String sku = text(body, "sku");
        String title = text(body, "title");
        String brand = text(body, "brand");
        JsonNode offers = body.path("offers");
        if (offers.isArray())
        {
            for (JsonNode offer : offers)
            {
                if (!"ae".equalsIgnoreCase(offer.path("country_code").asText()))
                    continue;
                if (!"noon".equalsIgnoreCase(offer.path("business_model").asText()))
                    continue;
                String issues = offer.path("offer_issues").isMissingNode()
                        ? "[]" : offer.path("offer_issues").toString();
                JsonNode price = offer.path("price");
                return new NoonOfferInfo(
                        partnerSku, sku, null, title, brand,
                        text(offer, "offer_code"),
                        offer.path("is_active").asBoolean(false),
                        offer.path("live_status").asBoolean(false),
                        issues,
                        price.isMissingNode() || price.isNull() ? null : price.path("amount").asText(null),
                        price.isMissingNode() || price.isNull() ? null : text(price, "currency"),
                        getPrimaryImage(sku));
            }
        }
        return null; // 该账号没有 AE noon offer：同步时计为跳过，不伪造数据
    }

    /**
     * 回读 Noon 商品首图。sku_parent = Noon SKU 去掉 "-数字" 变体后缀；
     * 取 visibility=VISIBLE 且 review_status=VALID 的 sort 最小图片。
     * 老式 N 开头 SKU 无 content（400），或没有图片时返回 null，禁止伪造。
     */
    String getPrimaryImage(String noonSku)
    {
        if (noonSku == null || noonSku.isBlank()) return null;
        String skuParent = noonSku.replaceFirst("-\\d+$", "");
        try
        {
            ObjectNode body = json.createObjectNode();
            body.put("sku_parent", skuParent);
            JsonNode content = call("POST", "/content/v1/product/content/get", body.toString());
            JsonNode images = content.path("images");
            if (!images.isArray()) return null;
            String bestUrl = null;
            long bestSort = Long.MAX_VALUE;
            for (JsonNode image : images)
            {
                String visibility = image.path("visibility").asText("");
                String review = image.path("review_status").asText("");
                if (!"VISIBILITY_STATUS_VISIBLE".equals(visibility)
                        && !"VISIBLE".equalsIgnoreCase(visibility)) continue;
                if (!"REVIEW_STATUS_VALID".equals(review) && !"VALID".equalsIgnoreCase(review)) continue;
                long sort = image.path("sort").asLong(Long.MAX_VALUE);
                String url = text(image, "url");
                if (url != null && !url.isBlank() && sort < bestSort)
                {
                    bestSort = sort;
                    bestUrl = url;
                }
            }
            return bestUrl;
        }
        catch (RuntimeException e)
        {
            return null; // 无 content / 无图是正常业务状态，不能伪造图片
        }
    }

    /** 解析 FBN 台账摘要 CSV；无引号字段按逗号切分，带引号字段兼容双引号转义。 */
    static List<NoonCatalogSku> parseCatalogCsv(String csv)
    {
        String content = csv.startsWith("\uFEFF") ? csv.substring(1) : csv;
        String[] lines = content.split("\\R");
        if (lines.length == 0) return List.of();
        Map<String, Integer> header = new LinkedHashMap<>();
        String[] cols = splitCsvLine(lines[0]);
        for (int i = 0; i < cols.length; i++) header.put(cols[i].trim(), i);
        int ps = header.getOrDefault("partner_sku", -1);
        int noonSku = header.getOrDefault("sku", -1);
        int barcode = header.getOrDefault("partner_barcode", -1);
        int location = header.getOrDefault("location", -1);
        if (ps < 0) throw new IllegalStateException("Noon 导出文件缺少 partner_sku 列");

        Map<String, NoonCatalogSku> result = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++)
        {
            if (lines[i].isBlank()) continue;
            String[] row = splitCsvLine(lines[i]);
            if (location >= 0 && location < row.length && !"AE".equalsIgnoreCase(row[location].trim()))
                continue;
            String partnerSku = cell(row, ps);
            if (partnerSku.isBlank()) continue;
            result.putIfAbsent(partnerSku, new NoonCatalogSku(partnerSku, cell(row, noonSku), cell(row, barcode)));
        }
        return new ArrayList<>(result.values());
    }

    private static String cell(String[] row, int index)
    {
        return index >= 0 && index < row.length ? row[index].trim() : "";
    }

    private static String[] splitCsvLine(String line)
    {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            if (c == '"')
            {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"')
                {
                    cell.append('"');
                    i++;
                }
                else quoted = !quoted;
            }
            else if (c == ',' && !quoted)
            {
                out.add(cell.toString());
                cell.setLength(0);
            }
            else cell.append(c);
        }
        out.add(cell.toString());
        return out.toArray(String[]::new);
    }

    private byte[] download(String url)
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", properties.getUserAgent())
                .GET().timeout(Duration.ofSeconds(60)).build();
        NoonRequestThrottle.Response<byte[]> response = throttle.run(() ->
        {
            HttpResponse<byte[]> r = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new NoonRequestThrottle.Response<>(r.statusCode(), r.body());
        });
        require2xx(response.status(), response.body().length > 5000 ? "<binary>" : new String(response.body(), StandardCharsets.UTF_8));
        return response.body();
    }

    /** 统一调用：401 时重新登录并重试一次；非 2xx 抛出带响应摘要的异常。 */
    private JsonNode call(String method, String path, String body)
    {
        for (int attempt = 0; attempt < 2; attempt++)
        {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + path))
                    .header("User-Agent", properties.getUserAgent())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(properties.getOfferTimeoutSeconds()));
            if (projectCode != null) builder.header("X-Project", projectCode);
            if (sessionCookie != null && !sessionCookie.isBlank()) builder.header("Cookie", sessionCookie);
            if (body == null)
                builder.GET();
            else
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            try
            {
                // 每次尝试都把真实请求交给统一限速器；401 重登在 throttle 外层，
                // 避免 throttle 持锁期间调用 login 造成二次获取死锁。
                NoonRequestThrottle.Response<String> response = throttle.run(() ->
                {
                    HttpResponse<String> r = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    return new NoonRequestThrottle.Response<>(r.statusCode(), r.body());
                });
                if (response.status() == 401 && attempt == 0)
                {
                    sessionCookie = null;
                    login();
                    continue;
                }
                require2xx(response.status(), response.body());
                if (response.body().isBlank()) return json.createObjectNode();
                return json.readTree(response.body());
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Noon API 调用失败: " + method + " " + path, e);
            }
        }
        throw new IllegalStateException("Noon API 认证失败: " + path);
    }

    private void require2xx(int status, String body)
    {
        if (status >= 200 && status < 300) return;
        String message = body == null ? "" : body.replace('\n', ' ');
        if (message.length() > 400) message = message.substring(0, 400);
        throw new IllegalStateException("Noon API HTTP " + status + " :: " + message);
    }

    /** 供 Seller Lab 只读客户端复用会话（受统一限速保护）；不暴露密钥/私钥。 */
    public void ensureLoggedIn()
    {
        if (sessionCookie != null && !sessionCookie.isBlank()) return;
        login();
    }

    /** 供 Seller Lab 只读客户端使用的会话头（值不进入日志/响应）。 */
    public String sessionCookie() { return sessionCookie == null ? "" : sessionCookie; }

    public String projectCode() { return projectCode == null ? "" : projectCode; }

    public void require2xxPublic(int status, String body) { require2xx(status, body); }

    private synchronized void login()
    {
        if (sessionCookie != null && projectCode != null) return;
        try
        {
            Path path = Path.of(properties.getCredentialsPath());
            JsonNode credentials = json.readTree(Files.readString(path, StandardCharsets.UTF_8));
            keyId = credentials.path("key_id").asText(null);
            String pem = credentials.path("private_key").asText(null);
            projectCode = credentials.path("project_code").asText(null);
            if (keyId == null || pem == null || projectCode == null)
                throw new IllegalStateException("Noon 凭据文件缺少 key_id/private_key/project_code: " + path);

            ObjectNode loginBody = json.createObjectNode();
            loginBody.put("token", signJwt(keyId, pem));
            loginBody.put("default_project_code", projectCode);

            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + LOGIN_PATH))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", properties.getUserAgent())
                    .POST(HttpRequest.BodyPublishers.ofString(loginBody.toString(), StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(properties.getOfferTimeoutSeconds())).build();
            // Cookie 头必须在 throttle 内的真实响应里读取：Response 只带状态与 body。
            List<String> cookies = new ArrayList<>();
            NoonRequestThrottle.Response<String> response = throttle.run(() ->
            {
                HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
                for (String raw : r.headers().allValues("set-cookie")) cookies.addAll(java.util.Arrays.asList(raw.split(",\\s*(?=[^;,]+=)")));
                return new NoonRequestThrottle.Response<>(r.statusCode(), r.body());
            });
            require2xx(response.status(), response.body());
            List<String> pairs = new ArrayList<>();
            for (String raw : cookies)
            {
                String pair = raw.split(";", 2)[0].trim();
                if (!pair.isBlank()) pairs.add(pair);
            }
            if (pairs.isEmpty()) throw new IllegalStateException("Noon 登录未返回会话 Cookie");
            sessionCookie = String.join("; ", pairs);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("读取 Noon 凭据失败: " + properties.getCredentialsPath(), e);
        }
        catch (java.security.GeneralSecurityException e)
        {
            throw new IllegalStateException("Noon 私钥签名失败", e);
        }
    }

    private String signJwt(String subject, String pem) throws java.security.GeneralSecurityException
    {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64(("{\"sub\":\"" + subject + "\",\"iat\":"
                + (System.currentTimeMillis() / 1000) + ",\"jti\":\"" + UUID.randomUUID() + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String signed = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(signed.getBytes(StandardCharsets.US_ASCII));
        return signed + "." + b64(signature.sign());
    }

    private static String b64(byte[] bytes)
    {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Noon 导出轮询被中断", e);
        }
    }
}
