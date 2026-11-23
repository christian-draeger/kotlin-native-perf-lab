import re

from fastapi import FastAPI, HTTPException
from fastapi.responses import PlainTextResponse
from pydantic import BaseModel


app = FastAPI()


class User(BaseModel):
    id: int
    name: str = "benchmark-user"
    active: bool = True


@app.get("/health", response_class=PlainTextResponse)
async def health():
    return "ok"


@app.get("/json")
async def json_user() -> User:
    return User(id=42)


@app.get("/users/{value}")
async def user_by_id(value: str) -> User:
    if not re.fullmatch(r"[+-]?[0-9]+", value):
        raise HTTPException(status_code=400)
    try:
        user_id = int(value)
    except ValueError as error:
        raise HTTPException(status_code=400) from error
    if not -(2**31) <= user_id < 2**31:
        raise HTTPException(status_code=400)
    return User(id=user_id)
