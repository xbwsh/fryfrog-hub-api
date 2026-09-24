from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel


class UserDTO(BaseModel):
    id: int
    username: str
    nickname: str | None = None
    avatar: str | None = None
    role: str | None = None
    enabled: bool = False
    createdAt: datetime | None = None
    lastLoginAt: datetime | None = None

    model_config = {"from_attributes": True}

    @classmethod
    def from_user(cls, user) -> "UserDTO":
        return cls(
            id=user.id,
            username=user.username,
            nickname=user.nickname,
            avatar=user.avatar,
            role=user.role.value if user.role else None,
            enabled=bool(user.enabled),
            createdAt=user.created_at,
            lastLoginAt=user.last_login_at,
        )


class LoginRequest(BaseModel):
    username: str | None = None
    password: str = ""


class UserCreateRequest(BaseModel):
    username: str
    password: str
    nickname: str | None = None
    role: str | None = None


class UserUpdateRequest(BaseModel):
    nickname: str | None = None
    avatar: str | None = None
    role: str | None = None
    enabled: bool | None = None


class ChangePasswordRequest(BaseModel):
    oldPassword: str | None = None
    newPassword: str


class UserLibraryUpdateRequest(BaseModel):
    libraryIds: list[int] = []


class UserPreferenceUpdateRequest(BaseModel):
    # key -> value，空串表示删除
    preferences: dict[str, str] = {}


class SettingUpdateRequest(BaseModel):
    value: str | None = None
    description: str | None = None


class MediaLibraryCreateRequest(BaseModel):
    name: str
    path: str
    type: str
    subType: str | None = None
    enabled: bool | None = None
    enableScraping: bool | None = None
    isAdult: bool | None = None
    sortOrder: int | None = None
    description: str | None = None


class MediaLibraryUpdateRequest(BaseModel):
    name: str | None = None
    path: str | None = None
    type: str | None = None
    subType: str | None = None
    enabled: bool | None = None
    enableScraping: bool | None = None
    isAdult: bool | None = None
    sortOrder: int | None = None
    description: str | None = None


class MediaLibraryDTO(BaseModel):
    id: int
    name: str
    path: str
    type: str
    subType: str | None = None
    enabled: bool = True
    enableScraping: bool | None = None
    isAdult: bool | None = None
    sortOrder: int | None = None
    description: str | None = None

    @classmethod
    def from_entity(cls, lib) -> "MediaLibraryDTO":
        return cls(
            id=lib.id,
            name=lib.name,
            path=lib.path,
            type=lib.type,
            subType=lib.sub_type,
            enabled=bool(lib.enabled),
            enableScraping=lib.enable_scraping,
            isAdult=lib.is_adult,
            sortOrder=lib.sort_order,
            description=lib.description,
        )
