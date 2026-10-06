from __future__ import annotations

import logging
from datetime import datetime
from pathlib import Path

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from fryfrog.core.utils import _CJK, clean_title, primary_title, title_head, title_parts
from fryfrog.models.video import ActorProfile, Video, VideoActor, VideoSeries
from fryfrog.services.tmdb import TmdbClient
from fryfrog.services.video_assets import (
    download_all_covers,
    generate_nfo,
)
from fryfrog.services import video_assets as assets

logger = logging.getLogger(__name__)


def _like_escape(value: str) -> str:
    """转义 SQL LIKE 的通配符，避免路径里的 _ 或 % 误匹配。"""
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def search_queries(file_name: str | None, fallback: str = "") -> list[str]:
    """生成 TMDB 查询候选（由长到短）。

    发布名常见形如「中文名.英文名.2025.S01E01.2160p.BDRip.HEVC.10bit.FLAC」，
    整名直接搜索往往 0 结果，故再退到主标题。纯拉丁名不拆段，避免把
    「Show.Name」错拆成「Show」搜到无关作品。
    """
    queries: list[str] = []
    head = title_head(file_name) if file_name else ""
    full = clean_title(head)
    if full:
        queries.append(full)
    primary = primary_title(file_name) if file_name else ""
    if primary and primary not in queries:
        queries.append(primary)
    if _CJK.search(head):
        for part in title_parts(file_name):
            if part not in queries:
                queries.append(part)
    if fallback and fallback not in queries:
        queries.append(fallback)
    return queries[:4]


def search_tmdb_best(
    queries: list[str], media_pref: str = "", adult_pref: bool | None = None
) -> dict | None:
    """依次尝试候选查询词，返回首个有结果的最佳项。

    [adult_pref] 同名多条时的取舍：
      - True（成人库里的视频）：**优先成人条目**——这类作品在 TMDB 上常有
        「普通版 + 成人版」两条同名记录，不指定就只能听天由命看返回顺序；
      - False（普通库）：反过来优先非成人条目，避免把普通内容错绑到成人条目；
      - None：不干预，保持 TMDB 原顺序（手动搜索等场景）。

    只影响**同为 mediaType** 的候选项之间的取舍，不会为了成人标记而跨类型选错。
    """
    for query in queries:
        results = search_tmdb(query)
        if not results:
            continue
        typed = [r for r in results if r.get("mediaType") == media_pref] if media_pref else []
        pool = typed or results
        if adult_pref is not None and len(pool) > 1:
            want = [r for r in pool if bool(r.get("adult")) is bool(adult_pref)]
            if want:
                return want[0]
        return pool[0]
    return None


def search_tmdb(query: str) -> list[dict]:
    client = TmdbClient()
    items = []
    for r in client.search_multi(query):
        media_type = r.get("media_type")
        if media_type not in ("movie", "tv"):
            continue
        release = r.get("release_date") or r.get("first_air_date")
        title = r.get("title") or r.get("name")
        original = r.get("original_title") or r.get("original_name")
        year = None
        if release and len(release) >= 4:
            try:
                year = int(release[:4])
            except ValueError:
                year = None
        items.append(
            {
                "id": r.get("id"),
                "title": title,
                "originalTitle": original,
                "overview": r.get("overview"),
                "releaseDate": release,
                "year": year,
                "posterPath": r.get("poster_path"),
                "backdropPath": r.get("backdrop_path"),
                "genreIds": r.get("genre_ids") or [],
                "voteAverage": r.get("vote_average"),
                "voteCount": r.get("vote_count"),
                "mediaType": media_type,
                "popularity": r.get("popularity"),
                "adult": r.get("adult"),
            }
        )
    return items


