<?php
/**
 * PHP7.4 (android 静态内置二进制) 网络能力补齐层
 * —— PhpRuntimeBridge 通过 -d auto_prepend_file=<本文件> 注入, 解析脚本无需任何修改。
 *
 * 背景: 内置二进制未编译 openssl 扩展(无 https 流包装器), 且 libcurl 未含 https 协议,
 *      脚本里所有 HTTPS 访问 (file_get_contents/fopen/file()/curl_*) 原生必失败。
 * 方案: 全部转到 LocalServer 的 /phpfetch 回环端点(本机 127.0.0.1:8080), 由应用层
 *       OkHttp 完成真实 TLS、证书校验、重定向与请求头, 结果回传 PHP。
 *
 * 包含五部分:
 *   1. __phpfetch()          —— 回环取流公共实现 (http://127.0.0.1:8080/phpfetch)
 *   2. https:// 流包装器     —— file_get_contents()/fopen()/file()/get_headers() 等可用
 *   3. curl_* 用户态垫片     —— 仅当原生 curl 函数已被二进制补丁改名 (xurl_*) 时启用,
 *                               覆盖常用单句柄/多句柄函数与常用 CURLOPT/CURLINFO 选项
 *   4. openssl_* 用户态垫片  —— 纯 PHP AES-CBC/ECB 加解密 (脚本解密接口数据用)
 *   5. ssl:// 传输垫片       —— stream_socket_client()/fsockopen() 的 ssl/tls 方案
 *                               转 127.0.0.1:8081 裸回环隧道, Java 侧做真实 TLS 并回传
 *                               原生流资源 (WebSocket 心跳等长连接脚本用)
 *
 * 兼容性: 若换用自带 TLS 的 php 二进制 (如 摸鱼TV/bin/php), 原生函数/包装器存在,
 *         本文件全部逻辑自动跳过, 不产生任何覆盖。
 */

// ============================ 1. 回环取流 ============================
if (!function_exists('__phpfetch')) {
    /**
     * 回环裸 HTTP 客户端 (仅 LocalServer 127.0.0.1:8080):
     * http 包装器已被 2b 接管为垫片, __phpfetch 若再用 file_get_contents 回环会
     * 与垫片相互递归; 这里走原生 TCP (fsockopen→xsockopen, IP 直连不经 DNS、
     * 不经任何流包装器), 从根上终止递归。
     * @return array{err:string, lines:array, body:string}
     */
    function __phpfetch_loop($url, $timeout = 15)
    {
        $p = @parse_url($url);
        if ($p === false || !isset($p['host'])) {
            return array('err' => 'bad loopback url', 'lines' => array(), 'body' => '');
        }
        $host = $p['host'];
        $port = isset($p['port']) ? (int) $p['port'] : 80;
        $path = (isset($p['path']) ? $p['path'] : '/') . (isset($p['query']) ? '?' . $p['query'] : '');
        $errno = 0;
        $errstr = '';
        $fp = @fsockopen($host, $port, $errno, $errstr, (float) $timeout);
        if (!$fp) {
            return array('err' => 'phpfetch loopback connect failed: ' . $errstr, 'lines' => array(), 'body' => '');
        }
        stream_set_timeout($fp, (int) ceil($timeout));
        $req = 'GET ' . $path . " HTTP/1.0\r\n"
             . 'Host: ' . $host . ':' . $port . "\r\n"
             . "Connection: close\r\nAccept: */*\r\n\r\n";
        @fwrite($fp, $req);
        $raw = '';
        while (!feof($fp)) {
            $chunk = @fread($fp, 8192);
            if ($chunk === false || $chunk === '') {
                break;
            }
            $raw .= $chunk;
        }
        fclose($fp);
        $split = strpos($raw, "\r\n\r\n");
        if ($split === false) {
            return array('err' => 'malformed loopback response', 'lines' => array(), 'body' => $raw);
        }
        $headBlock = substr($raw, 0, $split);
        $lines = ($headBlock === '') ? array() : explode("\r\n", $headBlock);
        return array('err' => '', 'lines' => $lines, 'body' => substr($raw, $split + 4));
    }

    /**
     * @return array{status:int, headers:string, body:string, error:string, url:string, time:float}
     */
    function __phpfetch($url, $method = 'GET', $headerBlock = '', $content = null, $follow = true, $timeout = 60)
    {
        $t = (int) ceil((float) $timeout);
        if ($t < 1) {
            $t = 1;
        }
        if ($t > 10) { // /phpfetch 端点上限 10s (外层代理读超时 15s)
            $t = 10;
        }
        $query = array(
            'url'     => (string) $url,
            'method'  => (string) $method,
            'headers' => base64_encode((string) $headerBlock),
            'data'    => base64_encode((string) ($content === null ? '' : $content)),
            'follow'  => $follow ? '1' : '0',
            'timeout' => (string) $t,
        );
        $loop = 'http://127.0.0.1:8080/phpfetch?' . http_build_query($query);
        $t0 = microtime(true);
        $lp = __phpfetch_loop($loop, $t + 4);
        $elapsed = microtime(true) - $t0;

        $status   = 0;
        $upResp   = '';
        $upErr    = '';
        $finalUrl = '';
        $body = (string) $lp['body'];
        if ($lp['err'] !== '') {
            $upErr = $lp['err'];
        }
        foreach ($lp['lines'] as $h) {
            if (preg_match('#^HTTP/\S+\s+(\d{3})#', $h, $mm)) {
                $status = (int) $mm[1];
                continue;
            }
            $ci = strpos($h, ':');
            if ($ci === false) {
                continue;
            }
            $name = strtolower(trim(substr($h, 0, $ci)));
            $val  = trim(substr($h, $ci + 1));
            if ($name === 'x-up-status') {
                $status = (int) $val;
            } elseif ($name === 'x-up-resp') {
                $upResp = $val;
            } elseif ($name === 'x-up-err') {
                $upErr = base64_decode($val, true);
                if ($upErr === false) {
                    $upErr = '';
                }
            } elseif ($name === 'x-up-final-url') {
                $finalUrl = $val;
            }
        }
        $raw = base64_decode($upResp, true);
        if ($raw === false) {
            $raw = '';
        }
        if ($body === false) {
            $body = '';
        }
        if ($status === 0) {
            if ($upErr === '') {
                $upErr = ($body === '') ? 'phpfetch loopback unreachable' : 'no status from phpfetch';
            }
            return array('status' => 0, 'headers' => '', 'body' => '', 'error' => $upErr,
                'url' => (string) $url, 'time' => $elapsed);
        }
        return array('status' => $status, 'headers' => $raw, 'body' => (string) $body,
            'error' => $upErr, 'url' => ($finalUrl !== '' ? $finalUrl : (string) $url), 'time' => $elapsed);
    }
}

