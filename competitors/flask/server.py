import re

from flask import Flask, Response


app = Flask(__name__)


def user_response(user_id: int):
    user = {"id": user_id, "name": "benchmark-user", "active": True}
    body = app.json.dumps(user, sort_keys=False, separators=(",", ":"))
    return Response(body, mimetype="application/json")


@app.get("/health")
def health():
    return Response("ok", mimetype="text/plain")


@app.get("/json")
def json_user():
    return user_response(42)


@app.get("/users/<value>")
def user_by_id(value: str):
    if not re.fullmatch(r"[+-]?[0-9]+", value):
        return Response(status=400)
    try:
        user_id = int(value)
    except ValueError:
        return Response(status=400)
    if not -(2**31) <= user_id < 2**31:
        return Response(status=400)
    return user_response(user_id)