def _apply_movie_detail(video: Video, detail: dict, client: TmdbClient) -> None:
    video.tmdb_id = detail.get("id")
    video.media_type = "movie"
    video.title = detail.get("title") or video.title
    video.original_title = detail.get("original_title")
    video.overview = detail.get("overview")
    video.rating = detail.get("vote_average")
    video.vote_count = detail.get("vote_count")
    video.release_date = detail.get("release_date")
    if detail.get("release_date"):
        try:
            video.year = int(detail["release_date"][:4])
        except ValueError:
            pass
    genres = detail.get("genres") or []
    if genres:
        video.genre = ",".join(g.get("name") or "" for g in genres if g.get("name"))
    credits = detail.get("credits") or {}
    cast = credits.get("cast") or []
    crew = credits.get("crew") or []
    if cast:
        video.actors = ",".join(c.get("name") or "" for c in cast[:8] if c.get("name"))
    directors = [c.get("name") for c in crew if c.get("job") == "Director" and c.get("name")]
    if directors:
        video.director = ",".join(directors)
    video.poster_url = client.image_url(detail.get("poster_path"))
    video.backdrop_url = client.image_url(detail.get("backdrop_path"))
    video.imdb_id = detail.get("imdb_id")
    # 只升不降：库级 is_adult 是用户自己声明的（扫描时写入），比 TMDB 的
    # `adult` 标记权威。JAV/成人番在 TMDB 上通常 adult=false，直接赋值会把
    # 库级设置改回 false（实测 series 94 剧级 true、其分集 false）。
    if detail.get("adult"):
        video.is_adult = True
    video.metadata_source = "tmdb"
    video.metadata_updated_at = datetime.now()
    video.status = detail.get("status")
    if detail.get("tagline"):
        video.tags = detail["tagline"]
    companies = detail.get("production_companies") or []
    names = [c.get("name") for c in companies if c.get("name")]
    if names:
        video.studio = ",".join(names[:5])
    runtime = detail.get("runtime")
    if runtime:
        video.duration_minutes = int(runtime)


def _apply_tv_detail(db: Session, video: Video, detail: dict, client: TmdbClient) -> VideoSeries:
    video.tmdb_id = detail.get("id")
    video.media_type = "tv"
    show_title = detail.get("name") or video.title
    video.series_name = show_title
    video.is_series = True
    video.original_title = detail.get("original_name")
    video.overview = detail.get("overview")
    video.rating = detail.get("vote_average")
    video.vote_count = detail.get("vote_count")
    video.release_date = detail.get("first_air_date")
    if detail.get("first_air_date"):
        try:
            video.year = int(detail["first_air_date"][:4])
        except ValueError:
            pass
    genres = detail.get("genres") or []
    if genres:
        video.genre = ",".join(g.get("name") or "" for g in genres if g.get("name"))
    video.poster_url = client.image_url(detail.get("poster_path"))
    video.backdrop_url = client.image_url(detail.get("backdrop_path"))
    video.imdb_id = (detail.get("external_ids") or {}).get("imdb_id")
    # 同 _apply_movie_detail：只升不降，库级声明优先于 TMDB 的 adult 标记
    if detail.get("adult"):
        video.is_adult = True
    video.metadata_source = "tmdb"
    video.metadata_updated_at = datetime.now()
    video.status = detail.get("status")

    credits = detail.get("credits") or {}
    cast = credits.get("cast") or []
    if cast:
        video.actors = ",".join(c.get("name") or "" for c in cast[:8] if c.get("name"))
    creators = detail.get("created_by") or []
    if creators:
        video.director = ",".join(c.get("name") or "" for c in creators if c.get("name"))

    series = video.series
    if series is None:
        series = db.scalar(select(VideoSeries).where(VideoSeries.title == show_title))
    if series is None:
        series = VideoSeries(title=show_title)
        db.add(series)
        db.flush()
    series.title = show_title
    series.original_title = detail.get("original_name")
    series.overview = detail.get("overview")
    series.media_type = "tv"
    series.tmdb_id = detail.get("id")
    series.imdb_id = video.imdb_id
    series.rating = detail.get("vote_average")
    series.release_date = detail.get("first_air_date")
    series.year = video.year
    series.poster_url = video.poster_url
    series.backdrop_url = video.backdrop_url
    series.status = detail.get("status")
    # 只升不降：整剧只要有一集属于成人库，剧级就该是成人
    series.is_adult = bool(series.is_adult) or bool(video.is_adult)
    series.metadata_source = "tmdb"
    series.number_of_seasons = detail.get("number_of_seasons")
    series.total_episodes = detail.get("number_of_episodes")
    next_ep = detail.get("next_episode_to_air")
    if next_ep:
        series.next_episode_date = next_ep.get("air_date")
        if next_ep.get("season_number") is not None and next_ep.get("episode_number") is not None:
            series.next_episode_number = f"S{next_ep['season_number']:02d}E{next_ep['episode_number']:02d}"
    else:
        series.next_episode_date = None
        series.next_episode_number = None
    video.series = series
    video.series_id = series.id
    db.flush()
    _apply_episode_detail(db, video, detail, client)
    return series