// ============================ 2. https 流包装器 ============================
if (!in_array('https', stream_get_wrappers(), true)) {
    class __LoopHttpsWrapper
    {
        /** @var resource|null */
        public $context;
        /** @var string */
        private $body = '';
        /** @var int */
        private $pos = 0;

        public function stream_open($path, $mode, $options, &$opened_path)
        {
            // 注: http:// IP 直连同样走 __phpfetch —— 回环已改为裸套接字 (见 1),
            // 无递归; 局域网 IP 目标交给 OkHttp (IP 无需 DNS) 行为等价。
            $http = array();
            if ($this->context) {
                $opts = stream_context_get_options($this->context);
                if (isset($opts['http']) && is_array($opts['http'])) {
                    $http = $opts['http'];
                }
            }
            $method = isset($http['method']) ? strtoupper((string) $http['method']) : 'GET';
            $header = '';
            if (isset($http['header'])) {
                $header = is_array($http['header'])
                    ? implode("\r\n", $http['header'])
                    : (string) $http['header'];
            }
            $content = isset($http['content']) ? $http['content'] : null;
            $timeout = isset($http['timeout']) ? (float) $http['timeout'] : (float) ini_get('default_socket_timeout');
            $follow  = !isset($http['follow_location']) || $http['follow_location'] ? true : false;
            $ignore  = !empty($http['ignore_errors']);
            if ($content !== null && $method === 'GET') {
                $method = 'POST';
            }
            if (!in_array($method, array('GET', 'POST', 'HEAD', 'PUT', 'DELETE'), true)) {
                $method = 'GET';
            }

            $r = __phpfetch($path, $method, $header, $content, $follow, $timeout);
            if ($r['status'] <= 0) {
                trigger_error('https stream wrapper: ' . ($r['error'] !== '' ? $r['error'] : 'fetch failed'), E_USER_WARNING);
                return false;
            }
            if ($r['status'] >= 400 && !$ignore) {
                trigger_error('https stream wrapper: HTTP request failed! HTTP/1.1 ' . $r['status'], E_USER_WARNING);
                return false;
            }
            $this->body = $r['body'];
            $this->pos = 0;
            $opened_path = $path;
            return true;
        }

        public function stream_read($count)
        {
            $chunk = substr($this->body, $this->pos, (int) $count);
            $this->pos += strlen($chunk);
            return $chunk;
        }

        public function stream_write($data)
        {
            return 0;
        }

        public function stream_tell()
        {
            return $this->pos;
        }

        public function stream_eof()
        {
            return $this->pos >= strlen($this->body);
        }

        public function stream_seek($offset, $whence = SEEK_SET)
        {
            $size = strlen($this->body);
            $new = (int) $offset;
            if ($whence === SEEK_CUR) {
                $new = $this->pos + (int) $offset;
            } elseif ($whence === SEEK_END) {
                $new = $size + (int) $offset;
            }
            if ($new < 0 || $new > $size) {
                return false;
            }
            $this->pos = $new;
            return true;
        }

        public function stream_flush()
        {
            return true;
        }

        public function stream_stat()
        {
            return array('size' => strlen($this->body));
        }

        public function url_stat($path, $flags)
        {
            return false;
        }

        public function stream_set_option($option, $arg1, $arg2)
        {
            return false;
        }

        public function stream_close()
        {
            $this->body = '';
            $this->pos = 0;
        }
    }
    @stream_wrapper_register('https', '__LoopHttpsWrapper');
}

// ======================== 2b. http:// 外部域名接管 ============================
// 静态编译二进制的 bionic 找不到 /system/etc/resolv.conf → 原生 DNS (gethostbyname)
// 必失败 (errno=111), file_get_contents('http://域名') 全挂 —— 而 https 走 1 的
// /phpfetch 回环 (Java 侧 DNS) 一切正常。这里把带主机名的 http:// 也交给同一个包装类:
// stream_open 内 IP 直连/localhost 委托回原生 (无 DNS 依赖), 域名转 /phpfetch。
// 每请求重装 (幂等), 不加全局标记以防请求间状态复位导致漏装。
if (class_exists('__LoopHttpsWrapper') && in_array('http', stream_get_wrappers(), true)) {
    @stream_wrapper_unregister('http');   // 拆掉原生 (或上一轮的垫片)
    @stream_wrapper_restore('http');      // 确保原生在位 (stream_open 委托时要用)
    @stream_wrapper_unregister('http');
    @stream_wrapper_register('http', '__LoopHttpsWrapper');
}

