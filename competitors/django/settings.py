import os
from secrets import token_hex


SECRET_KEY = token_hex(32)
ALLOWED_HOSTS = ["localhost", "127.0.0.1", os.environ.get("BIND_HOST", "127.0.0.1")]
ROOT_URLCONF = "server"
