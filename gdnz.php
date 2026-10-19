<?php
main($_GET['id']);



function main($id)
{
    $r = load_from_cache($id);
    if ($r && locate_or_echoContent($r)) {
        return;
    }

    $r = get_m3u8_url_from_web($id);
    save_to_cache($id, $r);

    locate_or_echoContent($r);
}

function locate_or_echoContent($url)
{
    if (can_locate($url['url'])) {
        locate($url['url']);
        return true;
    } else {
        $c = request($url['url'], false);
        if (!empty($c)) {
            echo_content($c);
            return true;
        }
    }
    return false;
}

function can_locate($url)
{
    $h = parse_url($url, PHP_URL_HOST);
    return strpos($h, '.itouchtv.cn') === false;
}

function locate($url) {
    header('Access-Control-Allow-Origin: *');
    header('Location: '.$url);
}

function echo_content($content)
{
    header("Access-Control-Allow-Origin: *");
    header("Content-Type: application/vnd.apple.mpegurl");
    echo $content;
}

function get_m3u8_url_from_web($id) {
    $url = "https://tcdn-api.itouchtv.cn/getParam";
    $data = request($url);
    $json = json_decode($data);
    $node = $json->node;

    send_heartbeat($node, false, $wsnode);

//    request($url);

    $url = "https://gdtv-api.gdtv.cn/api/tv/v2/tvChannel/$id?node=".base64_encode($wsnode);
    request($url, false, "OPTIONS");

    $data = request($url);
    $json = json_decode($data);
    $playURL = json_decode($json->playUrl)->hd;

    return [
        'url' => $playURL,
        'node' => $node,
    ];
}

function save_to_cache($id, $url)
{
    array_to_file($url, "gdtv_cache/$id.txt");
}

function load_from_cache($id)
{
    $cacheFile = "gdtv_cache/$id.txt";
    $expireSeconds = 60 * 30;
    if (file_exists($cacheFile) && (time() - filemtime($cacheFile) < $expireSeconds)) {
        file_to_array($cacheFile, $a);
        return $a;
    } else {
        return null;
    }
}

function file_to_array($filename, &$array) {
    $array = [];
    if (file_exists($filename)) {
        $handle = fopen($filename, 'r');
        if (flock($handle, LOCK_SH)) { // 共享锁，允许其他进程读但禁止写
            $data = file_get_contents($filename);
            $array = unserialize($data);
            flock($handle, LOCK_UN);
        }
        fclose($handle);
    }
    return true;
}

function array_to_file($array, $filename) {
    $dir = dirname($filename);
    if (!is_dir($dir) && is_writable(dirname($dir))) {
        if (!@mkdir($dir, 0755, true))
            return false;
    }
    $data = serialize($array);
    file_put_contents($filename, $data, LOCK_EX);
    return true;
}

function request($url, $sign_in_header = true, $method = null) {
    $ch = curl_init();
    $o = [
        CURLOPT_URL => $url,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_SSL_VERIFYPEER => false,
    ];
    if ($sign_in_header) {
        $t = round(microtime(true) * 1000);
        $k = 'dfkcY1c3sfuw1Cii9DWj8UO3iQy2hqlDxyvDXd1oVMxwYVDSgeB6phO9eW1dfuwX';
        $sign = base64_encode(hash_hmac("SHA256","GET\n$url\n$t\n",$k,true));
        $header = [
            "Referer: https://www.gdtv.cn/",
            "Origin: https://www.gdtv.cn",
//            "User-Agent: Mozilla/5.0 (Linux; U; Android 9; zh-cn; Redmi Note 5 Build/PKQ1.180904.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/71.0.3578.141 Mobile Safari/537.36 XiaoMi/MiuiBrowser/11.10.8",
            "User-Agent: Mozilla/5.0 (Linux; U; Android 9)",
            "X-Itouchtv-Ca-Key: 89541943007407288657755311868534",
            "X-Itouchtv-Ca-Signature: $sign",
            "X-Itouchtv-Ca-Timestamp: $t",
            "X-Itouchtv-Client: WEB_M",
            "X-Itouchtv-Device-Id: WEBM_0",
        ];
    } else {
        $header = [
            "Referer: https://www.gdtv.cn/",
        ];
    }
    $o[CURLOPT_HTTPHEADER] = $header;
    if ($method !== null)
        $o[CURLOPT_CUSTOMREQUEST] = $method;
    curl_setopt_array($ch, $o);
    $data = curl_exec($ch);
    if ($data === false) {
        echo 'Err ' . curl_errno($ch) .': '. curl_error($ch);
        curl_close($ch);
        die;
    }
    curl_close($ch);
    return $data;
}

function send_heartbeat($node, $twice = false, &$wsnode = null) {

    $encode = function($data) {
        $len = strlen($data);
        $head[0] = 129;
        $mask = [];
        for ($j = 0; $j < 4; $j ++) {
            $mask[] = mt_rand(1, 128);
        }
        $split = str_split(sprintf('%016b', $len), 8);
        $head[1] = 254;
        $head[2] = bindec($split[0]);
        $head[3] = bindec($split[1]);
        $head = array_merge($head, $mask);
        foreach ($head as $k => $v) {
            $head[$k] = chr($v);
        }
        $mask_data = '';
        for ($j = 0; $j < $len; $j ++) {
            $mask_data .= chr(ord($data[$j]) ^ $mask[$j % 4]);
        }
        return implode('', $head).$mask_data;
    };

    $result = false;
//    static $sock = null;

    $contextOptions = ['ssl' => ["verify_peer"=>false,"verify_peer_name"=>false]];
    $context = stream_context_create($contextOptions);
//    if ($sock === null)
        $sock = stream_socket_client("ssl://tcdn-ws.itouchtv.cn:3800",$errno,$errstr,5,STREAM_CLIENT_CONNECT,$context);

    stream_set_timeout($sock,5);
    $key = '';
    for ($i = 0; $i < 16; $i++) {
        $key .= chr(rand(33, 126));
    }
    $key = base64_encode($key);
    $header = '';
    $header .= "GET /connect HTTP/1.1\r\n";
    $header .= "Host: tcdn-ws.itouchtv.cn:3800\r\n";
    $header .= "Upgrade: websocket\r\n";
    $header .= "Sec-WebSocket-Key: $key\r\n";
    fwrite($sock,$header."\r\n");
    $handshake = fread($sock, 4096);

    if(strstr($handshake,'Sec-Websocket-Accept')) {
        $wssData = json_encode(['route' => 'getwsparam','message' => $node]);
        $encoded_data = $encode($wssData);
        fwrite($sock, $encoded_data);
        if (func_num_args() > 2) {
            $param = fread($sock, 4096);
            $param = substr($param,4);
            $json = json_decode($param);
            $wsnode = $json->wsnode;
        }
//        if ($twice) {
//            fread($sock, 4096);
//            fwrite($sock, $encoded_data);
//        }
        $result = true;
    }

    fclose($sock);

    return $result;
}