// ============================ 3. curl_* 用户态垫片 ============================
// 仅当原生 curl 函数已被 PhpRuntimeBridge 的二进制补丁改名 (curl_* → xurl_*) 时启用;
// 换用自带 TLS 的 php 二进制时原生函数仍在, 本块整体跳过。
if (!function_exists('curl_init')) {

    class __ShimCurlHandle
    {
        public $url = '';
        public $method = 'GET';
        public $headers = array();
        public $body = null;
        public $follow = false;
        public $timeout = 60;
        public $ua = null;
        public $referer = null;
        public $cookie = null;
        public $contentType = null;
        public $returnTransfer = false;
        public $includeHeader = false;
        public $noBody = false;
        public $failOnError = false;
        public $writeFn = null;
        public $headerFn = null;
        public $outStream = null;
        public $ran = false;
        public $closed = false;
        public $status = 0;
        public $respHeaders = '';
        public $respBody = '';
        public $output = '';
        public $errNo = 0;
        public $errStr = '';
        public $effectiveUrl = '';
        public $totalTime = 0.0;
        public $headerSize = 0;
    }

    class __ShimCurlMulti
    {
        public $handles = array();
        public $doneQueue = array();
    }

    /** 取流执行核心, 返回 bool; 输出暂存 $ch->output */
    function __shim_curl_run($ch)
    {
        if ($ch->ran) {
            return $ch->errNo === 0 && $ch->status > 0;
        }
        $ch->ran = true;
        if ($ch->url === '') {
            $ch->errNo = 2;
            $ch->errStr = 'URL using bad/illegal format or more than one URL';
            return false;
        }
        $hdrs = $ch->headers;
        if ($ch->ua !== null && $ch->ua !== '') {
            $hdrs[] = 'User-Agent: ' . $ch->ua;
        }
        if ($ch->referer !== null && $ch->referer !== '') {
            $hdrs[] = 'Referer: ' . $ch->referer;
        }
        if ($ch->cookie !== null && $ch->cookie !== '') {
            $hdrs[] = 'Cookie: ' . $ch->cookie;
        }
        if ($ch->contentType !== null && $ch->contentType !== '') {
            $hdrs[] = 'Content-Type: ' . $ch->contentType;
        }
        $method = $ch->noBody ? 'HEAD' : $ch->method;
        $r = __phpfetch($ch->url, $method, implode("\r\n", $hdrs), $ch->body, $ch->follow, $ch->timeout);
        $ch->status = $r['status'];
        $ch->respHeaders = $r['headers'];
        $ch->respBody = $r['body'];
        $ch->effectiveUrl = $r['url'];
        $ch->totalTime = $r['time'];
        $ch->headerSize = strlen($r['headers']);
        if ($r['status'] <= 0) {
            $ch->errNo = 7;
            $ch->errStr = $r['error'] !== '' ? $r['error'] : "Couldn't connect to server";
            return false;
        }
        if ($ch->failOnError && $r['status'] >= 400) {
            $ch->errNo = 22;
            $ch->errStr = 'The requested URL returned error: ' . $r['status'];
            return false;
        }
        $ch->output = ($ch->includeHeader ? $r['headers'] : '') . $r['body'];
        return true;
    }

    function curl_init($url = false)
    {
        $h = new __ShimCurlHandle();
        if (is_string($url) && $url !== '') {
            $h->url = $url;
        }
        return $h;
    }

    function __shim_curl_setopt($ch, $opt, $value)
    {
        if (!($ch instanceof __ShimCurlHandle) || $ch->closed) {
            trigger_error('curl_setopt(): supplied argument is not a valid CurlHandle', E_USER_WARNING);
            return false;
        }
        switch ($opt) {
            case CURLOPT_URL:
                $ch->url = (string) $value;
                break;
            case CURLOPT_RETURNTRANSFER:
                $ch->returnTransfer = (bool) $value;
                break;
            case CURLOPT_HEADER:
                $ch->includeHeader = (bool) $value;
                break;
            case CURLOPT_NOBODY:
                $ch->noBody = (bool) $value;
                break;
            case CURLOPT_FAILONERROR:
                $ch->failOnError = (bool) $value;
                break;
            case CURLOPT_FOLLOWLOCATION:
                $ch->follow = (bool) $value;
                break;
            case CURLOPT_POST:
                if ($value) {
                    $ch->method = 'POST';
                }
                break;
            case CURLOPT_HTTPGET:
                if ($value) {
                    $ch->method = 'GET';
                }
                break;
            case CURLOPT_UPLOAD:
                if ($value) {
                    $ch->method = 'PUT';
                }
                break;
            case CURLOPT_CUSTOMREQUEST:
                $ch->method = strtoupper((string) $value);
                break;
            case CURLOPT_POSTFIELDS:
                $ch->method = ($ch->method === 'GET') ? 'POST' : $ch->method;
                if (is_array($value)) {
                    foreach ($value as $v) {
                        if (is_object($v)) {
                            trigger_error('curl shim: CURLFile upload is not supported', E_USER_WARNING);
                            return false;
                        }
                    }
                    $ch->body = http_build_query($value, '', '&');
                    $ch->contentType = 'application/x-www-form-urlencoded';
                } else {
                    $ch->body = (string) $value;
                }
                break;
            case CURLOPT_HTTPHEADER:
                $ch->headers = is_array($value) ? array_values($value) : array();
                break;
            case CURLOPT_USERAGENT:
                $ch->ua = (string) $value;
                break;
            case CURLOPT_REFERER:
                $ch->referer = (string) $value;
                break;
            case CURLOPT_COOKIE:
                $ch->cookie = (string) $value;
                break;
            case CURLOPT_TIMEOUT:
                $ch->timeout = (int) $value;
                break;
            case CURLOPT_TIMEOUT_MS:
                $ch->timeout = (int) max(1, (int) ceil($value / 1000.0));
                break;
            case CURLOPT_ENCODING:
                $enc = (string) $value;
                $ch->headers[] = 'Accept-Encoding: ' . ($enc === '' ? 'gzip, deflate' : $enc);
                break;
            case CURLOPT_WRITEFUNCTION:
                $ch->writeFn = $value;
                break;
            case CURLOPT_HEADERFUNCTION:
                $ch->headerFn = $value;
                break;
            case CURLOPT_FILE:
                $ch->outStream = $value;
                break;
            default:
                // TLS 细节(SSL_VERIFYPEER/VERIFYHOST/CAINFO/CERT等)与传输调优选项一律忽略,
                // 证书校验与网络传输由应用层 OkHttp 负责
                break;
        }
        return true;
    }

    function curl_setopt($ch, $opt, $value)
    {
        return __shim_curl_setopt($ch, $opt, $value);
    }

    function curl_setopt_array($ch, array $options)
    {
        $ok = true;
        foreach ($options as $opt => $value) {
            if (!__shim_curl_setopt($ch, $opt, $value)) {
                $ok = false;
            }
        }
        return $ok;
    }

    function curl_exec($ch)
    {
        if (!($ch instanceof __ShimCurlHandle) || $ch->closed) {
            trigger_error('curl_exec(): supplied resource is not a valid Curl handle', E_USER_WARNING);
            return false;
        }
        if (!__shim_curl_run($ch)) {
            return false;
        }
        if ($ch->headerFn !== null && $ch->respHeaders !== '') {
            foreach (explode("\r\n", rtrim($ch->respHeaders, "\r\n")) as $line) {
                if ($line !== '') {
                    @call_user_func($ch->headerFn, $line . "\r\n");
                }
            }
        }
        $out = $ch->output;
        if ($ch->returnTransfer) {
            return $out;
        }
        if ($ch->writeFn !== null) {
            @call_user_func($ch->writeFn, $out);
            return true;
        }
        if ($ch->outStream !== null && is_resource($ch->outStream)) {
            @fwrite($ch->outStream, $out);
            return true;
        }
        echo $out;
        return true;
    }

    function curl_getinfo($ch, $opt = 0)
    {
        if (!($ch instanceof __ShimCurlHandle)) {
            return array();
        }
        $ct = '';
        foreach (explode("\r\n", $ch->respHeaders) as $h) {
            if (stripos($h, 'content-type:') === 0) {
                $ct = trim(substr($h, 13));
                break;
            }
        }
        $len = strlen($ch->respBody);
        $full = array(
            'url' => $ch->effectiveUrl !== '' ? $ch->effectiveUrl : $ch->url,
            'content_type' => $ct,
            'http_code' => $ch->status,
            'header_size' => $ch->headerSize,
            'request_size' => 0,
            'filetime' => -1,
            'ssl_verify_result' => 1,
            'redirect_count' => 0,
            'total_time' => $ch->totalTime,
            'namelookup_time' => 0.0,
            'connect_time' => 0.0,
            'pretransfer_time' => 0.0,
            'size_upload' => 0.0,
            'size_download' => (float) $len,
            'speed_download' => 0.0,
            'speed_upload' => 0.0,
            'download_content_length' => (float) $len,
            'starttransfer_time' => 0.0,
            'redirect_time' => 0.0,
            'primary_ip' => '127.0.0.1',
            'primary_port' => 8080,
            'local_ip' => '127.0.0.1',
            'local_port' => 0,
        );
        if ($opt === null || $opt === 0) {
            return $full;
        }
        $map = array();
        foreach (array(
            'CURLINFO_EFFECTIVE_URL' => 'url',
            'CURLINFO_CONTENT_TYPE' => 'content_type',
            'CURLINFO_RESPONSE_CODE' => 'http_code',
            'CURLINFO_HTTP_CODE' => 'http_code',
            'CURLINFO_HEADER_SIZE' => 'header_size',
            'CURLINFO_REQUEST_SIZE' => 'request_size',
            'CURLINFO_FILETIME' => 'filetime',
            'CURLINFO_SSL_VERIFYRESULT' => 'ssl_verify_result',
            'CURLINFO_REDIRECT_COUNT' => 'redirect_count',
            'CURLINFO_REDIRECT_TIME' => 'redirect_time',
            'CURLINFO_TOTAL_TIME' => 'total_time',
            'CURLINFO_NAMELOOKUP_TIME' => 'namelookup_time',
            'CURLINFO_CONNECT_TIME' => 'connect_time',
            'CURLINFO_PRETRANSFER_TIME' => 'pretransfer_time',
            'CURLINFO_SIZE_UPLOAD' => 'size_upload',
            'CURLINFO_SIZE_DOWNLOAD' => 'size_download',
            'CURLINFO_SPEED_DOWNLOAD' => 'speed_download',
            'CURLINFO_SPEED_UPLOAD' => 'speed_upload',
            'CURLINFO_CONTENT_LENGTH_DOWNLOAD' => 'download_content_length',
            'CURLINFO_STARTTRANSFER_TIME' => 'starttransfer_time',
            'CURLINFO_PRIMARY_IP' => 'primary_ip',
            'CURLINFO_PRIMARY_PORT' => 'primary_port',
            'CURLINFO_LOCAL_IP' => 'local_ip',
            'CURLINFO_LOCAL_PORT' => 'local_port',
        ) as $cname => $key) {
            if (defined($cname)) {
                $map[constant($cname)] = $key;
            }
        }
        return isset($map[$opt]) ? $full[$map[$opt]] : null;
    }

    function curl_error($ch)
    {
        return ($ch instanceof __ShimCurlHandle) ? $ch->errStr : '';
    }

    function curl_errno($ch)
    {
        return ($ch instanceof __ShimCurlHandle) ? $ch->errNo : 0;
    }

    function curl_close($ch)
    {
        if ($ch instanceof __ShimCurlHandle) {
            $ch->closed = true;
        }
    }

    function curl_reset($ch)
    {
        if ($ch instanceof __ShimCurlHandle && !$ch->closed) {
            $ch->url = '';
            $ch->method = 'GET';
            $ch->headers = array();
            $ch->body = null;
            $ch->follow = false;
            $ch->ua = null;
            $ch->referer = null;
            $ch->cookie = null;
            $ch->contentType = null;
            $ch->returnTransfer = false;
            $ch->includeHeader = false;
            $ch->noBody = false;
            $ch->failOnError = false;
            $ch->writeFn = null;
            $ch->headerFn = null;
            $ch->outStream = null;
            $ch->ran = false;
            $ch->status = 0;
            $ch->respHeaders = '';
            $ch->respBody = '';
            $ch->output = '';
            $ch->errNo = 0;
            $ch->errStr = '';
        }
        return true;
    }

    function curl_copy_handle($ch)
    {
        if (!($ch instanceof __ShimCurlHandle)) {
            return false;
        }
        $n = new __ShimCurlHandle();
        foreach (get_object_vars($ch) as $k => $v) {
            $n->$k = $v;
        }
        $n->ran = false;
        $n->closed = false;
        return $n;
    }

    function curl_pause($ch, $bitmask)
    {
        return 0;
    }

    // ----------------------- multi -----------------------

    function curl_multi_init()
    {
        return new __ShimCurlMulti();
    }

    function curl_multi_add_handle($mh, $ch)
    {
        if (!($mh instanceof __ShimCurlMulti) || !($ch instanceof __ShimCurlHandle)) {
            return 1; // CURLM_BAD_HANDLE
        }
        $mh->handles[] = $ch;
        return 0; // CURLM_OK
    }

    function curl_multi_remove_handle($mh, $ch)
    {
        if (!($mh instanceof __ShimCurlMulti)) {
            return 1;
        }
        foreach ($mh->handles as $i => $h) {
            if ($h === $ch) {
                unset($mh->handles[$i]);
                $mh->handles = array_values($mh->handles);
                break;
            }
        }
        return 0;
    }

    function curl_multi_exec($mh, &$running)
    {
        if (!($mh instanceof __ShimCurlMulti)) {
            $running = 0;
            return 1;
        }
        // 一次调用内串行完成全部待执行句柄 (对脚本的轮询循环语义等价)
        foreach ($mh->handles as $ch) {
            if (!$ch->ran) {
                $ok = __shim_curl_run($ch);
                $mh->doneQueue[] = array(
                    'msg' => 1, // CURLMSG_DONE
                    'result' => $ok ? 0 : $ch->errNo,
                    'handle' => $ch,
                );
            }
        }
        $running = 0;
        return 0;
    }

    function curl_multi_getcontent($ch)
    {
        if (!($ch instanceof __ShimCurlHandle)) {
            return '';
        }
        return $ch->output;
    }

    function curl_multi_info_read($mh, &$queued = null)
    {
        if (!($mh instanceof __ShimCurlMulti) || count($mh->doneQueue) === 0) {
            $queued = 0;
            return false;
        }
        $item = array_shift($mh->doneQueue);
        $queued = count($mh->doneQueue);
        return $item;
    }

    function curl_multi_select($mh, $timeout = 0.0)
    {
        return 0;
    }

    function curl_multi_setopt($mh, $opt, $value)
    {
        return true;
    }

    function curl_multi_errno($mh)
    {
        return 0;
    }

    function curl_multi_close($mh)
    {
        if ($mh instanceof __ShimCurlMulti) {
            $mh->handles = array();
            $mh->doneQueue = array();
        }
    }
}

