# -*- coding: utf-8 -*-
"""mock CMS (macCMS XML) + static file server for TVBox v1.5 emulator test"""
import zlib, struct, os, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

ROOT = os.path.dirname(os.path.abspath(__file__))

def png(path_color):
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)
    w = h = 64
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    row = b"\x00" + bytes(path_color) * (w * 3)
    idat = chunk(b"IDAT", zlib.compress(row * h))
    iend = chunk(b"IEND", b"")
    return sig + ihdr + idat + iend

PICS = {
    "/pic1.png": png((220, 80, 60)),
    "/pic2.png": png((60, 140, 220)),
}

VIDEOS = {
    "1": {
        "name": "今天是太阳雨",
        "pic": "http://127.0.0.1:8000/pic1.png@Referer=http://127.0.0.1:8000/@User-Agent=MozillaTestUA",
        "note": "全集",
        "dd": [("线路A", "第01集$http://127.0.0.1:8000/video.mp4#第02集$http://127.0.0.1:8000/video.mp4")],
    },
    "2": {
        "name": "两京十五日",
        "pic": "http://127.0.0.1:8000/pic2.png",
        "note": "更新01",
        "dd": [],
    },
}

# 多源并发搜索测试：另外两个 JSON(type=1) CMS 源，同名不同线路；其中一个故意慢 4 秒
JSON_SOURCES = {
    "/cms2.php": {"vid": "101", "name": "今天是太阳雨", "pic": "http://127.0.0.1:8000/pic2.png",
                  "remark": "HD中字", "flag": "线路B", "delay": 0},
    "/cms3.php": {"vid": "201", "name": "今天是太阳雨", "pic": "http://127.0.0.1:8000/pic1.png",
                  "remark": "抢先版", "flag": "线路C", "delay": 4},
}


def json_list(src):
    import json as _json
    return _json.dumps({
        "code": 1, "msg": "数据列表", "page": 1, "pagecount": 1, "limit": "20", "total": 1,
        "list": [{
            "vod_id": src["vid"], "vod_name": src["name"], "vod_pic": src["pic"],
            "vod_remarks": src["remark"], "vod_lang": "国语", "vod_area": "大陆",
            "vod_year": "2026", "vod_director": "JSON导演", "vod_actor": "JSON演员",
            "vod_content": "JSON 源简介",
            "vod_play_from": src["flag"],
            "vod_play_url": "第01集$http://127.0.0.1:8000/video.mp4",
        }],
    }, ensure_ascii=False).encode("utf-8")

def xml_video(v, vid):
    dds = "".join('<dd flag="%s">%s</dd>' % (f, u) for f, u in v["dd"])
    return ("<video><id>%s</id><name>%s</name><pic>%s</pic>"
            "<note>%s</note><director>测试导演</director><actor>测试演员</actor>"
            "<des>测试简介</des><year>2026</year>%s</video>") % (vid, v["name"], v["pic"], v["note"], dds)

def list_xml(ids):
    videos = "".join(xml_video(VIDEOS[i], i) for i in ids if i in VIDEOS)
    return ('<?xml version="1.0" encoding="utf-8"?><list pagecount="1">%s</list>' % videos).encode()

def class_xml():
    return ('<?xml version="1.0" encoding="utf-8"?><class><ty id="1">电影</ty>'
            '<ty id="2">剧集</ty></class>').encode("utf-8")

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        print("[mock]", a[0] % a[1:], flush=True)

    def do_GET(self):
        p = urlparse(self.path)
        q = parse_qs(p.query)
        path = unquote(p.path)
        if path == "/test.json":
            body = ('{"sites":[{"key":"mockcms","name":"测试CMS","type":0,"api":"http://127.0.0.1:8000/cms.php","searchable":1},'
                    '{"key":"mockjson","name":"测试CMS-JSON","type":1,"api":"http://127.0.0.1:8000/cms2.php","searchable":1},'
                    '{"key":"mockslow","name":"测试慢源","type":1,"api":"http://127.0.0.1:8000/cms3.php","searchable":1},'
                    '{"key":"douban_test","name":"豆瓣推荐","type":3,"api":"csp_Douban","searchable":0,"quickSearch":0,"filterable":0,"jar":"http://127.0.0.1:8000/custom_spider.jar"}]}').encode("utf-8")
        elif path in JSON_SOURCES:
            src = JSON_SOURCES[path]
            if src["delay"]:
                import time as _t
                _t.sleep(src["delay"])
            body = json_list(src)
        elif path == "/custom_spider.jar":
            with open(os.path.join(ROOT, "custom_spider.jar"), "rb") as f:
                body = f.read()
        elif path == "/cms.php":
            ac = q.get("ac", [""])[0]
            wd = q.get("wd", [None])[0]
            ids = q.get("ids", [None])[0]
            if wd is not None:
                keys = [k for k, v in VIDEOS.items() if wd in v["name"]]
                body = list_xml(keys)
            elif ids is not None:
                body = list_xml(ids.split(","))
            elif ac == "class":
                body = class_xml()
            else:
                body = list_xml(list(VIDEOS.keys()))
        elif path in PICS:
            body = PICS[path]
        elif path == "/video.mp4":
            body = b"\x00" * 64
        else:
            self.send_response(404); self.end_headers(); return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

if __name__ == "__main__":
    srv = ThreadingHTTPServer(("0.0.0.0", 8000), H)
    print("mock server on :8000", flush=True)
    srv.serve_forever()
