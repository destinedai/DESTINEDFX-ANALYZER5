import hashlib
import logging
import re
import secrets
import time
from contextlib import asynccontextmanager
from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone
from uuid import uuid4
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from . import models
from .config import get_settings
from .db import SessionLocal, engine
from .schemas import AuditOut, Credentials, DeviceIn, PreferenceIn, TokenOut, UserOut

settings = get_settings()
log = logging.getLogger("app")
hasher = PasswordHasher()
@asynccontextmanager
async def lifespan(_app: FastAPI):
    yield
    engine.dispose()


app = FastAPI(title="App Management API", version="1.0.0", docs_url=None, redoc_url=None, lifespan=lifespan)
rate_buckets: dict[str, deque[float]] = defaultdict(deque)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


@app.middleware("http")
async def guard_requests(request: Request, call_next):
    correlation = request.headers.get("X-Correlation-ID", "")
    if not re.fullmatch(r"[A-Za-z0-9._:-]{1,64}", correlation):
        correlation = str(uuid4())
    request.state.correlation_id = correlation
    size = request.headers.get("content-length")
    if size and (not size.isdigit() or int(size) > settings.max_request_bytes):
        return JSONResponse(status_code=413, content={"error": {"code": "request_too_large", "message": "Request body too large", "correlation_id": correlation}}, headers={"X-Correlation-ID": correlation})
    now = time.monotonic()
    bucket = rate_buckets[request.client.host if request.client else "unknown"]
    while bucket and bucket[0] < now - 60:
        bucket.popleft()
    if len(bucket) >= settings.rate_limit_per_minute:
        return JSONResponse(status_code=429, content={"error": {"code": "rate_limited", "message": "Too many requests", "correlation_id": correlation}}, headers={"X-Correlation-ID": correlation})
    bucket.append(now)
    try:
        response = await call_next(request)
    except Exception:
        # Keep logs free of exception payloads, SQL parameters, and request contents.
        log.error("Unhandled request failure correlation_id=%s", correlation)
        response = JSONResponse(status_code=500, content={"error": {"code": "internal_error", "message": "Internal server error", "correlation_id": correlation}})
    response.headers["X-Correlation-ID"] = correlation
    return response


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError):
    return JSONResponse(status_code=422, content={"error": {"code": "validation_error", "message": "Invalid request", "correlation_id": getattr(request.state, "correlation_id", "")}})


@app.exception_handler(HTTPException)
async def http_error(request: Request, exc: HTTPException):
    codes = {401: "unauthorized", 403: "forbidden", 404: "not_found", 409: "conflict", 413: "request_too_large", 422: "validation_error", 429: "rate_limited"}
    return JSONResponse(status_code=exc.status_code, headers=exc.headers, content={"error": {"code": codes.get(exc.status_code, "request_error"), "message": str(exc.detail), "correlation_id": getattr(request.state, "correlation_id", "")}})


def audit(db, request, actor_id, action, details=None):
    db.add(models.AuditEvent(actor_user_id=actor_id, action=action, correlation_id=request.state.correlation_id, details=details))


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def current_user(authorization: str | None = Header(default=None), db: Session = Depends(get_db)):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Authentication required")
    raw = authorization[7:]
    if not 32 <= len(raw) <= 256:
        raise HTTPException(401, "Invalid or expired session")
    record = db.scalar(select(models.SessionToken).where(models.SessionToken.token_hash == token_hash(raw)))
    now = datetime.now(timezone.utc)
    expires_at = record.expires_at if record else None
    if expires_at and expires_at.tzinfo is None:  # SQLite test fixtures return naive datetimes.
        expires_at = expires_at.replace(tzinfo=timezone.utc)
    if not record or record.revoked_at or expires_at <= now:
        raise HTTPException(401, "Invalid or expired session")
    user = db.get(models.User, record.user_id)
    if not user:
        raise HTTPException(401, "Invalid or expired session")
    return user


def admin_user(user: models.User = Depends(current_user)):
    if not user.is_admin:
        raise HTTPException(403, "Administrator access required")
    return user


@app.post("/api/v1/auth/register", response_model=UserOut, status_code=201)
def register(body: Credentials, request: Request, db: Session = Depends(get_db)):
    email = str(body.email).lower()
    user = models.User(email=email, password_hash=hasher.hash(body.password), is_admin=email in settings.admins)
    db.add(user)
    try:
        db.flush()
        audit(db, request, user.id, "auth.register")
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(409, "Account already exists")
    db.refresh(user)
    return user