def _apply_episode_detail(db: Session, video: Video, show_detail: dict, client: TmdbClient) -> None:
    """有季/集号时拉分集元数据，覆盖集标题/简介/时长，不覆盖剧集整体字段。"""
    # 不能用 `not video.season_number`：第 0 季（特别篇）是 0，falsy 会被直接
    # 跳过，特别篇的集标题/简介/时长/剧照就永远刮不到。
    if (
        video.season_number is None
        or video.episode_number is None
        or not show_detail.get("id")
    ):
        return
    ep = client.get_episode(show_detail["id"], video.season_number, video.episode_number)
    if not ep:
        return
    if ep.get("name"):
        video.title = ep["name"]
    if ep.get("overview"):
        video.overview = ep["overview"]
    if ep.get("air_date"):
        video.release_date = ep["air_date"]
    if ep.get("vote_average") is not None:
        video.rating = ep["vote_average"]
    if ep.get("vote_count") is not None:
        video.vote_count = ep["vote_count"]
    runtime = ep.get("runtime")
    if runtime:
        video.duration_minutes = int(runtime)
    still = ep.get("still_path")
    if still:
        video.poster_url = client.image_url(still, "w500")
        # 分集横屏用 TMDB 单集 still（每集独立），不再共用剧 backdrop
        video.backdrop_url = client.image_url(still, "original")
    else:
        # 无 still：留空，读取端回退总横屏，避免每集重复下载同一张剧图
        video.backdrop_url = None


def save_actors(db: Session, video: Video, cast: list[dict]) -> None:
    db.execute(delete(VideoActor).where(VideoActor.video_id == video.id))
    db.flush()
    for c in cast[:20]:
        db.add(
            VideoActor(
                video_id=video.id,
                name=c.get("name") or "",
                character=c.get("character"),
                image_url=TmdbClient().image_url(c.get("profile_path"), "w185"),
                source_actor_id=c.get("id"),
            )
        )
    db.flush()


