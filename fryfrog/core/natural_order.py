from __future__ import annotations

import re
from functools import cmp_to_key


def natural_key(s: str):
    """生成自然排序 key：数字段按数值。

    数字段必须截断：文件名可能含超长连续数字，超过 Python 3.12 的
    int 位数上限（默认 4300 位）时 `int()` 直接抛 ValueError，导致所有
    走 natural_key 排序的接口 500。截到 15 位不影响实际排序结果
    （15 位已远超任何文件序号范围），且与 natural_compare 语义一致。
    """
    return [
        int(text[:15]) if text.isdigit() else text.lower()
        for text in re.split(r"(\d+)", s)
    ]


def natural_compare(a: str, b: str) -> int:
    ia = ib = 0
    na, nb = len(a), len(b)
    while ia < na and ib < nb:
        ca, cb = a[ia], b[ib]
        da, db = ca.isdigit(), cb.isdigit()
        if da and db:
            sa = ia
            va = 0
            while ia < na and a[ia].isdigit():
                va = va * 10 + (ord(a[ia]) - 48)
                ia += 1
            sb = ib
            vb = 0
            while ib < nb and b[ib].isdigit():
                vb = vb * 10 + (ord(b[ib]) - 48)
                ib += 1
            if va != vb:
                return -1 if va < vb else 1
            la, lb = ia - sa, ib - sb
            if la != lb:
                return 1 if la < lb else -1
        else:
            if ca != cb:
                return -1 if ca < cb else 1
            ia += 1
            ib += 1
    return -1 if (na - ia) < (nb - ib) else (1 if (na - ia) > (nb - ib) else 0)


def natural_sort(strings: list[str]) -> list[str]:
    return sorted(strings, key=cmp_to_key(natural_compare))