// ============================ 4. openssl_* 用户态垫片 ============================
// 内置二进制未编译 openssl 扩展, openssl_decrypt/openssl_encrypt 等函数不存在
// (函数名空位可直接声明)。这里纯 PHP 实现 AES-128/192/256 CBC/ECB 加解密,
// 满足解析脚本常见的"AES-CBC 解密接口数据"用法。
if (!function_exists('openssl_decrypt')) {
    if (!defined('OPENSSL_RAW_DATA')) {
        define('OPENSSL_RAW_DATA', 1);
    }
    if (!defined('OPENSSL_ZERO_PADDING')) {
        define('OPENSSL_ZERO_PADDING', 2);
    }

    function __aes_xtime($x)
    {
        $x &= 0xff;
        return (($x << 1) ^ (($x & 0x80) ? 0x1b : 0)) & 0xff;
    }

    /** GF(2^8) 乘法 */
    function __aes_gmul($a, $b)
    {
        $p = 0;
        for ($i = 0; $i < 8; $i++) {
            if ($b & 1) {
                $p ^= $a;
            }
            $hi = $a & 0x80;
            $a = ($a << 1) & 0xff;
            if ($hi) {
                $a ^= 0x1b;
            }
            $b >>= 1;
        }
        return $p;
    }

    /** 延迟构建 exp/log/sbox/invsbox/rcon 表 (每请求一次) */
    function __aes_tables()
    {
        static $t = null;
        if ($t !== null) {
            return $t;
        }
        $exp = array();
        $log = array();
        $x = 1;
        for ($i = 0; $i < 255; $i++) {
            $exp[$i] = $x;
            $log[$x] = $i;
            $x = __aes_xtime($x) ^ $x;
        }
        $exp[255] = $exp[0];
        $sbox = array();
        $inv = array();
        for ($s = 0; $s < 256; $s++) {
            $i = ($s === 0) ? 0 : $exp[255 - $log[$s]];
            $i &= 0xff;
            $r1 = (($i << 1) | ($i >> 7)) & 0xff;
            $r2 = (($i << 2) | ($i >> 6)) & 0xff;
            $r3 = (($i << 3) | ($i >> 5)) & 0xff;
            $r4 = (($i << 4) | ($i >> 4)) & 0xff;
            $sb = ($i ^ $r1 ^ $r2 ^ $r3 ^ $r4 ^ 0x63) & 0xff;
            $sbox[$s] = $sb;
            $inv[$sb] = $s;
        }
        $rcon = array();
        $rc = 1;
        for ($i = 0; $i < 15; $i++) {
            $rcon[$i] = $rc;
            $rc = __aes_xtime($rc);
        }
        $t = array($sbox, $inv, $exp, $log, $rcon);
        return $t;
    }

    /** 密钥扩展 → 每轮 16 字节轮密钥; 返回 array($roundKeys, $nr) */
    function __aes_expand($key, $nk)
    {
        list($sbox, $inv, $exp, $log, $rcon) = __aes_tables();
        $key = array_values(unpack('C*', (string) $key)); // 字符串→字节整型数组 (否则 $key[i] 是单字符)
        $nr = $nk + 6;
        $w = array();
        for ($i = 0; $i < $nk; $i++) {
            $w[$i] = array($key[4 * $i], $key[4 * $i + 1], $key[4 * $i + 2], $key[4 * $i + 3]);
        }
        $rc = 1;
        $total = 4 * ($nr + 1);
        for ($i = $nk; $i < $total; $i++) {
            $t = $w[$i - 1];
            if ($i % $nk === 0) {
                $t = array($t[1], $t[2], $t[3], $t[0]);
                $t = array($sbox[$t[0]] ^ $rc, $sbox[$t[1]], $sbox[$t[2]], $sbox[$t[3]]);
                $rc = __aes_xtime($rc);
            } elseif ($nk > 6 && ($i % $nk) === 4) {
                $t = array($sbox[$t[0]], $sbox[$t[1]], $sbox[$t[2]], $sbox[$t[3]]);
            }
            $prev = $w[$i - $nk];
            $w[$i] = array($prev[0] ^ $t[0], $prev[1] ^ $t[1], $prev[2] ^ $t[2], $prev[3] ^ $t[3]);
        }
        $rks = array();
        for ($r = 0; $r <= $nr; $r++) {
            $blk = array();
            for ($j = 0; $j < 4; $j++) {
                $word = $w[4 * $r + $j];
                $blk[] = $word[0];
                $blk[] = $word[1];
                $blk[] = $word[2];
                $blk[] = $word[3];
            }
            $rks[] = $blk;
        }
        return array($rks, $nr);
    }

    function __aes_encrypt_block($b, $rks, $nr)
    {
        list($sbox) = __aes_tables();
        for ($i = 0; $i < 16; $i++) {
            $b[$i] ^= $rks[0][$i];
        }
        for ($r = 1; $r <= $nr; $r++) {
            // SubBytes + ShiftRows (行 r 左移 r)
            $s = array();
            for ($c = 0; $c < 4; $c++) {
                for ($row = 0; $row < 4; $row++) {
                    $s[4 * $c + $row] = $sbox[$b[4 * (($c + $row) % 4) + $row]];
                }
            }
            // MixColumns (最后一轮跳过)
            if ($r < $nr) {
                for ($c = 0; $c < 4; $c++) {
                    $a0 = $s[4 * $c];
                    $a1 = $s[4 * $c + 1];
                    $a2 = $s[4 * $c + 2];
                    $a3 = $s[4 * $c + 3];
                    $t0 = __aes_xtime($a0);
                    $t1 = __aes_xtime($a1);
                    $t2 = __aes_xtime($a2);
                    $t3 = __aes_xtime($a3);
                    $s[4 * $c] = $t0 ^ ($t1 ^ $a1) ^ $a2 ^ $a3;
                    $s[4 * $c + 1] = $a0 ^ $t1 ^ ($t2 ^ $a2) ^ $a3;
                    $s[4 * $c + 2] = $a0 ^ $a1 ^ $t2 ^ ($t3 ^ $a3);
                    $s[4 * $c + 3] = ($t0 ^ $a0) ^ $a1 ^ $a2 ^ $t3;
                }
            }
            $rk = $rks[$r];
            for ($i = 0; $i < 16; $i++) {
                $b[$i] = $s[$i] ^ $rk[$i];
            }
        }
        return $b;
    }

    function __aes_decrypt_block($b, $rks, $nr)
    {
        list($sbox, $inv) = __aes_tables();
        // 初始 AddRoundKey(nr)
        for ($i = 0; $i < 16; $i++) {
            $b[$i] ^= $rks[$nr][$i];
        }
        for ($r = $nr - 1; $r >= 0; $r--) {
            // InvShiftRows (行 r 右移 r) + InvSubBytes
            $s = array();
            for ($c = 0; $c < 4; $c++) {
                for ($row = 0; $row < 4; $row++) {
                    $s[4 * (($c + $row) % 4) + $row] = $inv[$b[4 * $c + $row]];
                }
            }
            // 键序复位: $s 的键按 dest 公式乱序插入, 而 pack('C*', ...$b) 按插入顺序展开,
            // 不 ksort 会导致输出字节乱序 (加密侧 dest 顺序天然递增故无此问题)
            ksort($s);
            $b = $s;
            // AddRoundKey(r): 必须先 xor 轮密钥, 再 InvMixColumns (标准逆轮, 轮密钥未预变换)
            $rk = $rks[$r];
            for ($i = 0; $i < 16; $i++) {
                $b[$i] ^= $rk[$i];
            }
            if ($r > 0) {
                // InvMixColumns
                for ($c = 0; $c < 4; $c++) {
                    $a0 = $b[4 * $c];
                    $a1 = $b[4 * $c + 1];
                    $a2 = $b[4 * $c + 2];
                    $a3 = $b[4 * $c + 3];
                    $b[4 * $c] = __aes_gmul($a0, 14) ^ __aes_gmul($a1, 11) ^ __aes_gmul($a2, 13) ^ __aes_gmul($a3, 9);
                    $b[4 * $c + 1] = __aes_gmul($a0, 9) ^ __aes_gmul($a1, 14) ^ __aes_gmul($a2, 11) ^ __aes_gmul($a3, 13);
                    $b[4 * $c + 2] = __aes_gmul($a0, 13) ^ __aes_gmul($a1, 9) ^ __aes_gmul($a2, 14) ^ __aes_gmul($a3, 11);
                    $b[4 * $c + 3] = __aes_gmul($a0, 11) ^ __aes_gmul($a1, 13) ^ __aes_gmul($a2, 9) ^ __aes_gmul($a3, 14);
                }
            }
        }
        return $b;
    }

    /** 解析 "aes-256-cbc" 等 → array(字节长度, 轮数, 模式) 或 null */
    function __aes_parse_cipher($method)
    {
        $m = strtolower((string) $method);
        if (!preg_match('/^aes-(128|192|256)-(cbc|ecb)$/', $m, $mm)) {
            return null;
        }
        $bytes = ((int) $mm[1]) / 8;
        $nk = $bytes / 4;
        return array($bytes, $nk, $mm[2]);
    }

    function openssl_encrypt($data, $method, $passphrase, $options = 0, $iv = '')
    {
        $spec = __aes_parse_cipher($method);
        if ($spec === null) {
            trigger_error('openssl_encrypt(): Unsupported cipher method', E_USER_WARNING);
            return false;
        }
        list($klen, $nk, $mode) = $spec;
        $raw = ((int) $options) & 1;
        if (!$raw) {
            $data = base64_decode($data, true);
            if ($data === false) {
                return false;
            }
        }
        $key = str_pad((string) $passphrase, $klen, "\0");
        $key = substr($key, 0, $klen);
        $zeroPad = (((int) $options) & 2) !== 0;
        if (!$zeroPad) {
            $n = 16 - (strlen($data) % 16);
            $data .= str_repeat(chr($n), $n);
        } else {
            $data .= str_repeat("\0", (16 - (strlen($data) % 16)) % 16);
        }
        list($rks, $nr) = __aes_expand($key, $nk);
        $out = '';
        if ($mode === 'ecb') {
            $prev = null;
            for ($i = 0; $i < strlen($data); $i += 16) {
                $b = array_values(unpack('C16', substr($data, $i, 16)));
                $b = __aes_encrypt_block($b, $rks, $nr);
                $out .= pack('C*', ...$b);
            }
        } else { // cbc
            $ivb = str_pad(substr((string) $iv, 0, 16), 16, "\0");
            $prev = array_values(unpack('C16', $ivb));
            for ($i = 0; $i < strlen($data); $i += 16) {
                $b = array_values(unpack('C16', substr($data, $i, 16)));
                for ($j = 0; $j < 16; $j++) {
                    $b[$j] ^= $prev[$j];
                }
                $b = __aes_encrypt_block($b, $rks, $nr);
                $prev = $b;
                $out .= pack('C*', ...$b);
            }
        }
        return $raw ? $out : base64_encode($out);
    }

    function openssl_decrypt($data, $method, $passphrase, $options = 0, $iv = '')
    {
        $spec = __aes_parse_cipher($method);
        if ($spec === null) {
            trigger_error('openssl_decrypt(): Unsupported cipher method', E_USER_WARNING);
            return false;
        }
        list($klen, $nk, $mode) = $spec;
        $raw = ((int) $options) & 1;
        if (!$raw) {
            $data = base64_decode($data, true);
            if ($data === false) {
                return false;
            }
        }
        if (strlen($data) % 16 !== 0 || strlen($data) === 0) {
            trigger_error('openssl_decrypt(): Data is not a multiple of block size', E_USER_WARNING);
            return false;
        }
        $key = str_pad((string) $passphrase, $klen, "\0");
        $key = substr($key, 0, $klen);
        list($rks, $nr) = __aes_expand($key, $nk);
        $out = '';
        $zeroPad = (((int) $options) & 2) !== 0;
        if ($mode === 'ecb') {
            for ($i = 0; $i < strlen($data); $i += 16) {
                $b = array_values(unpack('C16', substr($data, $i, 16)));
                $b = __aes_decrypt_block($b, $rks, $nr);
                $out .= pack('C*', ...$b);
            }
        } else { // cbc
            $ivb = str_pad(substr((string) $iv, 0, 16), 16, "\0");
            $prev = array_values(unpack('C16', $ivb));
            for ($i = 0; $i < strlen($data); $i += 16) {
                $b = array_values(unpack('C16', substr($data, $i, 16)));
                $cur = $b;
                $b = __aes_decrypt_block($b, $rks, $nr);
                for ($j = 0; $j < 16; $j++) {
                    $b[$j] ^= $prev[$j];
                }
                $prev = $cur;
                $out .= pack('C*', ...$b);
            }
        }
        if (!$zeroPad) {
            $n = ord($out[strlen($out) - 1]);
            if ($n < 1 || $n > 16) {
                trigger_error('openssl_decrypt(): bad decrypt', E_USER_WARNING);
                return false;
            }
            for ($i = 1; $i <= $n; $i++) {
                if (ord($out[strlen($out) - $i]) !== $n) {
                    trigger_error('openssl_decrypt(): bad decrypt', E_USER_WARNING);
                    return false;
                }
            }
            $out = substr($out, 0, strlen($out) - $n);
        }
        return $out;
    }

    function openssl_get_cipher_methods($method_aliases = true)
    {
        return array('aes-128-cbc', 'aes-192-cbc', 'aes-256-cbc', 'aes-128-ecb', 'aes-192-ecb', 'aes-256-ecb');
    }

    function openssl_cipher_iv_length($method)
    {
        $spec = __aes_parse_cipher($method);
        if ($spec === null) {
            return false;
        }
        return $spec[2] === 'cbc' ? 16 : 0;
    }
}

