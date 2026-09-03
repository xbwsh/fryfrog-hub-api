package com.fryfrog.hub.audiobook.util;

import java.util.Comparator;

/**
 * 自然排序：按数字段数值比较（"2.mp3" < "10.mp3"），非数字段按字符串比较。
 * 有声书多文件按文件名排序即播放顺序，必须用自然序而非字典序。
 */
public class NaturalOrderComparator implements Comparator<String> {

    public static final NaturalOrderComparator INSTANCE = new NaturalOrderComparator();

    @Override
    public int compare(String a, String b) {
        int ia = 0;
        int ib = 0;
        int na = a.length();
        int nb = b.length();

        while (ia < na && ib < nb) {
            char ca = a.charAt(ia);
            char cb = b.charAt(ib);

            boolean digitA = Character.isDigit(ca);
            boolean digitB = Character.isDigit(cb);

            if (digitA && digitB) {
                long va = 0;
                int sa = ia;
                while (ia < na && Character.isDigit(a.charAt(ia))) {
                    va = va * 10 + (a.charAt(ia) - '0');
                    ia++;
                }
                long vb = 0;
                int sb = ib;
                while (ib < nb && Character.isDigit(b.charAt(ib))) {
                    vb = vb * 10 + (b.charAt(ib) - '0');
                    ib++;
                }
                if (va != vb) return Long.compare(va, vb);
                // 前导零多的排前面（"01" < "1"），保持稳定
                int la = ia - sa;
                int lb = ib - sb;
                if (la != lb) return Integer.compare(lb, la);
            } else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                ia++;
                ib++;
            }
        }
        return Integer.compare(na - ia, nb - ib);
    }
}
