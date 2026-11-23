import os
import re

from django.core.asgi import get_asgi_application
from django.http import HttpResponse, HttpResponseBadRequest, JsonResponse
from django.urls import path
from django.views.decorators.http import require_GET


def user_response(user_id: int):
    user = {"id": user_id, "name": "benchmark-user", "active": True}
    return JsonResponse(user, json_dumps_params={"separators": (",", ":")})


@require_GET
async def health(request):
    return HttpResponse("ok", content_type="text/plain")


@require_GET
async def json_user(request):
    return user_response(42)


@require_GET
async def user_by_id(request, value: str):
    if not re.fullmatch(r"[+-]?[0-9]+", value):
        return HttpResponseBadRequest()
    try:
        user_id = int(value)
    except ValueError:
        return HttpResponseBadRequest()
    if not -(2**31) <= user_id < 2**31:
        return HttpResponseBadRequest()
    return user_response(user_id)


urlpatterns = [
    path("health", health),
    path("json", json_user),
    path("users/<value>", user_by_id),
]

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "settings")
app = get_asgi_application()