// ============================ 5. ssl:// 套接字传输垫片 ============================
// 内置二进制未编译 openssl 扩展, 也没有 "ssl" 套接字传输层 —— 脚本调
// stream_socket_client('ssl://host:port') / fsockopen('ssl://...') (WebSocket 心跳等
// 长连接用法) 原生报 "Unable to find the socket transport ssl"。
// PhpRuntimeBridge 的二进制补丁已把这两个函数原地等长改名 (xtream_socket_client /
// xsockopen), 函数名空位由这里用户态接管:
//   ssl/tls 方案 → 连 LocalServer 的 8081 裸回环隧道 (一行 "UPSTREAM host port\n"
//                  握手), Java 侧对远端做真实 TLS 后回 "OK\n", 再双向转发明文字节;
//                  返回的是原生 tcp 流资源, fread/fwrite/fclose/stream_set_timeout
//                  语义与原生完全一致, 脚本无感知。
//   其他方案     → 委派给改名后的原生函数 (xtream_socket_client / xsockopen)。
// 换用自带 TLS 的 php 二进制时原生函数仍在, 本块整体跳过。
if (!function_exists('stream_socket_client')) {

    /**
     * 通过回环 TLS 隧道建立 "ssl" 连接, 成功返回原生流资源, 失败置 $errno/$errstr 返 false。
     * @param string $rest 形如 "host:port"
     * @return resource|false
     */
    function __php_upstream_ssl($rest, $timeout, &$errno, &$errstr)
    {
        $errno = 0;
        $errstr = '';
        $host = trim((string) $rest);
        $port = 443;
        if (preg_match('/^(\[[0-9A-Fa-f:.]+\]|[^\s:]+):(\d+)$/', $host, $m)) {
            $host = $m[1];
            $port = (int) $m[2];
        }
        $host = trim($host, '[]');
        if ($host === '' || preg_match('/[\x00-\x20]/', $host)) {
            $errno = 22;
            $errstr = 'Invalid argument (bad ssl address)';
            return false;
        }
        $t = (int) ceil((float) $timeout);
        if ($t < 1) {
            $t = 1;
        }
        $t0 = microtime(true);
        $cerr = 0;
        $cmsg = '';
        $fp = @fsockopen('127.0.0.1', 8081, $cerr, $cmsg, min($t, 10));
        if (!$fp) {
            $errno = $cerr ? $cerr : 111;
            $errstr = 'ssl tunnel (127.0.0.1:8081): ' . $cmsg;
            error_log('[sslshim] tunnel connect fail: ' . $cmsg);
            return false;
        }
        stream_set_timeout($fp, $t); // 握手读 (远端 TCP+TLS 可能耗时, 上限 $t 秒)
        fwrite($fp, "UPSTREAM $host $port\n");
        $line = fgets($fp, 1024);
        if (!is_string($line) || strpos($line, 'OK') !== 0) {
            $errno = 111;
            $errstr = 'ssl tunnel: ' . (is_string($line) && trim($line) !== '' ? trim($line) : 'no response');
            error_log('[sslshim] handshake fail after ' . round(microtime(true) - $t0, 2) . 's: ' . $errstr);
            fclose($fp);
            return false;
        }
        error_log('[sslshim] ' . $host . ':' . $port . ' ok in ' . round(microtime(true) - $t0, 2) . 's');
        // 握手完成: 读超时按原生语义交还 default_socket_timeout (脚本可自行 stream_set_timeout)
        $dft = (int) ini_get('default_socket_timeout');
        stream_set_timeout($fp, $dft > 0 ? $dft : 60);
        return $fp;
        // 握手完成: 读超时按原生语义交还 default_socket_timeout (脚本可自行 stream_set_timeout)
        $dft = (int) ini_get('default_socket_timeout');
        stream_set_timeout($fp, $dft > 0 ? $dft : 60);
        return $fp;
    }

    /**
     * 用户态 stream_socket_client: ssl/tls → 回环 TLS 隧道; 其他 → 原生委派。
     * @return resource|false
     */
    function stream_socket_client($address, &$errno = null, &$errstr = null, $timeout = null, $flags = null, $context = null)
    {
        $addr = (string) $address;
        $scheme = 'tcp';
        $rest = $addr;
        if (($p = strpos($addr, '://')) !== false) {
            $scheme = strtolower(substr($addr, 0, $p));
            $rest = substr($addr, $p + 3);
        }
        if (in_array($scheme, array('ssl', 'tls', 'sslv2', 'sslv3', 'sslv23'), true)) {
            return __php_upstream_ssl($rest, $timeout === null ? 60 : (float) $timeout, $errno, $errstr);
        }
        if (function_exists('xtream_socket_client')) {
            $fn = 'xtream_socket_client';
            if ($flags === null) {
                $flags = STREAM_CLIENT_CONNECT;
            }
            return $fn($addr, $errno, $errstr, $timeout, $flags, $context);
        }
        $errno = 1;
        $errstr = 'stream_socket_client unavailable';
        return false;
    }
}