@app.post("/api/v1/auth/login", response_model=TokenOut)
def login(body: Credentials, request: Request, db: Session = Depends(get_db)):
    user = db.scalar(select(models.User).where(models.User.email == str(body.email).lower()))
    try:
        valid = user is not None and hasher.verify(user.password_hash, body.password)
    except VerifyMismatchError:
        valid = False
    if not valid:
        raise HTTPException(401, "Invalid email or password")
    raw = secrets.token_urlsafe(48)
    expires = datetime.now(timezone.utc) + timedelta(seconds=settings.session_ttl_seconds)
    db.add(models.SessionToken(user_id=user.id, token_hash=token_hash(raw), expires_at=expires))
    audit(db, request, user.id, "auth.login")
    db.commit()
    return TokenOut(access_token=raw, expires_at=expires, user=user)


@app.post("/api/v1/auth/logout", status_code=204)
def logout(request: Request, user: models.User = Depends(current_user), authorization: str = Header(...), db: Session = Depends(get_db)):
    record = db.scalar(select(models.SessionToken).where(models.SessionToken.token_hash == token_hash(authorization[7:])))
    record.revoked_at = datetime.now(timezone.utc)
    audit(db, request, user.id, "auth.logout")
    db.commit()
    return Response(status_code=204)


@app.get("/api/v1/auth/me", response_model=UserOut)
def me(user: models.User = Depends(current_user)):
    return user


@app.get("/api/v1/preferences")
def list_preferences(user: models.User = Depends(current_user), db: Session = Depends(get_db)):
    return {p.key: p.value for p in db.scalars(select(models.Preference).where(models.Preference.user_id == user.id))}


@app.put("/api/v1/preferences/{key}")
def set_preference(key: str, body: PreferenceIn, request: Request, user: models.User = Depends(current_user), db: Session = Depends(get_db)):
    if not 1 <= len(key) <= 80 or key.lower() in {"password", "token", "credential", "secret"}:
        raise HTTPException(422, "Invalid preference key")
    row = db.scalar(select(models.Preference).where(models.Preference.user_id == user.id, models.Preference.key == key))
    if row:
        row.value = body.value
    else:
        db.add(models.Preference(user_id=user.id, key=key, value=body.value))
    audit(db, request, user.id, "preferences.update", key)
    db.commit()
    return {"key": key, "value": body.value}


@app.get("/api/v1/notification-preferences")
def get_notifications(user: models.User = Depends(current_user), db: Session = Depends(get_db)):
    row = db.scalar(select(models.Preference).where(models.Preference.user_id == user.id, models.Preference.key == "notifications"))
    return row.value if row else {"enabled": True}


@app.put("/api/v1/notification-preferences")
def set_notifications(body: PreferenceIn, request: Request, user: models.User = Depends(current_user), db: Session = Depends(get_db)):
    if set(body.value) - {"enabled", "email", "push", "marketing"} or any(not isinstance(v, bool) for v in body.value.values()):
        raise HTTPException(422, "Only boolean notification settings are supported")
    set_preference("notifications", body, request, user, db)
    return body.value


@app.put("/api/v1/devices")
def register_device(body: DeviceIn, request: Request, user: models.User = Depends(current_user), db: Session = Depends(get_db)):
    row = db.scalar(select(models.Device).where(models.Device.user_id == user.id, models.Device.device_id == body.device_id))
    if row:
        row.platform, row.app_version = body.platform, body.app_version
    else:
        row = models.Device(user_id=user.id, **body.model_dump())
        db.add(row)
    audit(db, request, user.id, "device.register")
    db.commit()
    return {"device_id": row.device_id, "platform": row.platform, "app_version": row.app_version}


# Deliberately fixed, non-secret, allowlisted configuration; no arbitrary env exposure.
PUBLIC_CONFIG = {"minimum_supported_version": "1.0.0", "maintenance": False}


@app.get("/api/v1/app-config")
def app_config():
    return PUBLIC_CONFIG.copy()


@app.get("/api/v1/service-status")
def service_status(request: Request, db: Session = Depends(get_db)):
    try:
        db.execute(text("SELECT 1"))
    except Exception:
        log.warning("Service readiness failed correlation_id=%s database=unavailable", request.state.correlation_id)
        return JSONResponse(status_code=503, content={"status": "degraded", "service": "application-management", "database": "unavailable", "correlation_id": request.state.correlation_id})
    return {"status": "operational", "service": "application-management", "database": "connected"}


@app.get("/api/v1/app-version")
def app_version():
    return {"version": app.version, "api_version": "v1"}


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/ready")
def readiness(db: Session = Depends(get_db)):
    db.execute(text("SELECT 1"))
    return {"status": "ready", "database": "ok"}


@app.get("/api/v1/admin/audit-events", response_model=list[AuditOut])
def audit_events(limit: int = Query(50, ge=1, le=100), offset: int = Query(0, ge=0), _admin: models.User = Depends(admin_user), db: Session = Depends(get_db)):
    return list(db.scalars(select(models.AuditEvent).order_by(models.AuditEvent.created_at.desc(), models.AuditEvent.id.desc()).offset(offset).limit(limit)))
