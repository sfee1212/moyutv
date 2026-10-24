# -*- coding: utf-8 -*-
"""FongMi/CatVodSpider 风格爬虫基类。

宿主在实例化后注入: siteKey / net / local / _py_script, 然后调用 init(extend)。
爬虫实现接口:
    init(extend) / getName() / homeContent(filter) / homeVideoContent()
    categoryContent(tid, pg, filter, extend) / searchContent(key, quick, pg)
    detailContent(ids) / playerContent(flag, id, vipFlags) / action(action)
    liveContent(url) / localProxy(param) / isVideoFormat(url) / manualVideoCheck() / destroy()
"""

import json

from . import net as _hostnet


class Spider:
    siteKey = ""
    net = None
    local = None

    def init(self, extend=""):
        pass

    def getName(self):
        return type(self).__name__

    # ---------- 宿主工具 ----------

    def getProxyUrl(self, param):
        return _hostnet.proxy_url(getattr(self, "_py_script", ""), param)

    @staticmethod
    def link(name, ext):
        return "%s$%s" % (name, json.dumps(ext, ensure_ascii=False, separators=(",", ":")))

    @staticmethod
    def page(d, pg, pageSize=20):
        """TV 惯例分页: 数据量超过页大小 → 本地切片; 否则视为站点已分页直接返回当页。"""
        try:
            page = int(pg)
        except Exception:
            page = 1
        if page < 1:
            page = 1
        items = list(d) if d else []
        limit = int(pageSize) if pageSize else 20
        if not items:
            return {"list": [], "page": page, "pagecount": 1, "limit": limit, "total": 0}
        if len(items) > limit:
            pagecount = len(items) // limit + (1 if len(items) % limit else 0)
            start = (page - 1) * limit
            return {"list": items[start:start + limit], "page": page,
                    "pagecount": pagecount, "limit": limit, "total": len(items)}
        return {"list": items, "page": page, "pagecount": page + 1, "limit": limit, "total": len(items)}

    def getActivity(self):
        return None

    def destroy(self):
        pass