if (!function_exists('fsockopen')) {

    /**
     * 用户态 fsockopen: ssl/tls → 回环 TLS 隧道; 其他 → 原生委派 (xsockopen)。
     * @return resource|false
     */
    function fsockopen($hostname, $port = -1, &$errno = null, &$errstr = null, $timeout = null)
    {
        $host = (string) $hostname;
        $scheme = 'tcp';
        $rest = $host;
        if (($p = strpos($host, '://')) !== false) {
            $scheme = strtolower(substr($host, 0, $p));
            $rest = substr($host, $p + 3);
        }
        if (in_array($scheme, array('ssl', 'tls', 'sslv2', 'sslv3', 'sslv23'), true)) {
            $addr = ($port === null || (int) $port === -1) ? $rest : $rest . ':' . (int) $port;
            return __php_upstream_ssl($addr, $timeout === null ? 60 : (float) $timeout, $errno, $errstr);
        }
        if (function_exists('xsockopen')) {
            $fn = 'xsockopen';
            return $fn($hostname, $port, $errno, $errstr, $timeout);
        }
        if (function_exists('stream_socket_client')) {
            $addr = strpos($host, '://') === false ? 'tcp://' . $host : $host;
            if ($port !== null && (int) $port !== -1 && strpos($host, '://') === false) {
                $addr = 'tcp://' . $host . ':' . (int) $port;
            }
            return stream_socket_client($addr, $errno, $errstr, $timeout);
        }
        $errno = 1;
        $errstr = 'fsockopen unavailable';
        return false;
    }
}

