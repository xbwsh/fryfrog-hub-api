"""超长标题不能把详情页打成 500。

实测故障：某条 JAV 文件名 110 字符 / **284 字节**（中日文一字 3 字节），
`get_metadata_dir` 拿它当目录名拼路径后 `.exists()` 抛
`OSError: [Errno 36] File name too long`，冒到路由层 → 详情页 internal server error。

关键点：`Path.exists()` **只吞 FileNotFoundError 一类，不吞 ENAMETOOLONG**，
所以必须在生成目录名时就按字节截断，并且路径探测要兜住 OSError。

**测试数据的坑（本地过、CI 挂）**：一开始我直接按线上那条 284 字节的名字
`write_bytes()` 造文件——Windows NTFS 容忍超长名所以本地全过，但 CI 是 ext4，
单层 255 字节是硬限制，`write_bytes` 直接 OSError。
真实文件系统上这种文件**根本建不出来**，所以正确的造法是：文件名本身合法，
但**标题（=`_select_show_name` 取的值）比它长**——这才是 `get_metadata_dir`
会拿到超长目录名的真实途径。
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

# 线上真实触发的那条文件名（110 字符 / 284 字节）——只用于断言，
# 不作为文件名落盘（ext4 建不出来）
REAL_LONG_NAME = (
    "URE-093 累計6万DL越え！！ 究極の逆3Pハーレム同人を全編丸ごと忠実実写化！！ "
    "原作_サークルしまぱん 巨乳が2人いないと勃起しない夫のために友達を連れてきた妻 "
    "おまけの職場コスFUCKエピソードも特別追加！！"
)

# 能安全落盘的短文件名（ASCII，远低于任何文件系统上限）
SAFE_FILE_NAME = "URE-093.mp4"

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

    vf = media / SAFE_FILE_NAME
    vf.write_bytes(b"x" * 32)
    video = Video(
        file_path=str(vf),
        file_name=SAFE_FILE_NAME,
        # 单片：标题就是"剧名"，而它比文件名长得多 —— `_select_show_name`
        # 对单片取的就是 title，于是 get_metadata_dir 拿到超长目录名
        title=REAL_LONG_NAME,
        library_id=lib.id,
        media_type="movie",
    )
    db.add(video)
    db.commit()
    yield db, lib, video, media
    db.close()


def test_reproduced_the_real_title_is_actually_too_long():
    """先证明这条数据确实会超限——否则下面的测试就是在测空气。"""
    assert len(REAL_LONG_NAME.encode("utf-8")) > FS_COMPONENT_LIMIT


def test_safe_file_name_fits_filesystem():
    """测试自己用的文件名必须能落盘（这条就是 CI 挂掉的教训）。"""
    assert len(SAFE_FILE_NAME.encode("utf-8")) <= FS_COMPONENT_LIMIT
    assert len(SAFE_FILE_NAME.encode("utf-8")) < 100


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
    assert path.exists() in (True, False)


def test_metadata_dir_is_under_library_root(env):
    """截断后仍要落在库根下，别把路径拼飞。"""
    db, _lib, video, media = env
    path = vs.get_metadata_dir(db, video)

    assert str(path).startswith(str(media)), path
    assert path.parent == media


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


def test_disk_really_rejects_overlong_component(tmp_path):
    """留个记录：真实文件系统确实建不出 284 字节的单层名（CI 就是在这里挂的）。

    不写成断言失败——不同平台行为不同（Windows 容忍、ext4 拒绝），
    只验证：**若**系统拒绝，异常是 OSError 而不是别的诡异错误。
    """
    media = tmp_path / "probe"
    media.mkdir()
    overlong = media / f"{REAL_LONG_NAME}.mp4"
    if len(REAL_LONG_NAME.encode("utf-8")) <= FS_COMPONENT_LIMIT:
        pytest.skip("文件名没超限，无需探测")
    try:
        overlong.write_bytes(b"x")
        # Windows/NTFS 容忍：记下来，别当失败
    except OSError as exc:
        assert exc.errno is not None, exc
