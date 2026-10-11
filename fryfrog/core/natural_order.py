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
        # isdecimal() 而非 isdigit()：后者对 '²' '①' 等上标/带圈数字为 True，
        # 但 int() 对它们抛 ValueError —— 文件名里出现这类字符就会让整轮排序
        # 抛异常，扫描被上层 except 吞成一条日志。isdecimal() 保证 int() 可解析。
        int(text[:15]) if text.isdecimal() else text.lower()
        for text in re.split(r"(\d+)", s)
    ]


def natural_compare(a: str, b: str) -> int:
    ia = ib = 0
    na, nb = len(a), len(b)
    while ia < na and ib < nb:
        ca, cb = a[ia], b[ib]
        # 一律用 isdecimal()：'²' '①' 这类 isdigit() 为真但 ord()-48 算出的
        # 是无意义的数字（'²' → 170），会让排序结果错乱。
        da, db = ca.isdecimal(), cb.isdecimal()
        if da and db:
            sa = ia
            va = 0
            while ia < na and a[ia].isdecimal():
                va = va * 10 + (ord(a[ia]) - 48)
                ia += 1
            sb = ib
            vb = 0
            while ib < nb and b[ib].isdecimal():
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
