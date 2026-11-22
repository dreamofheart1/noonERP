package com.yx.noon.asn;

import java.util.List;

/**
 * ASN 动作结果（契约 §3），与调拨 {@code TransferResult} 同形。首次执行 {@code replayed=false}；
 * 同 requestId + 同 action + 同内容重放原样返回首次结果并置 {@code replayed=true}，零副作用。
 *
 * @param asnId         ASN 头 {@code log_asn.id}
 * @param asnNo         ASN 号 {@code ASN:<SITE>:<UUID8>}（服务端生成）
 * @param replayed      是否幂等重放
 * @param action        {@code CREATE} / {@code SEAL} / {@code RECEIVE}
 * @param childBatchIds 本次动作创建（SEAL）或处理（RECEIVE）的在途子批次 id；CREATE 恒为空
 */
public record AsnResult(Long asnId, String asnNo, boolean replayed, String action, List<Long> childBatchIds)
{
    public AsnResult
    {
        childBatchIds = childBatchIds == null ? List.of() : List.copyOf(childBatchIds);
    }
}
