from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel


def resolution_label(resolution: str | None) -> str | None:
    if not resolution:
        return None
    try:
        parts = resolution.lower().split("x")
        if len(parts) != 2:
            return None
        width, height = int(parts[0].strip()), int(parts[1].strip())
        longer = max(width, height)
        if longer >= 3800:
            return "4K"
        if longer >= 2500:
            return "2K"
        if longer >= 1800:
            return "1080p"
        if longer >= 1200:
            return "720p"
        if longer >= 700:
            return "480p"
        return f"{height}p"
    except ValueError:
        return None


_RESOLUTION_RANK = {"4K": 0, "2K": 1, "1080p": 2, "720p": 3, "480p": 4}


def collect_resolutions(labels: list[str | None]) -> list[str]:
    seen: list[str] = []
    for label in labels:
        if label and label not in seen:
            seen.append(label)
    return sorted(seen, key=lambda x: _RESOLUTION_RANK.get(x, 5))


class VideoMetadataUpdateRequest(BaseModel):
    title: str | None = None
    overview: str | None = None
    rating: float | None = None
    year: int | None = None
    releaseDate: str | None = None
    genre: str | None = None
    director: str | None = None
    actors: str | None = None
    originalTitle: str | None = None
    tags: str | None = None


class SeriesMetadataUpdateRequest(BaseModel):
    title: str | None = None
    overview: str | None = None
    rating: float | None = None
    year: int | None = None
    releaseDate: str | None = None
    originalTitle: str | None = None
    status: str | None = None


class UpdatePositionRequest(BaseModel):
    position: float
    duration: float | None = None


class UpdateWatchedRequest(BaseModel):
    completed: bool | None = None


class VideoBindRequest(BaseModel):
    tmdbId: int
    mediaType: str


class LogoSelectRequest(BaseModel):
    filePath: str | None = None


class FrameSelectRequest(BaseModel):
    index: int = 0
    type: str | None = None


class SeriesFrameSelectRequest(BaseModel):
    videoId: int | None = None
    index: int = 0


class WatchProgressDTO(BaseModel):
    videoId: int
    positionSeconds: float | None = None
    durationSeconds: float | None = None
    completed: bool | None = None
    progressPercent: float = 0.0
    updatedAt: datetime | None = None

    @classmethod
    def from_entity(cls, progress) -> "WatchProgressDTO":
        percent = 0.0
        if (
            progress.position_seconds is not None
            and progress.duration_seconds
            and progress.duration_seconds > 0
        ):
            percent = progress.position_seconds / progress.duration_seconds * 100
        return cls(
            videoId=progress.video_id,
            positionSeconds=progress.position_seconds,
            durationSeconds=progress.duration_seconds,
            completed=progress.completed,
            progressPercent=percent,
            updatedAt=progress.updated_at,
        )


class VideoDTO(BaseModel):
    id: int
    title: str
    coverUrl: str | None = None
    fanartUrl: str | None = None
    logoUrl: str | None = None
    streamUrl: str | None = None
    originalTitle: str | None = None
    director: str | None = None
    actors: str | None = None
    genre: str | None = None
    year: int | None = None
    releaseDate: str | None = None
    durationMinutes: int | None = None
    overview: str | None = None
    fileName: str | None = None
    originalFileName: str | None = None
    fileSize: int | None = None
    format: str | None = None
    resolution: str | None = None
    resolutionLabel: str | None = None
    favorite: bool | None = None
    tmdbId: int | None = None
    mediaType: str | None = None
    imdbId: str | None = None
    rating: float | None = None
    voteCount: int | None = None
    status: str | None = None
    metadataSource: str | None = None
    metadataUpdatedAt: datetime | None = None
    hasMetadataDir: bool | None = None
    hasNfo: bool | None = None
    hasPoster: bool | None = None
    hasFanart: bool | None = None
    scraped: bool | None = None
    isSeries: bool | None = None
    libraryId: int | None = None
    seriesId: int | None = None
    seriesTitle: str | None = None
    seasonNumber: int | None = None
    episodeNumber: int | None = None
    watchPosition: float | None = None
    watchProgressPercent: float | None = None
    watched: bool | None = None
    isAdult: bool | None = None

    @classmethod
    def from_entity(
        cls,
        video,
        *,
        has_nfo: bool = False,
        has_poster: bool = False,
        has_fanart: bool = False,
        has_metadata_dir: bool = False,
        favorite: bool = False,
        logo_url: str | None = None,
    ) -> "VideoDTO":
        from fryfrog.core.signer import sign

        vid = video.id
        return cls(
            id=vid,
            title=video.title,
            coverUrl=sign(f"/api/v1/video/{vid}/cover"),
            fanartUrl=sign(f"/api/v1/video/{vid}/fanart"),
            logoUrl=logo_url,
            streamUrl=sign(f"/api/v1/video/{vid}/stream"),
            originalTitle=video.original_title,
            director=video.director,
            actors=video.actors,
            genre=video.genre,
            year=video.year,
            releaseDate=video.release_date,
            durationMinutes=video.duration_minutes,
            overview=video.overview,
            fileName=video.file_name,
            originalFileName=video.original_file_name,
            fileSize=video.file_size,
            format=video.format,
            resolution=video.resolution,
            resolutionLabel=resolution_label(video.resolution),
            favorite=favorite,
            tmdbId=video.tmdb_id,
            mediaType=video.media_type,
            imdbId=video.imdb_id,
            rating=video.rating,
            voteCount=video.vote_count,
            status=video.status,
            metadataSource=video.metadata_source,
            metadataUpdatedAt=video.metadata_updated_at,
            hasMetadataDir=has_metadata_dir,
            hasNfo=has_nfo,
            hasPoster=has_poster,
            hasFanart=has_fanart,
            scraped=video.tmdb_id is not None,
            isSeries=video.is_series,
            isAdult=video.is_adult,
            libraryId=video.library_id,
            seasonNumber=video.season_number,
            episodeNumber=video.episode_number,
            seriesId=video.series.id if video.series else None,
            seriesTitle=video.series.title if video.series else None,
        )