def bind_series(db: Session, video_id: int, tmdb_id: int, media_type: str) -> list[Video]:
    from fryfrog.services.video_service import get_video

    video = get_video(db, video_id)
    client = TmdbClient()
    media_type = (media_type or "").lower()
    bound: list[Video] = []

    # 连带范围（务必保守，历史上这里按 title 全库匹配曾把 11 部无关剧的
    # S01E01 一起改成同一部电影）：
    # - 剧集：同 series_id 的全部分集；
    # - 电影/未入剧：仅限**同一目录**（同名分片，如 CD1/CD2）。
    if media_type == "tv" and video.series_id is not None:
        siblings = list(
            db.scalars(select(Video).where(Video.series_id == video.series_id)).all()
        )
    else:
        vid_dir = str(Path(video.file_path).parent)
        siblings = [
            v
            for v in db.scalars(
                select(Video).where(
                    Video.file_path.like(f"{_like_escape(vid_dir)}%", escape="\\")
                )
            ).all()
            if str(Path(v.file_path).parent) == vid_dir
        ]
    if video not in siblings:
        siblings.append(video)

    detail = client.get_movie(tmdb_id) if media_type == "movie" else client.get_tv(tmdb_id)
    if not detail:
        return [video]

    for v in siblings:
        if media_type == "movie":
            _apply_movie_detail(v, detail, client)
            credits = (detail.get("credits") or {}).get("cast") or []
            save_actors(db, v, credits)
        else:
            _apply_tv_detail(db, v, detail, client)
            credits = (detail.get("credits") or {}).get("cast") or []
            save_actors(db, v, credits)
        bound.append(v)
    if media_type != "movie":
        # 目标剧组必须在 _apply_tv_detail 之后取：siblings 是在它之前算出来的，
        # 那时 video.series_id 还是旧值，推不出绑定后的剧组。
        target = next((v.series_id for v in siblings if v.series_id is not None), None)
        if target is not None:
            _merge_split_series(db, siblings, target)
    db.flush()
    return bound


def _merge_split_series(db: Session, siblings: list[Video], target: int) -> int:
    """把「同一部剧被拆成多个 VideoSeries 行」的情况合并掉。

    为什么会拆：`_apply_tv_detail` 按**剧名**找系列行，剧名一变（例如重新刮削
    后从 `某某！！` 变成 `某某`）就找不到、于是新建一个剧组，同时把老行改名
    ——结果同一个目录树下的分集散落在两个剧组里，用户在剧详情里只看到其中一半
    （实测：特别篇被落在旧剧组，新剧显示 0 集）。

    判据刻意保守：**只合并剧名根目录（`<库>/<剧名>/`）相同的行**。
    注意不能按剧名匹配——被拆开的两行标题恰好不同（那正是它们被拆的原因）。

    [target] 是本次绑定后这批分集所属的系列行，所有同剧的散落分集都会并到它上面。
    """
    from sqlalchemy import update as sa_update

    def show_root(path: str | None):
        if not path:
            return None
        try:
            return Path(path).parent.parent
        except Exception:
            return None

    root = next((show_root(v.file_path) for v in siblings if show_root(v.file_path)), None)
    if root is None:
        return 0

    merged = 0
    for other in db.scalars(
        select(VideoSeries).where(
            VideoSeries.id != target, VideoSeries.media_type == "tv"
        )
    ).all():
        episodes = list(db.scalars(select(Video).where(Video.series_id == other.id)).all())
        # 有分集的：必须全部位于同一剧名根目录才并
        if episodes:
            if not all(show_root(e.file_path) == root for e in episodes):
                continue
        # 空壳行：没有分集可依据，只能用「剧名与目录名互为前缀」判断，
        # 否则会误删别的剧的空壳行。
        elif not _series_looks_like_show(other, root):
            continue
        db.execute(
            sa_update(Video).where(Video.series_id == other.id).values(series_id=target)
        )
        db.delete(other)
        merged += 1
        logger.info("合并被拆分的剧组: %r → series %s", other.title, target)
    if merged:
        db.flush()
    return merged


def _series_looks_like_show(series: VideoSeries, root: Path) -> bool:
    """空壳剧组是否属于这个剧名根目录：标题与目录名互为前缀即可。

    用于处理「剧名从 `某某！！` 变成 `某某` 后留下空壳」这种情形——两边没有
    分集可依据，只能靠剧名与目录名的相似度判断，因此要求前缀关系（比包含更严）。
    """
    title = (series.title or "").strip()
    if not title:
        return False
    name = root.name.strip()
    if not name:
        return False
    return title.startswith(name) or name.startswith(title)


