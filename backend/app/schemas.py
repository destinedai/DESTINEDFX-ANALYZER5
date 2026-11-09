from datetime import datetime
from typing import Any
from pydantic import BaseModel, ConfigDict, EmailStr, Field


class Credentials(BaseModel):
    email: EmailStr
    password: str = Field(min_length=12, max_length=128)


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    email: EmailStr
    created_at: datetime


class TokenOut(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_at: datetime
    user: UserOut


class PreferenceIn(BaseModel):
    value: dict[str, Any]


class DeviceIn(BaseModel):
    device_id: str = Field(min_length=1, max_length=128)
    platform: str = Field(pattern="^(android|ios|web)$")
    app_version: str | None = Field(default=None, max_length=40)


class AuditOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    actor_user_id: int | None
    action: str
    correlation_id: str
    created_at: datetime
    details: str | None