class SeasonDTO(BaseModel):
    seasonNumber: int
    coverUrl: str | None = None
    episodes: list[VideoDTO] = []

    @classmethod
    def of(cls, series_id: int | None, season_number: int, episodes: list[VideoDTO]) -> "SeasonDTO":
        from fryfrog.core.signer import sign

        cover = None
        if series_id is not None:
            cover = sign(f"/api/v1/video/series/{series_id}/season/{season_number}/cover")
        return cls(seasonNumber=season_number, coverUrl=cover, episodes=episodes)


class SeriesDTO(BaseModel):
    id: int
    type: str
    title: str
    coverUrl: str | None = None
    fanartUrl: str | None = None
    logoUrl: str | None = None
    originalTitle: str | None = None
    overview: str | None = None
    mediaType: str | None = None
    tmdbId: int | None = None
    rating: float | None = None
    year: int | None = None
    releaseDate: str | None = None
    seasonNumber: int | None = None
    numberOfSeasons: int | None = None
    totalEpisodes: int | None = None
    status: str | None = None
    isAdult: bool | None = None
    favorite: bool | None = None
    episodeCount: int | None = None
    seasons: list[SeasonDTO] = []
    resolutions: list[str] = []

    @classmethod
    def from_entity(cls, series, episodes: list[VideoDTO], favorite: bool) -> "SeriesDTO":
        from fryfrog.core.signer import sign

        sid = series.id
        by_season: dict[int, list[VideoDTO]] = {}
        for ep in episodes:
            by_season.setdefault(ep.seasonNumber or 1, []).append(ep)
        seasons = [
            SeasonDTO.of(sid, num, by_season[num]) for num in sorted(by_season.keys())
        ]
        return cls(
            id=sid,
            type="series",
            title=series.title,
            coverUrl=sign(f"/api/v1/video/series/{sid}/cover"),
            fanartUrl=sign(f"/api/v1/video/series/{sid}/fanart"),
            logoUrl=_series_logo_url(series),
            originalTitle=series.original_title,
            overview=series.overview,
            mediaType=series.media_type,
            tmdbId=series.tmdb_id,
            rating=series.rating,
            year=series.year,
            releaseDate=series.release_date,
            seasonNumber=series.season_number,
            numberOfSeasons=series.number_of_seasons,
            totalEpisodes=series.total_episodes,
            status=series.status,
            isAdult=series.is_adult,
            favorite=favorite,
            episodeCount=len(episodes),
            seasons=seasons,
            resolutions=collect_resolutions([e.resolutionLabel for e in episodes]),
        )

    @classmethod
    def from_standalone_video(cls, video, episode: VideoDTO, favorite: bool) -> "SeriesDTO":
        from fryfrog.core.signer import sign

        vid = video.id
        label = resolution_label(video.resolution)
        return cls(
            id=vid,
            type="standalone",
            title=video.title,
            coverUrl=sign(f"/api/v1/video/{vid}/cover"),
            fanartUrl=sign(f"/api/v1/video/{vid}/fanart"),
            logoUrl=_video_logo_url(video),
            originalTitle=video.original_title,
            overview=video.overview,
            mediaType=video.media_type,
            tmdbId=video.tmdb_id,
            rating=video.rating,
            year=video.year,
            releaseDate=video.release_date,
            seasonNumber=None,
            numberOfSeasons=None,
            totalEpisodes=1,
            status=video.status,
            favorite=favorite,
            episodeCount=1,
            seasons=[SeasonDTO.of(None, 1, [episode])],
            resolutions=[label] if label else [],
        )