def unbind_by_tmdb_id(db: Session, tmdb_id: int) -> int:
    videos = list(db.scalars(select(Video).where(Video.tmdb_id == tmdb_id)).all())
    for v in videos:
        v.tmdb_id = None
        v.metadata_source = None
        v.metadata_updated_at = None
        v.imdb_id = None
        v.rating = None
        v.vote_count = None
    series_list = list(db.scalars(select(VideoSeries).where(VideoSeries.tmdb_id == tmdb_id)).all())
    for s in series_list:
        s.tmdb_id = None
        s.metadata_source = None
        s.imdb_id = None
        s.rating = None
    db.flush()
    return len(videos)


def rescrape_video(db: Session, video_id: int) -> list[Video]:
    from fryfrog.services.video_service import get_video

    video = get_video(db, video_id)
    queries = search_queries(video.file_name, video.series_name or video.title)
    # 优先同类型；同名多条时按库级成人标记取舍
    pick = search_tmdb_best(
        queries, (video.media_type or "").lower(), adult_pref=bool(video.is_adult)
    )
    if not pick:
        return [video]
    return bind_series(db, video_id, pick["id"], pick["mediaType"] or "movie")


def rescrape_by_library(db: Session, library_id: int) -> int:
    """批量刷新该库**已绑定**视频的元数据（安全语义，不做搜索）。

    历史实现是「先把所有绑定 unbind，再按文件名 search_tmdb_best 重绑」，有两个
    严重问题：
      1. 原本正确的绑定被清掉后重搜，搜到别的条目就**绑错了**；
      2. 对**未绑定**的视频也强行搜索——而用户把某些视频留在未刮削状态，
         正是因为 TMDB 上根本没有它们，强搜只会写入错误内容。

    现在只处理有 `tmdb_id` 的记录，并**用已知 ID 拉取**（不搜索、不清绑定）。
    代价是绑错的条目修不了——那属于逐个手动重绑的场景。

    返回实际处理的视频数。
    """
    return refresh_bound_by_library(db, library_id)["refreshed"]


def refresh_bound_by_library(
    db: Session, library_id: int, on_progress=None
) -> dict:
    """用**已有 tmdb_id** 刷新该库已绑定的视频，不搜索、不改绑定。

    与 `rescrape_by_library` 的区别：那个会先清掉绑定再按文件名重搜，可能把
    正确的绑定改坏，也会去搜用户刻意未绑定的视频。这里只认已绑定的记录。

    [on_progress] 可选回调 `(refreshed, skipped, failed, total)`：每处理完一部剧
    （或一个独立视频）调用一次。库大时整批可能跑几分钟，没有它界面会一直显示
    0%（实测 400+ 部剧的库跑了 4 分钟仍显示 0，看起来像卡死）。

    返回 {"refreshed": n, "skipped": 未绑定数, "failed": 失败数}。
    """
    from fryfrog.services import video_service as vs

    videos = list(db.scalars(select(Video).where(Video.library_id == library_id)).all())
    bound = [v for v in videos if v.tmdb_id]
    skipped = len(videos) - len(bound)

    # 按 series_id 聚合：整剧只需取一次详情，避免每集重复请求
    series_ids = sorted({v.series_id for v in bound if v.series_id is not None})
    solo = [v for v in bound if v.series_id is None]
    total = len(series_ids) + len(solo)

    refreshed = failed = 0
    client = TmdbClient()

    def report() -> None:
        if on_progress is None:
            return
        try:
            on_progress(refreshed, skipped, failed, total)
        except Exception:
            logger.debug("刷新进度回调失败", exc_info=True)

    report()

    for series_id in series_ids:
        series = vs.get_series(db, series_id)
        episodes = vs.series_videos(db, series_id) or []
        if series is None or not series.tmdb_id or not episodes:
            failed += 1
        else:
            try:
                detail = client.get_tv(series.tmdb_id)
                if not detail:
                    failed += 1
                else:
                    for ep in episodes:
                        _apply_tv_detail(db, ep, detail, client)
                    # 剧根素材 + 剧级/季级 NFO 都在这一步里（detail 复用，零额外请求）
                    assets.download_series_root_art(db, series, episodes, detail)
                    for ep in episodes:
                        assets.ensure_season_poster(db, ep)
                        assets.ensure_season_nfo_from_detail(db, ep, detail)
                        assets.download_all_covers(db, ep, force=False)
                        generate_nfo(db, ep)
                        refreshed += 1
            except Exception:
                logger.exception("刷新剧集失败: series=%s", series_id)
                failed += 1
        report()
        try:
            # 走重试版提交：裸 commit 撞上别的写者会直接抛错中断整批刷新
            from fryfrog.core.deps import commit_with_retry

            commit_with_retry(db)
        except Exception:
            logger.warning("刷新中途提交失败，继续", exc_info=True)
            db.rollback()

    for video in solo:
        try:
            if (video.media_type or "").lower() == "movie":
                detail = client.get_movie(video.tmdb_id)
                if not detail:
                    failed += 1
                    report()
                    continue
                _apply_movie_detail(video, detail, client)
            else:
                detail = client.get_tv(video.tmdb_id)
                if not detail:
                    failed += 1
                    report()
                    continue
                _apply_tv_detail(db, video, detail, client)
            video.metadata_updated_at = datetime.now()
            assets.download_all_covers(db, video, force=False)
            generate_nfo(db, video)
            refreshed += 1
        except Exception:
            logger.exception("刷新视频失败: %s", video.file_name)
            failed += 1
        report()

    db.flush()
    logger.info(
        "[Refresh] library=%s 刷新 %s / 跳过未绑定 %s / 失败 %s",
        library_id,
        refreshed,
        skipped,
        failed,
    )
    return {"refreshed": refreshed, "skipped": skipped, "failed": failed}


