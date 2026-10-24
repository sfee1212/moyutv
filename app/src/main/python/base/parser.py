# -*- coding: utf-8 -*-
"""旧版 PythonCat 风格解析基类 (from base.parser import Parser)。

常见社区脚本以 requests 为主、仅需一个可继承的基类; 这里补齐 fetch/post 等便捷方法。
"""

import requests

DEFAULT_UA = (
    "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)


class Parser:
    def __init__(self, *args, **kwargs):
        self.headers = {"User-Agent": DEFAULT_UA}
        self.session = requests.Session()
        self.flag = None
        self.homeHtml = None

    def fetch(self, url, headers=None, cookies=None, timeout=10, **kwargs):
        h = dict(self.headers)
        if headers:
            h.update(headers)
        return self.session.get(url, headers=h, cookies=cookies, timeout=timeout, **kwargs)

    def post(self, url, data=None, json=None, headers=None, cookies=None, timeout=10, **kwargs):
        h = dict(self.headers)
        if headers:
            h.update(headers)
        return self.session.post(url, data=data, json=json, headers=h, cookies=cookies, timeout=timeout, **kwargs)

    def getContent(self, url, headers=None, cookies=None, timeout=10):
        return self.fetch(url, headers=headers, cookies=cookies, timeout=timeout).text

    def getText(self, url, headers=None, cookies=None, timeout=10):
        return self.getContent(url, headers=headers, cookies=cookies, timeout=timeout)

    def getHtml(self, url, headers=None, cookies=None, timeout=10):
        return self.getContent(url, headers=headers, cookies=cookies, timeout=timeout)

    def cleanText(self, text):
        return str(text or "").strip()

    def destroy(self):
        try:
            self.session.close()
        except Exception:
            pass