// ============================ 6. mbstring 用户态垫片 ============================
// 内置 php 未编译 mbstring: TVBox 系脚本 (如 migu.php) 常按 function_exists('mb_ord')
// 分支兜底到 mb_convert_encoding, 两个都没有 → 未捕获 fatal 整个请求报废。
// 这里补齐 UTF-8 语义的常用函数; 无 iconv 时非 UTF-8 编码不做全量转码表,
// 无法识别的组合原样返回 (宁可结果退化也不 fatal)。
if (!function_exists('mb_ord')) {
    function mb_ord($s, $encoding = 'UTF-8')
    {
        $s = (string) $s;
        if ($s === '') {
            return false;
        }
        $enc = strtoupper(str_replace(array('-', ' '), '', (string) $encoding));
        if ($enc === '' || $enc === 'UTF8') {
            $c = ord($s[0]);
            if ($c < 0x80) {
                return $c;
            }
            $n = strlen($s);
            if (($c & 0xE0) === 0xC0 && $n >= 2) {
                return (($c & 0x1F) << 6) | (ord($s[1]) & 0x3F);
            }
            if (($c & 0xF0) === 0xE0 && $n >= 3) {
                return (($c & 0x0F) << 12) | ((ord($s[1]) & 0x3F) << 6) | (ord($s[2]) & 0x3F);
            }
            if (($c & 0xF8) === 0xF0 && $n >= 4) {
                return (($c & 0x07) << 18) | ((ord($s[1]) & 0x3F) << 12)
                     | ((ord($s[2]) & 0x3F) << 6) | (ord($s[3]) & 0x3F);
            }
            return false;
        }
        return ord($s[0]); // 其它单字节编码近似
    }
}

