# -*- coding: utf-8 -*-
"""CatVod 爬虫宿主服务实现: 网络 (net) / TTL 缓存 / 本地存储 (local) / proxy URL。

全部为同步实现 (requests + 文件), 接口形状对齐 FongMi CatVodSpider 的 self.net / self.local。
数据目录由宿主 App 在启动时通过 setup() 注入。
"""

import base64
import hashlib
import json
import os
import tempfile
import threading
import time
from contextlib import contextmanager
from urllib.parse import quote, urlsplit

import requests

DEFAULT_UA = (
    "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

PROXY_BASE = "http://127.0.0.1:8080/pyproxy"

_data_dir = None
_dir_lock = threading.Lock()


def set_data_dir(path):
    global _data_dir
    try:
        if path:
            os.makedirs(path, exist_ok=True)
            _data_dir = path
    except Exception:
        _data_dir = None


def _root(*parts):
    base = _data_dir or tempfile.gettempdir()
    p = os.path.join(base, *parts)
    os.makedirs(p, exist_ok=True)
    return p


def _sid(name):
    return hashlib.sha1(str(name).encode("utf-8")).hexdigest()[:16]


def proxy_url(script_name, param):
    """getProxyUrl 实现: 生成回环地址, 由 App 的 LocalServer 转调本脚本 localProxy()。

    路径带 .m3u8 假后缀 (与 /phpout/ 路由同思路): 播放器按地址后缀判定媒体类型,
    localProxy 返回的是 m3u8 播放列表, 无后缀会被误判成普通渐进流导致解析失败。
    """
    text = param if isinstance(param, str) else json.dumps(param, ensure_ascii=False)
    return "%s/%s.m3u8?p=%s" % (PROXY_BASE, quote(str(script_name)), quote(text))


def _host_of(url):
    try:
        return urlsplit(str(url)).hostname or ""
    except Exception:
        return ""


def _as_text(content):
    if isinstance(content, (bytes, bytearray)):
        return bytes(content).decode("utf-8", "replace")
    return content


def _safe_json(value):
    """尽量把结果转成可持久化 JSON; 不可序列化时退化为字符串。"""
    try:
        json.dumps(value)
        return value
    except Exception:
        return str(value)


class _SessionFlow:
    """net.session() 的会话对象: 独立 Cookie 流程。"""

    def __init__(self, session):
        self._s = session

    def req(self, url, options=None):
        return _do_req(self._s, url, options, None)

    def setCookie(self, url, cookie):
        try:
            host = _host_of(url)
            for item in str(cookie or "").split(";"):
                name, sep, value = item.strip().partition("=")
                if sep and name:
                    self._s.cookies.set(name, value, domain=host, path="/")
            return True
        except Exception:
            return False

    def getCookie(self, url):
        try:
            host = _host_of(url)
            parts = []
            for c in self._s.cookies:
                if not c.domain or host.endswith(c.domain.lstrip(".")):
                    parts.append("%s=%s" % (c.name, c.value))
            return "; ".join(parts)
        except Exception:
            return ""


def _do_req(session, url, options, mem_cookie):
    options = options or {}
    try:
        method = str(options.get("method") or "GET").upper()
        headers = {str(k): str(v) for k, v in (options.get("headers") or {}).items()}
        timeout = float(options.get("timeout") or 15000) / 1000.0
        if mem_cookie:
            ck = mem_cookie.get(_host_of(url))
            if ck and not any(k.lower() == "cookie" for k in headers):
                headers["Cookie"] = ck
        kwargs = {"timeout": timeout}
        if headers:
            kwargs["headers"] = headers
        data = options.get("data")
        body = options.get("json")
        if body is not None:
            kwargs["json"] = body
        elif data is not None:
            if options.get("encode") == "base64":
                kwargs["data"] = base64.b64decode(str(data))
            else:
                kwargs["data"] = data
        allow_redirects = options.get("redirect", True)
        kwargs["allow_redirects"] = bool(allow_redirects)
        r = session.request(method, str(url), **kwargs)
        buf = int(options.get("buffer") or 0)
        content = r.content if buf >= 3 else r.text
        return {"code": r.status_code, "content": content,
                "headers": dict(r.headers), "error": ""}
    except Exception as e:
        return {"code": -1, "content": "", "headers": {},
                "error": "%s: %s" % (type(e).__name__, e)}


class Net:
    """self.net —— 请求 / 缓存 / Cookie / 下载。按脚本隔离。"""

    def __init__(self, script_name):
        self.script = str(script_name or "script")
        self.sid = _sid(self.script)
        self._session = None
        self._mem_cookie = {}

    # ---------- HTTP ----------

    def req(self, url, options=None):
        return _do_req(self._session or requests, url, options, self._mem_cookie)

    def json(self, url, options=None):
        value = self.req(url, options)
        if value.get("error"):
            raise RuntimeError(value["error"])
        if not 200 <= int(value.get("code") or 0) < 300:
            raise RuntimeError("HTTP %s" % value.get("code"))
        return json.loads(_as_text(value.get("content")))

    def batch(self, items):
        out = []
        for it in items or []:
            it = it or {}
            out.append(self.req(it.get("url"), it.get("options")))
        return out

    @contextmanager
    def session(self):
        s = requests.Session()
        try:
            yield _SessionFlow(s)
        finally:
            try:
                s.close()
            except Exception:
                pass

    def ws(self, url, options=None):
        raise NotImplementedError("此宿主暂不支持 WebSocket (net.ws)")

    def connect(self, url, options=None, callback=None):
        raise NotImplementedError("此宿主暂不支持 WebSocket 长连接 (net.connect)")

    def download(self, url, path, options=None):
        value = self.req(url, dict(options or {}, **{"buffer": 3}))
        if value.get("error"):
            raise RuntimeError(value["error"])
        if not 200 <= int(value.get("code") or 0) < 300:
            raise RuntimeError("HTTP %s" % value.get("code"))
        with open(str(path), "wb") as f:
            f.write(value["content"] if isinstance(value["content"], (bytes, bytearray)) else str(value["content"]).encode("utf-8"))

    # ---------- TTL 缓存 ----------

    def _cache_path(self, key):
        return os.path.join(_root("pycache", self.sid), _sid(key) + ".json")

    def cached(self, key, options=None, provider=None):
        options = options or {}
        ttl = int(options.get("ttl") or 60000)
        path = self._cache_path(key)
        now = int(time.time() * 1000)
        entry = None
        with _dir_lock:
            try:
                with open(path, "r", encoding="utf-8") as f:
                    entry = json.load(f)
            except Exception:
                entry = None
        if entry is not None and now - int(entry.get("t") or 0) < ttl:
            return entry.get("v")
        if provider is None:
            return None if entry is None else entry.get("v")
        value = _safe_json(provider() if callable(provider) else provider)
        with _dir_lock:
            try:
                with open(path, "w", encoding="utf-8") as f:
                    json.dump({"t": now, "v": value}, f, ensure_ascii=False)
            except Exception:
                pass
        return value

    def cachedText(self, key, options=None, provider=None):
        value = self.cached(key, options, provider)
        return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)

    def peek(self, key):
        try:
            with open(self._cache_path(key), "r", encoding="utf-8") as f:
                return json.load(f).get("v")
        except Exception:
            return None

    def refresh(self, key, options, provider):
        t = threading.Thread(target=lambda: self.cached(key, options, provider), daemon=True)
        t.start()

    def clearCache(self, key=None):
        if key is None:
            d = os.path.join(_root("pycache", self.sid))
            for name in os.listdir(d):
                try:
                    os.remove(os.path.join(d, name))
                except Exception:
                    pass
        else:
            try:
                os.remove(self._cache_path(key))
            except Exception:
                pass

    # ---------- 杂项 ----------

    def sleep(self, ms):
        time.sleep(float(ms or 0) / 1000.0)

    def getUserAgent(self):
        return DEFAULT_UA

    def setCookie(self, url, cookie):
        try:
            host = _host_of(url)
            # 过期写法 (Max-Age=0 / 过期日期) 清除
            expired = "max-age=0" in str(cookie or "").lower()
            if expired:
                self._mem_cookie.pop(host, None)
                return True
            self._mem_cookie[host] = str(cookie or "")
            return True
        except Exception:
            return False

    def getCookie(self, url):
        return self._mem_cookie.get(_host_of(url), "")


class Local:
    """self.local —— 简单 JSON 存储 (按脚本隔离)。"""

    def __init__(self, script_name):
        self.sid = _sid(script_name)

    def _path(self, key):
        return os.path.join(_root("pylocal", self.sid), _sid(key) + ".json")

    def get(self, key):
        try:
            with open(self._path(key), "r", encoding="utf-8") as f:
                return json.load(f).get("v")
        except Exception:
            return None

    def set(self, key, value):
        try:
            with open(self._path(key), "w", encoding="utf-8") as f:
                json.dump({"v": _safe_json(value)}, f, ensure_ascii=False)
            return True
        except Exception:
            return False

    def delete(self, key):
        try:
            os.remove(self._path(key))
            return True
        except Exception:
            return False