def scrape_video_if_needed(db: Session, video: Video) -> None:
    """扫描时可选刮削。"""
    from fryfrog.config import get_settings

    settings = get_settings()
    if not settings.tmdb_api_key:
        return
    if video.tmdb_id:
        return
    queries = search_queries(video.file_name, video.series_name or video.title)
    media_pref = (video.media_type or "").lower() or ("tv" if video.is_series else "movie")
    # 同名多条时按库级成人标记取舍（成人库里优先成人条目）
    pick = search_tmdb_best(queries, media_pref, adult_pref=bool(video.is_adult))
    if not pick:
        return
    try:
        bind_series(db, video.id, pick["id"], pick["mediaType"] or media_pref)
        from fryfrog.services import video_assets as assets

        assets.upgrade_legacy_assets(db, video)
        generate_nfo(db, video)
        # 先补齐季竖海报再做分集封面：download_all_covers 见到「已有共享竖图」
        # 就会跳过下载分集竖封面，季目录里没图时就会退化成截帧（特别篇即如此）
        assets.ensure_season_poster(db, video)
        download_all_covers(db, video, force=False)
    except Exception:
        logger.exception("扫描刮削失败: %s", video.file_name)


# -------------------- 演员详情 --------------------

def actor_image_url(actor: VideoActor) -> str | None:
    from fryfrog.core.signer import sign

    if actor.image_path:
        from pathlib import Path

        if Path(actor.image_path).exists():
            return sign(f"/api/v1/video/actor/{actor.id}/image")
    return sign(f"/api/v1/video/actor/{actor.id}/image")


