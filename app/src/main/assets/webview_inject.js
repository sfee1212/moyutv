(function() {
    'use strict';

    // ── 获取 URL 参数 ────────────────────────────────────────────────────────
    function getQueryString(name) {
        var reg = new RegExp('(^|&)' + name + '=([^&]*)(&|$)', 'i');
        var r = window.location.search.substr(1).match(reg);
        if (r != null) {
            try {
                return decodeURIComponent(r[2]);
            } catch(e) {
                return unescape(r[2]);
            }
        }
        return null;
    }
    // 优先读取 Native 注入的硬编码频道 ID，兜底使用 URL 参数
    var targetChannelId = "__CHANNEL_ID__";
    if (targetChannelId === "__" + "CHANNEL_ID__" || !targetChannelId) {
        targetChannelId = getQueryString('channelId');
    }
    var hasForceSwitched = false;

    // ── 劫持 Vue 屏蔽与强制切台 ──────────────────────────────────────────────
    function hackVueInstance(instance) {
        if (!instance) return;
        
        // 1. 强行让版权屏蔽校验永远返回 false
        if (instance.isShieldDate) {
            instance.isShieldDate = function() { return false; };
        }
        if (instance.$options && instance.$options.methods && instance.$options.methods.isShieldDate) {
            instance.$options.methods.isShieldDate = function() { return false; };
        }
        
        // 2. 强行屏蔽弹窗
        if ('isShowMc' in instance) {
            instance.isShowMc = false;
            try {
                Object.defineProperty(instance, 'isShowMc', {
                    get: function() { return false; },
                    set: function() {},
                    configurable: true
                });
            } catch(e) {}
        }

        // 3. 纠偏劫持：防止 Vue 自己的 mounted 周期在加载完列表后，走 else 分支强制切回默认的江苏卫视 (534)
        if (instance.hanldeClickJstv && !instance.hanldeClickJstv.__hacked__) {
            var origClick = instance.hanldeClickJstv;
            instance.hanldeClickJstv = function(e, t, n) {
                // 如果外部/页面试图切换到江苏卫视 (534)，但我们请求的明明是别的频道，进行重定向纠偏
                if (targetChannelId && targetChannelId != "534" && e && e.ChannelID == 534) {
                    var idx = -1;
                    for (var i = 0; i < instance.jstvList.length; i++) {
                        if (instance.jstvList[i].ChannelID == targetChannelId) {
                            idx = i;
                            break;
                        }
                    }
                    if (idx !== -1) {
                        hasForceSwitched = true;
                        return origClick.call(instance, instance.jstvList[idx], idx, 1);
                    }
                }
                return origClick.apply(this, arguments);
            };
            instance.hanldeClickJstv.__hacked__ = true;
        }
        
        // 4. 手动强行切台 (仅执行一次)
        if (targetChannelId && !hasForceSwitched && instance.jstvList && instance.jstvList.length > 0 && instance.hanldeClickJstv) {
            var idx = -1;
            for (var i = 0; i < instance.jstvList.length; i++) {
                if (instance.jstvList[i].ChannelID == targetChannelId) {
                    idx = i;
                    break;
                }
            }
            if (idx !== -1) {
                hasForceSwitched = true;
                instance.hanldeClickJstv(instance.jstvList[idx], idx, 1);
            }
        }
        
        // 递归处理子组件
        if (instance.$children && instance.$children.length > 0) {
            for (var i = 0; i < instance.$children.length; i++) {
                hackVueInstance(instance.$children[i]);
            }
        }
    }

    var vueHackTimer = setInterval(function() {
        var app = document.getElementById('app');
        if (app && app.__vue__) {
            hackVueInstance(app.__vue__);
        }
    }, 200);

    // ── JSTV 官网全新改版 DOM 暴力纠偏 (无视 Vue) ──────────────────────────
    var domHackAttempts = 0;
    var domHackTimer = setInterval(function() {
        if (hasForceSwitched || !targetChannelId || targetChannelId === "534") {
            clearInterval(domHackTimer);
            return;
        }
        domHackAttempts++;
        if (domHackAttempts > 40) {
            clearInterval(domHackTimer);
            return;
        }
        
        var channelMap = {
            "534": "江苏卫视",
            "535": "江苏城市",
            "536": "公共新闻", // User's screenshot shows 536 is 江苏新闻
            "537": "江苏体育",
            "542": "江苏影视",
            "543": "优漫卡通",
            "544": "国际频道",
            "545": "江苏教育",
            "556": "江苏综艺",
            "676": "4K"
        };
        // 如果 targetChannelId 在映射表里，用映射表；否则直接当做中文频道名搜索！
        var targetName = channelMap[targetChannelId] || targetChannelId;
        if (!targetName || targetName === "534") return;
        
        var elements = document.querySelectorAll('div, span, li, a, p');
        var clicked = false;
        for (var i = 0; i < elements.length; i++) {
            var el = elements[i];
            var text = el.textContent.trim();
            if (text === targetName || (el.children.length === 0 && text.indexOf(targetName) !== -1)) {
                el.click();
                if (el.parentNode) el.parentNode.click();
                clicked = true;
            }
        }
        if (clicked) {
            hasForceSwitched = true;
            clearInterval(domHackTimer);
        } else {
            // 如果没找到目标频道，有可能是像芒果TV那样，频道被隐藏在其他的分类 Tab 中。
            // 我们循环点击分类 Tab 强制网页展开隐藏的列表。
            if (domHackAttempts % 2 === 0) {
                var mgtvTabs = ["热门", "卫视台", "地方台", "特色频道"];
                var tabToClick = mgtvTabs[(domHackAttempts / 2) % mgtvTabs.length];
                for (var j = 0; j < elements.length; j++) {
                    if (elements[j].textContent.trim() === tabToClick) {
                        elements[j].click();
                        if (elements[j].parentNode) elements[j].parentNode.click();
                    }
                }
            }
        }
    }, 500);

    // ── iframe 重定向 ────────────────────────────────────────────────────────
    function checkIframeRedirect() {
        try {
            var cur = window.location.href;
            var iframes = document.querySelectorAll('iframe');
            var maxArea = 0, best = null, sa = window.innerWidth * window.innerHeight;
            for (var i = 0; i < iframes.length; i++) {
                var r = iframes[i].getBoundingClientRect();
                var a = r.width * r.height;
                if (r.width > 200 && r.height > 200 && a > maxArea && a > sa * 0.1) { maxArea = a; best = iframes[i]; }
            }
            if (best && best.src && best.src.indexOf('http') === 0) {
                var s = best.src;
                if (cur !== s && cur.indexOf(s) === -1 && s.indexOf(cur) === -1 && window.WebViewBridge && !window.hasRedirectedIframe) {
                    window.hasRedirectedIframe = true;
                    window.WebViewBridge.loadIframeUrl(s, cur);
                    return true;
                }
            }
        } catch(e) {}
        return false;
    }

    if (checkIframeRedirect()) return;

    var startTime = Date.now();
    var done = false;
    var fsVideo = null;

    // ── 全屏方案：把 video 移到 document.body 直接子节点的新容器 ────────────
    // 注意：这里把 video 移出原有的播放器容器，直接挂在 body 上的全屏 div 里。
    // 为防止播放器 JS 找不到 video，我们在原位置留下一个"占位 video 克隆"
    // 并接管 src / srcObject，保证原 JS 继续操作占位元素时不崩溃。
    function applyFullscreen(video) {
        fsVideo = video;

        // 1. 注入全局样式
        var st = document.createElement('style');
        st.textContent =
            'html,body{overflow:hidden!important;margin:0!important;padding:0!important;background:#000!important;width:100%!important;height:100%!important;}' +
            '#__fs_wrap__{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;overflow:hidden!important;}' +
            '#__fs_wrap__ video{position:absolute!important;top:0!important;left:0!important;width:100%!important;height:100%!important;object-fit:contain!important;pointer-events:none!important;}';
        (document.head || document.documentElement).appendChild(st);

        // 2. 创建全屏容器并直接挂到 body（body 的直接子节点不受 transform 影响）
        var wrap = document.createElement('div');
        wrap.id = '__fs_wrap__';
        document.body.appendChild(wrap);

        // 3. 在原位置插入占位元素（空 div），防止原播放器 JS 报错找不到容器
        var placeholder = document.createElement('div');
        placeholder.style.cssText = 'display:none!important;width:0!important;height:0!important;';
        if (video.parentNode) {
            video.parentNode.insertBefore(placeholder, video);
        }

        // 4. 把 video 移入全屏容器
        wrap.appendChild(video);

        // 5. 重置 video 样式，确保撑满
        video.style.cssText = '';

        // 6. 解除静音（多种方式）
        function unmute(v) {
            // 移除 HTML muted 属性（JS .muted 有时被 attribute 锁定）
            v.removeAttribute('muted');
            v.muted  = false;
            v.volume = 1;
            // 恢复音频上下文
            try {
                var AC = window.AudioContext || window.webkitAudioContext;
                if (AC) {
                    var ac = window.__moyu_ac__ || new AC();
                    window.__moyu_ac__ = ac;
                    if (ac.state === 'suspended') ac.resume();
                }
            } catch(e) {}
        }

        unmute(video);
        if (video.paused) {
            video.play().catch(function(){});
        }
        // DOM 移动后 100ms 再次解除静音（部分播放器会重置 muted）
        setTimeout(function() { unmute(video); }, 100);
        setTimeout(function() { unmute(video); }, 500);
        setTimeout(function() { unmute(video); }, 1500);

        // 6. 隐藏 body 其他所有子节点（只保留 wrap）
        var kids = Array.prototype.slice.call(document.body.children);
        for (var i = 0; i < kids.length; i++) {
            if (kids[i] !== wrap) {
                kids[i].style.setProperty('display', 'none', 'important');
            }
        }

        // 通知原生
        if (window.WebViewBridge) window.WebViewBridge.onVideoPlaying();
    }

    // ── 视频比例 ─────────────────────────────────────────────────────────────
    window.setscale = function(t) {
        if (!fsVideo) return;
        var m = {0:'contain',1:'contain',2:'contain',3:'fill',4:'none',5:'cover'};
        fsVideo.style.setProperty('object-fit', m[t]||'contain', 'important');
    };

    // ── 轮询检测 video ────────────────────────────────────────────────────────
    function findVideo(doc) {
        var v = doc.querySelector('video');
        if (v) return v;
        var iframes = doc.querySelectorAll('iframe');
        for (var i = 0; i < iframes.length; i++) {
            try {
                var idoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                if (idoc) {
                    v = findVideo(idoc);
                    if (v) return v;
                }
            } catch(e) {}
        }
        return null;
    }

    var tmr = setInterval(function() {
        if (done) return;
        if (checkIframeRedirect()) { clearInterval(tmr); return; }
        if (Date.now() - startTime > 20000) { clearInterval(tmr); return; }

        var v = findVideo(document);
        if (!v) return;
        if (v.paused) v.play().catch(function(){});

        if (v.readyState > 0) {
            clearInterval(tmr);
            done = true;
            applyFullscreen(v);
            v.addEventListener('playing', function() { if (window.WebViewBridge) window.WebViewBridge.onVideoPlaying(); });
            v.addEventListener('error',   function() { if (window.WebViewBridge) window.WebViewBridge.onVideoError(); });
        }
    }, 100);

    // ── 视口 meta ────────────────────────────────────────────────────────────
    if (!document.querySelector('meta[name="viewport"]')) {
        var m = document.createElement('meta');
        m.name = 'viewport'; m.content = 'width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no';
        document.head.appendChild(m);
    }

    // ── ku9 兼容 ──────────────────────────────────────────────────────────────
    window.ku9 = {
        scaleType:__SCALE_TYPE__, getscale:function(){return this.scaleType;},
        setvideo:function(){}, setaudio:function(){}, setposition:function(){}, setduration:function(){}
    };
})();