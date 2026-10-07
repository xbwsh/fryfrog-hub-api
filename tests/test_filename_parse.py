"""文件名解析：集号识别的能力矩阵，重点盯 `#N`（JAV/同人动画常见）。

实测缺口：库里 `Boin 巨乳教学 #1/#2`、`少女们的茶道ISM #1/#2`、
`樱春女学院的男优 #1` 这批**完全解析不出集号**，于是 season/episode 都是 None
（当单片入库），进不了正片季集体系。对照项目 MHTI（xiyan520/MHTI）的解析器
声明支持「裸数字与全角井号集数」，这里补齐 `#N` / `＃N`。

同时把负向断言固化——**宁可认不出，也不能认错**：
`ACHJ-039`（JAV 番号）结尾的数字不是集号，`FUCK#1`（无分隔粘连）也不算。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

import pytest

from fryfrog.services.fsutil import parse_episode

# (文件名, 期望季, 期望集, 说明)
CAPABILITY_MATRIX = [
    # ── 标准 / 拉美 ──────────────────────────────────────────────
    ("某剧 - S01E01", 1, 1, "标准 SxxExx"),
    ("某剧.S02E13.1080p", 2, 13, "点分隔 + 分辨率"),
    ("某剧 - s03e05", 3, 5, "小写"),
    ("某剧 - S00E01", 0, 1, "第 0 季（特别篇）——必须保留 0"),
    ("某剧 1x05", 1, 5, "1x05 形式"),
    # ── 方括号结构 ───────────────────────────────────────────────
    ("[组名] 某剧 - S01E02 [1080p]", 1, 2, "方括号"),
    ("【字幕组】某剧 - S01E02【1080P】", 1, 2, "全角方括号"),
    # ── 日语 / 中文 ──────────────────────────────────────────────
    ("某剧 第01话", None, 1, "日语 第x话"),
    ("某剧 第01話", None, 1, "日语繁体 話"),
    ("某剧 第01集", None, 1, "中文 第x集"),
    ("某剧 第001话", None, 1, "三位数补零"),
    ("某剧 - EP05", None, 5, "EP 前缀"),
    # ── #N 井号集数（本次新增）────────────────────────────────────
    ("Boin 巨乳教学 #1", None, 1, "半角井号（库里真实数据）"),
    ("Boin 巨乳教学 #2", None, 2, "半角井号 2"),
    ("少女们的茶道ISM #1", None, 1, "含 ASCII 的标题 + 井号"),
    ("某剧 ＃12", None, 12, "全角井号"),
    ("某剧 # 12", None, 12, "井号与数字间有空格"),
    # ── 方括号 / 圆括号裸集号（[01] 形式）────────────────────────
    (
        "[TUDO&Ygm] Kono Yuusha ga Ore Tsueee Kuse ni Shinchou Sugiru "
        "[01][Ma10p_2160p][x265_flac_ass]",
        None, 1, "发布组+方括号集号+编码标记（本次新增）",
    ),
    ("Some.Show[02][1080p]", None, 2, "点分隔标题 + 方括号集号"),
    ("某剧（03）", None, 3, "全角圆括号集号"),
    ("某剧 (04)", None, 4, "半角圆括号集号"),
    ("某剧【第2集】", None, 2, "全角方括号 第x集"),
    ("某剧 [1]", None, 1, "单数字集号"),
    # ── 英文发布名 ───────────────────────────────────────────────
    ("Some.Show.S01E01.2160p.WEB-DL.HEVC", 1, 1, "英文发布名"),
]

# 不能误判的（数字看着像集号但不是）
NEGATIVE_CASES = [
    ("ACHJ-039", "JAV 番号：结尾数字不是集号"),
    ("ADN-499", "JAV 番号"),
    ("某剧 FUCK#1", "无分隔粘连：前面紧跟字母，不认"),
    ("某剧 #1234", "四位数编号，不认"),
    ("某剧 [2024]", "方括号四位数（年份），不认"),
    ("某剧 [01-12]", "集数范围，不认"),
    ("某剧 [1080p]", "括号内是分辨率，不认"),
    ("某剧 [FR2]", "括号内字母紧跟数字，不认"),
    ("某剧 [Ma10p_2160p]", "括号内是编码标记，不认"),
    ("2024-01-15 某剧", "日期前缀，不认"),
    ("某剧 OVA", "OVA 无集号"),
    ("某剧 双胞胎小恶魔", "纯标题无集号"),
]


@pytest.mark.parametrize("name,season,episode,note", CAPABILITY_MATRIX)
def test_capability_matrix(name, season, episode, note):
    got_name, got_season, got_episode = parse_episode(name)
    assert (got_season, got_episode) == (season, episode), (
        f"{note}：{name!r} 期望季集 {(season, episode)}，实际 {(got_season, got_episode)}"
    )
    assert got_name, f"{name!r} 解析出的剧名不该为空"


@pytest.mark.parametrize("name,note", NEGATIVE_CASES)
def test_negative_cases_not_misparsed(name, note):
    _n, season, episode = parse_episode(name)
    assert (season, episode) == (None, None), (
        f"{note}：{name!r} 不该解析出季集，实际 {(season, episode)}"
    )


def test_hash_episode_stripped_from_series_name():
    """`#N` 要从剧名里剥掉，否则每集都会各建一个顶层目录。"""
    for name, expected in (
        ("Boin 巨乳教学 #1", "Boin 巨乳教学"),
        ("Boin 巨乳教学 #2", "Boin 巨乳教学"),
        ("某剧 ＃12", "某剧"),
    ):
        assert parse_episode(name)[0] == expected, name


def test_same_show_same_series_name_across_episodes():
    """关键点：同一部剧的不同集必须解析出**同一个剧名**，否则整季被拆散。"""
    a = parse_episode("Boin 巨乳教学 #1")[0]
    b = parse_episode("Boin 巨乳教学 #2")[0]
    assert a == b


def test_explicit_patterns_win_over_hash():
    """SxxExx 比 #N 更明确：同时出现时以 SxxExx 为准。"""
    assert parse_episode("某剧 #5 S02E07")[1:] == (2, 7)


def test_hash_does_not_break_season_marker():
    """`第 N 季` 仍要能解析出来（#N 分支不能把它顶掉）。"""
    assert parse_episode("某剧 第 2 季 #3")[1:] == (2, 3)
