# -*- coding: utf-8 -*-
"""
通用 Python 脚本执行引导 (由 PyScriptManager 调用)

两种模式:
1. CGI 模式 (无 api 参数, 向后兼容):
   - 脚本通过 os.environ["QUERY_STRING"] 或 sys.argv 获取参数 (与 PHP $_GET / CGI 习惯一致)
   - 脚本把结果 print 到 stdout:
       * print("http://...")        → 302 跳转
       * print(m3u8 文本, "#EXTM3U") → 返回 m3u8 播放列表

2. CatVod 爬虫协议模式 (query 含 api=<方法名>):
   - 脚本定义爬虫类 (推荐 class Spider; 兼容继承 base.parser.Parser 的类),
     实现 FongMi/CatVodSpider 标准接口 (homeContent/playerContent/detailContent/...)
   - 宿主注入 self.net / self.local / siteKey, 提供 base.spider / base.parser 兼容层
   - call() 按 (脚本, ext) 缓存实例, init(extend) 后调用指定方法, 结果序列化为 JSON
"""

import hashlib
import importlib.util
import io
import json
import os
import sys
import runpy
import threading
import traceback

_lock = threading.RLock()
_modules = {}    # script_path -> imported module (类发现用)
_mtimes = {}     # script_path -> 导入时的文件 mtime (外部修改后自动重载)
_instances = {}  # (script_path, ext) -> 爬虫实例 (LRU 上限)
_INST_MAX = 8

_PROTOCOL_METHODS = ("homeContent", "homeVideoContent", "categoryContent",
                     "searchContent", "detailContent", "playerContent", "liveContent")


def setup(files_dir):
    """宿主注入数据目录 (TTL 缓存 / local 存储落盘位置)。"""
    try:
        import base.net as _bnet
        _bnet.set_data_dir(files_dir)
        return True
    except BaseException:
        return False


def run(script_path, query):
    """CGI 模式执行脚本, 返回 dict: {ok, output, error}"""
    params = {}
    if query:
        for pair in str(query).split("&"):
            if "=" in pair:
                k, v = pair.split("=", 1)
                try:
                    from urllib.parse import unquote_plus
                    k = unquote_plus(k)
                    v = unquote_plus(v)
                except Exception:
                    pass
                params[k] = v

    os.environ["QUERY_STRING"] = str(query or "")
    os.environ["REQUEST_METHOD"] = "GET"
    os.environ["SCRIPT_NAME"] = os.path.basename(script_path)
    sys.argv = [script_path] + [str(query or "")]

    buf = io.StringIO()
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    sys.stdout = buf
    sys.stderr = buf
    try:
        runpy.run_path(script_path, run_name="__main__")
    except SystemExit:
        pass
    except BaseException:
        err = traceback.format_exc()
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        return {"ok": False, "output": buf.getvalue(), "error": err}
    finally:
        sys.stdout = old_stdout
        sys.stderr = old_stderr
    return {"ok": True, "output": buf.getvalue(), "error": ""}


# ---------- CatVod 爬虫协议 ----------

def _mtime_of(path):
    try:
        return os.path.getmtime(path)
    except Exception:
        return 0.0


def _load_module(script_path):
    """按路径导入脚本模块 (缓存), 供类发现与实例化; 文件被外部修改后自动重载。"""
    with _lock:
        mtime = _mtime_of(script_path)
        mod = _modules.get(script_path)
        if mod is not None and _mtimes.get(script_path) == mtime:
            return mod
        if mod is not None:
            # 脚本文件已更新 → 丢弃旧实例 (含 net/local/配置), 下次调用重新 init
            for k in [k for k in _instances if k[0] == script_path]:
                _instances.pop(k, None)
        name = "uvscript_" + hashlib.sha1(script_path.encode("utf-8")).hexdigest()[:16]
        spec = importlib.util.spec_from_file_location(name, script_path)
        if spec is None or spec.loader is None:
            raise ImportError("无法加载脚本模块: %s" % script_path)
        mod = importlib.util.module_from_spec(spec)
        sys.modules[name] = mod
        # 导入期的 print 不外泄
        old_o, old_e = sys.stdout, sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = sys.stdout
        try:
            spec.loader.exec_module(mod)
        finally:
            sys.stdout = old_o
            sys.stderr = old_e
        _modules[script_path] = mod
        _mtimes[script_path] = mtime
        return mod


def _find_spider_class(mod):
    """在模块内寻找协议爬虫类: 优先继承 base.spider.Spider 的, 再按类名 Spider/Parser, 否则首个命中协议方法的类。"""
    import base.spider as _bs
    candidates = []
    for value in vars(mod).values():
        if not isinstance(value, type):
            continue
        if getattr(value, "__module__", None) != mod.__name__:
            continue
        if not any(callable(getattr(value, n, None)) for n in _PROTOCOL_METHODS):
            continue
        candidates.append(value)
    if not candidates:
        return None
    for cls in candidates:
        if issubclass(cls, _bs.Spider):
            return cls
    for want in ("Spider", "Parser", "main"):
        for cls in candidates:
            if cls.__name__ == want:
                return cls
    return candidates[0]


def has_spider(script_path):
    """脚本是否定义了 CatVod 爬虫类 (供 CGI 模式空输出时给出更准确的提示)。"""
    try:
        return _find_spider_class(_load_module(script_path)) is not None
    except BaseException:
        return False


def _new_instance(script_path, mod, ext):
    cls = _find_spider_class(mod)
    if cls is None:
        raise TypeError("脚本未定义 CatVod 爬虫类 (需 class Spider 且含 homeContent/playerContent 等协议方法)")
    import base.net as _bnet
    inst = cls()
    site_key = os.path.splitext(os.path.basename(script_path))[0]
    try:
        inst.siteKey = site_key
    except Exception:
        pass
    inst.net = _bnet.Net(site_key)
    inst.local = _bnet.Local(site_key)
    inst._py_script = os.path.basename(script_path)
    init = getattr(inst, "init", None)
    if callable(init):
        init(ext if ext else "")
    return inst


def call(script_path, ext, method, args_json):
    """CatVod 爬虫协议调用, 返回 dict: {ok, result, error}; result 为 JSON 文本。"""
    try:
        args = json.loads(args_json) if args_json else []
        if not isinstance(args, list):
            args = [args]
    except BaseException:
        return {"ok": False, "result": "", "error": "args 不是合法 JSON: %s" % (args_json,)}

    os.environ["SCRIPT_NAME"] = os.path.basename(script_path)
    sys.argv = [script_path, ""]

    old_o, old_e = sys.stdout, sys.stderr
    sys.stdout = io.StringIO()
    sys.stderr = sys.stdout
    try:
        mod = _load_module(script_path)
        key = (script_path, str(ext or ""))
        with _lock:
            inst = _instances.get(key)
            if inst is None:
                inst = _new_instance(script_path, mod, ext)
                _instances[key] = inst
                while len(_instances) > _INST_MAX:
                    _instances.pop(next(iter(_instances)), None)
        fn = getattr(inst, method, None)
        if not callable(fn):
            return {"ok": False, "result": "", "error": "爬虫不支持接口: %s" % method}
        ret = fn(*args)
        return {"ok": True, "result": json.dumps(ret, ensure_ascii=False, default=str), "error": ""}
    except SystemExit:
        return {"ok": False, "result": "", "error": "脚本调用 sys.exit() 终止"}
    except BaseException:
        return {"ok": False, "result": "", "error": traceback.format_exc()}
    finally:
        sys.stdout = old_o
        sys.stderr = old_e