class SeriesListDTO(BaseModel):
    id: int
    type: str
    title: str
    coverUrl: str | None = None
    fanartUrl: str | None = None
    logoUrl: str | None = None
    originalTitle: str | None = None
    mediaType: str | None = None
    rating: float | None = None
    year: int | None = None
    releaseDate: str | None = None
    numberOfSeasons: int | None = None
    totalEpisodes: int | None = None
    episodeCount: int | None = None
    isAdult: bool | None = None
    favorite: bool | None = None
    hasAdultEpisodes: bool | None = None
    resolutions: list[str] = []

    @classmethod
    def from_entity(cls, series, episodes: list, favorite: bool) -> "SeriesListDTO":
        from fryfrog.core.signer import sign

        sid = series.id
        labels = [resolution_label(getattr(v, "resolution", None)) for v in episodes]
        return cls(
            id=sid,
            type="series",
            title=series.title,
            coverUrl=sign(f"/api/v1/video/series/{sid}/cover"),
            fanartUrl=sign(f"/api/v1/video/series/{sid}/fanart"),
            logoUrl=_series_logo_url(series),
            originalTitle=series.original_title,
            mediaType=series.media_type,
            rating=series.rating,
            year=series.year,
            releaseDate=series.release_date,
            numberOfSeasons=series.number_of_seasons,
            totalEpisodes=series.total_episodes,
            episodeCount=series.total_episodes if series.total_episodes is not None else len(episodes),
            isAdult=series.is_adult,
            favorite=favorite,
            hasAdultEpisodes=any(bool(getattr(v, "is_adult", False)) for v in episodes),
            resolutions=collect_resolutions(labels),
        )

    @classmethod
    def from_standalone_video(cls, video, favorite: bool) -> "SeriesListDTO":
        from fryfrog.core.signer import sign

        vid = video.id
        label = resolution_label(video.resolution)
        return cls(
            id=vid,
            type="standalone",
            title=video.title,
            coverUrl=sign(f"/api/v1/video/{vid}/cover"),
            fanartUrl=sign(f"/api/v1/video/{vid}/fanart"),
            logoUrl=_video_logo_url(video),
            originalTitle=video.original_title,
            mediaType=video.media_type,
            rating=video.rating,
            year=video.year,
            numberOfSeasons=None,
            totalEpisodes=1,
            episodeCount=1,
            isAdult=video.is_adult,
            favorite=favorite,
            hasAdultEpisodes=bool(video.is_adult),
            resolutions=[label] if label else [],
        )


class LibrarySeriesGroupDTO(BaseModel):
    libraryId: int | None = None
    libraryName: str | None = None
    libraryPath: str | None = None
    subType: str | None = None
    series: list[SeriesListDTO] = []
    standaloneVideos: list[SeriesListDTO] = []
    seriesCount: int = 0
    standaloneCount: int = 0


class Credit(BaseModel):
    id: int | None = None
    mediaType: str | None = None
    title: str | None = None
    originalTitle: str | None = None
    character: str | None = None
    job: str | None = None
    department: str | None = None
    releaseDate: str | None = None
    year: int | None = None
    posterUrl: str | None = None
    overview: str | None = None
    voteAverage: float | None = None
    voteCount: int | None = None
    episodeCount: int | None = None
    adult: bool | None = None


class CreditList(BaseModel):
    cast: list[Credit] = []
    crew: list[Credit] = []


class ActorDetailDTO(BaseModel):
    id: int
    name: str | None = None
    imageUrl: str | None = None
    tmdbId: int | None = None
    biography: str | None = None
    alsoKnownAs: list[str] = []
    birthday: str | None = None
    deathday: str | None = None
    gender: int | None = None
    genderLabel: str | None = None
    placeOfBirth: str | None = None
    homepage: str | None = None
    imdbId: str | None = None
    knownForDepartment: str | None = None
    popularity: float | None = None
    castCount: int = 0
    crewCount: int = 0
    totalCredits: int = 0
    knownFor: list[Credit] = []
    credits: CreditList = CreditList()


class LogoOption(BaseModel):
    filePath: str | None = None
    iso6391: str | None = None
    width: int | None = None
    height: int | None = None
    voteCount: int | None = None
    url: str | None = None


class TmdbSearchItem(BaseModel):
    id: int | None = None
    title: str | None = None
    originalTitle: str | None = None
    overview: str | None = None
    releaseDate: str | None = None
    year: int | None = None
    posterPath: str | None = None
    backdropPath: str | None = None
    genreIds: list[int] = []
    voteAverage: float | None = None
    voteCount: int | None = None
    mediaType: str | None = None
    popularity: float | None = None
    adult: bool | None = None


def _video_logo_url(video) -> str | None:
    from pathlib import Path

    from fryfrog.core.signer import sign

    if video.logo_local_path and Path(video.logo_local_path).exists():
        return sign(f"/api/v1/video/{video.id}/logo")
    return None


def _series_logo_url(series) -> str | None:
    from pathlib import Path

    from fryfrog.core.signer import sign

    if series.logo_local_path and Path(series.logo_local_path).exists():
        return sign(f"/api/v1/video/series/{series.id}/logo")
    return None


def apply_watch_progress(dto: VideoDTO, progress) -> VideoDTO:
    if progress is None:
        return dto
    dto.watchPosition = progress.position_seconds
    dto.watched = progress.completed
    if progress.duration_seconds and progress.duration_seconds > 0 and progress.position_seconds is not None:
        dto.watchProgressPercent = progress.position_seconds / progress.duration_seconds * 100
    return dto


def dump(obj: BaseModel) -> dict[str, Any]:
    return obj.model_dump()
