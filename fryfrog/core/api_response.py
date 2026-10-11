from __future__ import annotations

from typing import Any, Generic, TypeVar

from pydantic import BaseModel

T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    success: bool
    message: str | None = None
    data: T | None = None

    # 注意：`exclude_none` **不是** pydantic v2 的合法 ConfigDict 键，写在这里
    # 完全不生效（不会报错，也不会有任何效果）—— 它是序列化参数
    # `model_dump(exclude_none=True)` / FastAPI 的 `response_model_exclude_none=True`。
    # 保留这行只为记录原意图；实际响应始终带 message/data 两个字段（为 null）。
    # 若要真的省略 null 字段，需在各路由上加 response_model_exclude_none=True，
    # 那会改变所有响应的 JSON 结构，需同步确认前端。
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
