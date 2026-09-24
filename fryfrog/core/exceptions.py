from __future__ import annotations


class ResourceNotFoundException(Exception):
    def __init__(self, resource: str, field: str, value):
        super().__init__(f"{resource} not found: {field}={value}")
        self.message = f"{resource} not found: {field}={value}"


class ForbiddenException(Exception):
    def __init__(self, message: str = "Forbidden"):
        super().__init__(message)
        self.message = message


class BadRequestException(Exception):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message
