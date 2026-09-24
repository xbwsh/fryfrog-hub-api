from __future__ import annotations

import logging
from datetime import datetime

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from fryfrog.models.video import ActorProfile, Video, VideoActor, VideoSeries
from fryfrog.services.tmdb import TmdbClient
from fryfrog.services.video_assets import download_all_covers, generate_nfo

logger = logging.getLogger(__name__)


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
    video.is_adult = bool(detail.get("adult"))
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
    video.is_adult = bool(detail.get("adult"))
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
    series.is_adult = video.is_adult
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
    if not video.season_number or not video.episode_number or not show_detail.get("id"):
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

    # 同标题视频一并绑定（剧集整季）
    siblings = list(
        db.scalars(select(Video).where(Video.title == video.title)).all()
    ) or [video]
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
    db.flush()
    return bound


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
    title = video.title or video.series_name or video.file_name
    results = search_tmdb(title)
    if not results:
        return [video]
    # 优先同类型
    media_pref = (video.media_type or "").lower()
    pick = next((r for r in results if r.get("mediaType") == media_pref), results[0])
    return bind_series(db, video_id, pick["id"], pick["mediaType"] or "movie")


def rescrape_by_library(db: Session, library_id: int) -> int:
    videos = list(db.scalars(select(Video).where(Video.library_id == library_id)).all())
    for v in videos:
        if v.tmdb_id:
            unbind_by_tmdb_id(db, v.tmdb_id)
    count = 0
    for v in videos:
        try:
            rescrape_video(db, v.id)
            count += 1
        except Exception:
            logger.exception("重新刮削失败: %s", v.file_name)
    return count


def scrape_video_if_needed(db: Session, video: Video) -> None:
    """扫描时可选刮削。"""
    from fryfrog.config import get_settings

    settings = get_settings()
    if not settings.tmdb_api_key:
        return
    if video.tmdb_id:
        return
    title = video.series_name or video.title
    results = search_tmdb(title)
    if not results:
        return
    media_pref = (video.media_type or "").lower() or ("tv" if video.is_series else "movie")
    pick = next((r for r in results if r.get("mediaType") == media_pref), results[0])
    try:
        bind_series(db, video.id, pick["id"], pick["mediaType"] or media_pref)
        generate_nfo(db, video)
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
