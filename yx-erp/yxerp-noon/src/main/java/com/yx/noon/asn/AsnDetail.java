package com.yx.noon.asn;

import java.util.List;

/** ASN 详情（契约 §7）：头 + 行（行带在途子批次血缘：parent / children / split_seq / asn_no）。 */
public record AsnDetail(AsnRow head, List<AsnItemRow> items)
{
    public AsnDetail
    {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
