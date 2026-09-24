from __future__ import annotations

from typing import Any, Generic, TypeVar

from pydantic import BaseModel

T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    success: bool
    message: str | None = None
    data: T | None = None

    model_config = {"exclude_none": True}

    @classmethod
    def ok(cls, data: Any = None, message: str | None = None) -> "ApiResponse":
        return cls(success=True, message=message, data=data)

    @classmethod
    def error(cls, message: str) -> "ApiResponse":
        return cls(success=False, message=message)


class PageResponse(BaseModel, Generic[T]):
    content: list[T]
    page: int
    size: int
    totalElements: int
    totalPages: int

    @classmethod
    def of(cls, content: list, page: int, size: int, total_elements: int) -> "PageResponse":
        total_pages = (total_elements + size - 1) // size if size > 0 else 0
        return cls(
            content=content,
            page=page,
            size=size,
            totalElements=total_elements,
            totalPages=total_pages,
        )