def get_actor_detail(db: Session, actor: VideoActor, refresh: bool = False) -> dict:
    import json

    profile = db.scalar(select(ActorProfile).where(ActorProfile.actor_id == actor.id))
    now = datetime.now()
    need_fetch = (
        refresh
        or profile is None
        or profile.fetched_at is None
        or (now - profile.fetched_at).days >= 7
    )
    if need_fetch and actor.source_actor_id:
        client = TmdbClient()
        person = client.get_person(actor.source_actor_id)
        if person:
            if profile is None:
                profile = ActorProfile(actor_id=actor.id)
                db.add(profile)
            profile.tmdb_id = person.get("id")
            profile.name = person.get("name") or actor.name
            profile.biography = person.get("biography")
            profile.also_known_as_json = json.dumps(person.get("also_known_as") or [], ensure_ascii=False)
            profile.birthday = person.get("birthday")
            profile.deathday = person.get("deathday")
            profile.gender = person.get("gender")
            profile.place_of_birth = person.get("place_of_birth")
            profile.homepage = person.get("homepage")
            profile.imdb_id = person.get("imdb_id")
            profile.known_for_department = person.get("known_for_department")
            profile.popularity = person.get("popularity")
            combined = person.get("combined_credits") or {}
            profile.cast_json = json.dumps(combined.get("cast") or [], ensure_ascii=False)
            profile.crew_json = json.dumps(combined.get("crew") or [], ensure_ascii=False)
            profile.fetched_at = now
            db.flush()

    if profile is None:
        return {
            "id": actor.id,
            "name": actor.name,
            "imageUrl": actor_image_url(actor),
            "castCount": 0,
            "crewCount": 0,
            "totalCredits": 0,
            "knownFor": [],
            "credits": {"cast": [], "crew": []},
        }

    cast_list = json.loads(profile.cast_json or "[]")
    crew_list = json.loads(profile.crew_json or "[]")
    also_known = json.loads(profile.also_known_as_json or "[]")
    gender_map = {1: "女", 2: "男", 3: "非二元", 0: "未设置"}

    def to_credit(c: dict, is_cast: bool) -> dict:
        media = c.get("media_type") or ("tv" if c.get("first_air_date") else "movie")
        release = c.get("release_date") or c.get("first_air_date")
        year = None
        if release and len(release) >= 4:
            try:
                year = int(release[:4])
            except ValueError:
                pass
        title = c.get("title") or c.get("name")
        original = c.get("original_title") or c.get("original_name")
        poster = c.get("poster_path")
        return {
            "id": c.get("id"),
            "mediaType": media,
            "title": title,
            "originalTitle": original,
            "character": c.get("character") if is_cast else None,
            "job": c.get("job") if not is_cast else None,
            "department": c.get("department") if not is_cast else None,
            "releaseDate": release,
            "year": year,
            "posterUrl": (
                f"/api/v1/video/tmdb-image-proxy?path={poster}&size=w342" if poster else None
            ),
            "overview": c.get("overview"),
            "voteAverage": c.get("vote_average"),
            "voteCount": c.get("vote_count"),
            "episodeCount": c.get("episode_count"),
            "adult": c.get("adult"),
        }

    cast_credits = [to_credit(c, True) for c in cast_list]
    crew_credits = [to_credit(c, False) for c in crew_list]
    known = sorted(cast_credits, key=lambda x: x.get("voteCount") or 0, reverse=True)[:10]

    return {
        "id": actor.id,
        "name": profile.name or actor.name,
        "imageUrl": actor_image_url(actor),
        "tmdbId": profile.tmdb_id,
        "biography": profile.biography,
        "alsoKnownAs": also_known,
        "birthday": profile.birthday,
        "deathday": profile.deathday,
        "gender": profile.gender,
        "genderLabel": gender_map.get(profile.gender or 0),
        "placeOfBirth": profile.place_of_birth,
        "homepage": profile.homepage,
        "imdbId": profile.imdb_id,
        "knownForDepartment": profile.known_for_department,
        "popularity": profile.popularity,
        "castCount": len(cast_credits),
        "crewCount": len(crew_credits),
        "totalCredits": len(cast_credits) + len(crew_credits),
        "knownFor": known,
        "credits": {"cast": cast_credits, "crew": crew_credits},
    }