if (!function_exists('mb_convert_encoding')) {
    function mb_convert_encoding($s, $to_encoding, $from_encoding = null)
    {
        $s = (string) $s;
        $norm = function ($e) {
            return strtoupper(str_replace(array('-', ' '), '', (string) $e));
        };
        $to = $norm($to_encoding);
        $from = $from_encoding === null ? 'UTF8' : $norm($from_encoding);
        if ($to === $from || $s === '') {
            return $s;
        }
        $ucs4 = array('UCS4BE', 'UCS32BE', 'UTF32BE', 'UCS4', 'UTF32');
        $utf8 = array('UTF8', '');
        // UTF-8 -> UCS-4BE (migu.php 的兜底分支: unpack('N', ...))
        if (in_array($to, $ucs4, true) && in_array($from, $utf8, true)) {
            $ord = mb_ord($s, 'UTF-8');
            return $ord === false ? $s : pack('N', $ord);
        }
        // UCS-4BE -> UTF-8
        if (in_array($from, $ucs4, true) && in_array($to, $utf8, true)) {
            if (strlen($s) < 4) {
                return $s;
            }
            $t = unpack('N', substr($s, 0, 4));
            $cp = $t[1];
            if ($cp < 0x80) {
                return chr($cp);
            }
            if ($cp < 0x800) {
                return chr(0xC0 | ($cp >> 6)) . chr(0x80 | ($cp & 0x3F));
            }
            if ($cp < 0x10000) {
                return chr(0xE0 | ($cp >> 12)) . chr(0x80 | (($cp >> 6) & 0x3F)) . chr(0x80 | ($cp & 0x3F));
            }
            return chr(0xF0 | ($cp >> 18)) . chr(0x80 | (($cp >> 12) & 0x3F))
                 . chr(0x80 | (($cp >> 6) & 0x3F)) . chr(0x80 | ($cp & 0x3F));
        }
        if (function_exists('iconv')) {
            $r = @iconv($from === '' ? 'UTF-8' : $from, $to . '//TRANSLIT', $s);
            if ($r !== false) {
                return $r;
            }
        }
        return $s; // 无 iconv: 退化为原样返回, 不 fatal
    }
}

// ---- 以下 UTF-8 便捷函数: 有效 UTF-8 用 PCRE 按码点处理, 否则按字节退化 ----

if (!function_exists('mb_strlen')) {
    function mb_strlen($s, $encoding = null)
    {
        $s = (string) $s;
        if (!preg_match('//u', $s)) {
            return strlen($s);
        }
        return (int) preg_match_all('/./us', $s);
    }
}

if (!function_exists('mb_substr')) {
    function mb_substr($s, $start, $len = null, $encoding = null)
    {
        $s = (string) $s;
        if (!preg_match('//u', $s)) {
            return $len === null ? substr($s, (int) $start) : substr($s, (int) $start, (int) $len);
        }
        $chars = preg_split('//u', $s, -1, PREG_SPLIT_NO_EMPTY);
        if ($chars === false) {
            return $s;
        }
        $slice = $len === null ? array_slice($chars, (int) $start) : array_slice($chars, (int) $start, (int) $len);
        return implode('', $slice);
    }
}

if (!function_exists('mb_strpos')) {
    function mb_strpos($haystack, $needle, $offset = 0, $encoding = null)
    {
        $haystack = (string) $haystack;
        $needle = (string) $needle;
        if ($needle === '' || !preg_match('//u', $haystack) || !preg_match('//u', $needle)) {
            return strpos($haystack, $needle, (int) $offset);
        }
        $byteOffset = 0;
        $rest = $haystack;
        $o = (int) $offset;
        while ($o > 0) {
            $m = preg_match('/./us', $rest, $mm);
            if ($m !== 1) {
                return false;
            }
            $byteOffset += strlen($mm[0]);
            $rest = substr($rest, strlen($mm[0]));
            $o--;
        }
        $p = strpos($haystack, $needle, $byteOffset);
        if ($p === false) {
            return false;
        }
        return preg_match_all('/./us', substr($haystack, 0, $p));
    }
}

if (!function_exists('mb_strcut')) {
    function mb_strcut($s, $start, $len = null, $encoding = null)
    {
        $s = (string) $s;
        $start = (int) $start;
        if (!preg_match('//u', $s)) {
            return $len === null ? substr($s, $start) : substr($s, $start, (int) $len);
        }
        $chars = preg_split('//u', $s, -1, PREG_SPLIT_NO_EMPTY);
        if ($chars === false) {
            return $s;
        }
        $slice = $len === null ? array_slice($chars, $start) : array_slice($chars, $start, (int) $len);
        return implode('', $slice);
    }
}

if (!function_exists('mb_strtolower')) {
    function mb_strtolower($s, $encoding = null)
    {
        // 无 Intl/mbstring 全表: ASCII 精确转换, 非 ASCII 码点原样保留
        return strtolower((string) $s);
    }
}

if (!function_exists('mb_strtoupper')) {
    function mb_strtoupper($s, $encoding = null)
    {
        return strtoupper((string) $s);
    }
}

if (!function_exists('mb_check_encoding')) {
    function mb_check_encoding($value = null, $encoding = null)
    {
        if ($value === null) {
            return true;
        }
        if (is_array($value)) {
            foreach ($value as $v) {
                if (!mb_check_encoding($v, $encoding)) {
                    return false;
                }
            }
            return true;
        }
        $enc = strtoupper(str_replace(array('-', ' '), '', (string) $encoding));
        if ($enc === '' || $enc === 'UTF8') {
            return preg_match('//u', (string) $value) === 1;
        }
        return true; // 其它编码无校验表
    }
}
