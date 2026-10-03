from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from sqlalchemy import create_engine, event, select
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary, UserLibrary
from fryfrog.models.user import User, UserRole
from fryfrog.models.video import Video, VideoSeries
from fryfrog.routers.video import series as series_router
from fryfrog.routers.video._common import MAX_PAGE_SIZE, clamp_paging


def _setup(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib = MediaLibrary(name="影片", path=str(tmp_path), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()

    # 4 个系列（各 2 分集，series_id 与 title 交错），3 个单集
    for i, title in enumerate(["B剧", "a剧", "C剧", "D剧"]):
        s = VideoSeries(title=title)
        db.add(s)
        db.flush()
        for ep in (1, 2):
            db.add(
                Video(
                    file_path=str(tmp_path / f"{title}-E{ep}.mkv"),
                    file_name=f"{title}-E{ep}.mkv",
                    title=f"{title} S01E{ep:02d}",
                    library_id=lib.id,
                    series_id=s.id,
                    season_number=1,
                    episode_number=ep,
                )
            )
    for name in ("独立电影1", "独立电影2", "独立电影3"):
        db.add(
            Video(
                file_path=str(tmp_path / f"{name}.mkv"),
                file_name=f"{name}.mkv",
                title=name,
                library_id=lib.id,
            )
        )
    db.commit()
    return db


def _paged(resp) -> dict:
    assert resp.success
    return resp.data


def test_series_paging_covers_all_without_duplicates(tmp_path):
    """混合列表（系列+单集）翻完所有页：不重、不漏、总数正确。"""
    db = _setup(tmp_path)
    collected: list[tuple[str, int]] = []
    page = 0
    while True:
        data = _paged(series_router.list_series(db, page=page, size=3))
        if page == 0:
            assert data["totalElements"] == 7  # 4 系列 + 3 单集
            assert data["totalPages"] == 3
        if not data["content"]:
            break
        for item in data["content"]:
            collected.append((item["type"], item["id"]))
        page += 1
        assert page <= 10  # 防死循环

    assert len(collected) == 7
    assert len(set(collected)) == 7
    # 系列排在单集之前，系列按标题（忽略大小写）排序
    types = [t for t, _ in collected]
    assert types == ["series"] * 4 + ["standalone"] * 3


def test_series_paging_out_of_range(tmp_path):
    db = _setup(tmp_path)
    data = _paged(series_router.list_series(db, page=99, size=10))
    assert data["content"] == []
    assert data["totalElements"] == 7


def test_page_size_clamped(tmp_path):
    """size 超上限时收敛到 MAX_PAGE_SIZE，防止一次拉全表。"""
    db = _setup(tmp_path)
    data = _paged(series_router.list_series(db, page=0, size=99999))
    assert data["size"] == MAX_PAGE_SIZE
    assert clamp_paging(-5, 0) == (0, 1)
    assert clamp_paging(3, 250) == (3, MAX_PAGE_SIZE)


# ==================== 性能预算（N+1 回归预警） ====================


def _sql_budget(db):
    """开始计数；返回 stop() 取期间 SQL 次数。超预算 = 有人重新引入了 N+1。"""
    engine = db.get_bind()
    counter = {"n": 0}

    def _on(*args):
        counter["n"] += 1

    event.listen(engine, "before_cursor_execute", _on)

    def stop() -> int:
        event.remove(engine, "before_cursor_execute", _on)
        return counter["n"]

    return stop


def test_grouped_hides_unscraped_standalones(tmp_path):
    """未刮削单片只在 /unscraped 出现；库视图（分组列表）只收已绑定的。"""
    db = _setup(tmp_path)

    # 全部未刮削时：系列卡照常显示，单片段为空
    data = series_router.grouped_by_library(db, page=0, size=20).data
    assert len(data) == 1
    assert len(data[0]["series"]) == 4
    assert data[0]["standaloneVideos"] == []
    assert data[0]["standaloneCount"] == 0

    # 绑定一部单片 → 立即回流到库视图
    mv = db.scalars(select(Video).where(Video.series_id.is_(None))).first()
    mv.tmdb_id = 42
    mv.metadata_source = "tmdb"
    db.commit()

    data = series_router.grouped_by_library(db, page=0, size=20).data
    assert len(data[0]["standaloneVideos"]) == 1
    assert data[0]["standaloneCount"] == 1


def test_query_budget_paged_endpoints(tmp_path):
    """列表接口单页 SQL 次数预算：与数据规模无关，超限即回归。"""
    db = _setup(tmp_path)  # 4 系列 + 3 单集

    stop = _sql_budget(db)
    series_router.list_series(db, page=0, size=20)
    assert stop() <= 10

    stop = _sql_budget(db)
    series_router.grouped_by_library(db, page=0, size=20)
    assert stop() <= 15

    stop = _sql_budget(db)
    series_router.favorite_series(db, page=0, size=20)
    assert stop() <= 8

    stop = _sql_budget(db)
    series_router.series_calendar(db)
    assert stop() <= 5


def test_query_budget_stable_across_scale(tmp_path):
    """系列数量翻 10 倍，单页 SQL 次数不增长（N+1 的本质特征就是随规模线性涨）。"""
    db = _setup(tmp_path)
    stop = _sql_budget(db)
    series_router.list_series(db, page=0, size=20)
    small = stop()

    from sqlalchemy import select as sa_select

    lib_id = db.scalar(sa_select(MediaLibrary.id).limit(1))
    for i in range(40):
        s = VideoSeries(title=f"新增{i:03d}")
        db.add(s)
        db.flush()
        db.add(
            Video(
                file_path=str(tmp_path / f"新增{i}.mkv"),
                file_name=f"新增{i}.mkv",
                title=f"新增{i:03d}",
                library_id=lib_id,
                series_id=s.id,
            )
        )
    db.commit()

    stop = _sql_budget(db)
    series_router.list_series(db, page=0, size=20)
    large = stop()
    assert large <= 10
    assert large <= small + 2  # 基本持平，不允许随系列数线性增长


def test_restricted_user_visibility(tmp_path):
    """受限用户只看得到「有可见分集」的系列（EXISTS 分支的正确性）。"""
    from fryfrog.core.security import set_current_user_id

    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()
    lib_allowed = MediaLibrary(name="授权库", path=str(tmp_path / "a"), type="VIDEO", enabled=True)
    lib_hidden = MediaLibrary(name="未授权库", path=str(tmp_path / "b"), type="VIDEO", enabled=True)
    db.add_all([lib_allowed, lib_hidden])
    db.flush()

    s_visible = VideoSeries(title="可见剧")
    s_hidden = VideoSeries(title="隐藏剧")
    db.add_all([s_visible, s_hidden])
    db.flush()
    db.add(
        Video(
            file_path=str(tmp_path / "a" / "v.mkv"),
            file_name="v.mkv",
            title="可见剧 E01",
            library_id=lib_allowed.id,
            series_id=s_visible.id,
        )
    )
    db.add(
        Video(
            file_path=str(tmp_path / "b" / "h.mkv"),
            file_name="h.mkv",
            title="隐藏剧 E01",
            library_id=lib_hidden.id,
            series_id=s_hidden.id,
        )
    )
    user = User(username="limited", password_hash="x", role=UserRole.USER)
    db.add(user)
    db.flush()
    db.add(UserLibrary(user_id=user.id, library_id=lib_allowed.id))
    db.commit()

    set_current_user_id(user.id)
    try:
        data = _paged(series_router.list_series(db, page=0, size=20))
        titles = [i["title"] for i in data["content"]]
        assert titles == ["可见剧"]
        assert data["totalElements"] == 1

        stop = _sql_budget(db)
        series_router.list_series(db, page=0, size=20)
        assert stop() <= 10
    finally:
        set_current_user_id(None)
