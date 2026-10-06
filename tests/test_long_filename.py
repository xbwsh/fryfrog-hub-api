"""超长标题不能把详情页打成 500。

实测故障：某条 JAV 文件名 110 字符 / **284 字节**（中日文一字 3 字节），
`get_metadata_dir` 拿它当目录名拼路径后 `.exists()` 抛
`OSError: [Errno 36] File name too long`，冒到路由层 → 详情页 internal server error。

关键点：`Path.exists()` **只吞 FileNotFoundError 一类，不吞 ENAMETOOLONG**，
所以必须在生成目录名时就按字节截断，并且路径探测要兜住 OSError。
"""

from __future__ import annotations

import os

os.environ.setdefault("AUTH_ENABLED", "false")

from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from fryfrog.db import Base
from fryfrog.models.library import MediaLibrary
from fryfrog.models.video import Video
from fryfrog.services import video_service as vs

# 线上真实触发的那条文件名（110 字符 / 284 字节）
LONG_NAME = (
    "URE-093 累計6万DL越え！！ 究極の逆3Pハーレム同人を全編丸ごと忠実実写化！！ "
    "原作_サークルしまぱん 巨乳が2人いないと勃起しない夫のために友達を連れてきた妻 "
    "おまけの職場コスFUCKエピソードも特別追加！！"
)

# 单层文件名上限（Linux 文件系统按**字节**算）
FS_COMPONENT_LIMIT = 255


@pytest.fixture()
def env(tmp_path):
    engine = create_engine("sqlite://")
    Base.metadata.create_all(engine)
    db = sessionmaker(bind=engine)()

    media = tmp_path / "media"
    media.mkdir(parents=True, exist_ok=True)
    lib = MediaLibrary(name="小日子", path=str(media), type="VIDEO", enabled=True)
    db.add(lib)
    db.flush()

    # 磁盘上真实存在的长名文件（tmp_path 在 Windows 下也能建出 284 字节的名字）
    vf = media / f"{LONG_NAME}.mp4"
    vf.write_bytes(b"x" * 32)
    video = Video(
        file_path=str(vf),
        file_name=vf.name,
        title=LONG_NAME,  # 单片：标题就是文件名
        library_id=lib.id,
        media_type="movie",
    )
    db.add(video)
    db.commit()
    yield db, lib, video, media
    db.close()


def test_reproduced_the_real_filename_is_actually_too_long():
    """先证明这条数据确实会超限——否则下面的测试就是在测空气。"""
    assert len(LONG_NAME.encode("utf-8")) > FS_COMPONENT_LIMIT


def test_clean_folder_truncates_by_bytes(env):
    db, _lib, video, _media = env
    show = vs._clean_folder(vs._select_show_name(video))

    assert len(show.encode("utf-8")) <= vs.FOLDER_NAME_MAX_BYTES, (
        f"目录名仍超限：{len(show.encode('utf-8'))} 字节"
    )
    # 截断要保留可读前缀，不能变成 "Unknown" 或空
    assert show.startswith("URE-093"), show


def test_metadata_dir_exists_does_not_raise(env):
    """核心回归：修复前这里抛 OSError(ENAMETOOLONG)。"""
    db, _lib, video, _media = env

    path = vs.get_metadata_dir(db, video)
    assert len(path.name.encode("utf-8")) <= FS_COMPONENT_LIMIT
    # 不该抛异常（exists 返回什么都行）
    assert path.exists() in (True, False)


def test_asset_flags_survives_long_title(env):
    """路由层调的就是 asset_flags，它必须不炸。"""
    db, _lib, video, _media = env

    flags = vs.asset_flags(db, video)

    assert set(flags) == {"has_nfo", "has_poster", "has_fanart", "has_metadata_dir"}
    assert all(isinstance(v, bool) for v in flags.values())


def test_safe_exists_returns_false_on_oserror():
    """路径异常（权限、坏链接、超长）一律当"不存在"，不冒泡。"""

    class _Boom(Path):
        def exists(self, *, follow_symlinks: bool = True) -> bool:  # noqa: D102
            raise OSError(36, "File name too long")

    assert vs._safe_exists(_Boom("/whatever")) is False


def test_truncate_bytes_never_splits_multibyte_chars():
    """按字节截断不能切断 UTF-8 字符（否则名字会变成乱码）。"""
    for unit in ("あ", "中", "😀"):
        out = vs.truncate_bytes(unit * 300, 200)
        assert len(out.encode("utf-8")) <= 200, unit
        out.encode("utf-8").decode("utf-8")  # 能往返解码 = 没切断
        assert out == unit * (len(out) // len(unit))


def test_truncate_bytes_keeps_short_text_intact():
    for text in ("某剧", "Short Show", ""):
        assert vs.truncate_bytes(text) == text


def test_normal_length_titles_unaffected(env):
    """正常长度的剧名不能被改动（截断只对超长生效）。"""
    db, lib, _video, media = env
    vf = media / "普通剧名 - S01E01.mp4"
    vf.write_bytes(b"x" * 32)
    ep = Video(
        file_path=str(vf),
        file_name=vf.name,
        title="普通剧名 S01E01",
        series_name="普通剧名",
        library_id=lib.id,
        media_type="tv",
        is_series=True,
        season_number=1,
        episode_number=1,
    )
    db.add(ep)
    db.flush()

    show = vs._clean_folder(vs._select_show_name(ep))
    assert show == "普通剧名", show
