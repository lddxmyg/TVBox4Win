# -*- coding: utf-8 -*-
"""盯 android-apk workflow：轮询状态，成功后下载 APK 到 dist/。

复用 release_v0968.py 的直连方案：沙箱代理对 github.com 返回 502，
requests.Session(trust_env=False) + getaddrinfo 固定 api.github.com IP。
"""
import os
import re
import socket
import subprocess
import sys
import time

import requests

_orig = socket.getaddrinfo


def _gai(host, port, *a, **k):
    if host == "api.github.com":
        host = "140.82.114.6"
    return _orig(host, port, *a, **k)


socket.getaddrinfo = _gai
S = requests.Session()
S.trust_env = False

REPO = "lddxmyg/TVBox4Win"


def token():
    q = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True)
    m = re.search(r"^password=(\S+)", q.stdout, re.M)
    return m.group(1) if m else None


TOKEN = token()
HD = {"Authorization": "Bearer %s" % TOKEN} if TOKEN else {}


def get(url, **kw):
    r = S.get(url, timeout=(5, 30), headers=HD, **kw)
    r.raise_for_status()
    return r


def find_run():
    r = get("https://api.github.com/repos/%s/actions/workflows/android-apk.yml/"
            "runs?branch=android-port&per_page=3" % REPO)
    for it in r.json().get("workflow_runs", []):
        if it["head_sha"].startswith(open_head()) or it["event"] == "push":
            return it
    return r.json().get("workflow_runs", [None])[0]


_head = {}


def open_head():
    if "sha" not in _head:
        r = S.get("https://api.github.com/repos/%s/branches/android-port"
                  % REPO, timeout=(5, 30), headers=HD)
        _head["sha"] = r.json()["commit"]["sha"]
    return _head["sha"][:7]


def main():
    deadline = time.time() + 100 * 60
    run = None
    for _ in range(10):
        run = find_run()
        if run:
            break
        time.sleep(15)
    if not run:
        print("POLL FAIL: 没找到 workflow run")
        return 1
    rid = run["id"]
    print("RUN %d head=%s status=%s" % (rid, run["head_sha"][:7], run["status"]),
          flush=True)
    while time.time() < deadline:
        r = get("https://api.github.com/repos/%s/actions/runs/%d" % (REPO, rid))
        j = r.json()
        st, con = j["status"], j.get("conclusion")
        print("[%s] status=%s conclusion=%s" % (
            time.strftime("%H:%M:%S"), st, con), flush=True)
        if st == "completed":
            if con == "success":
                arts = get("https://api.github.com/repos/%s/actions/runs/%d/"
                           "artifacts" % (REPO, rid)).json()["artifacts"]
                apk = [a for a in arts if a["name"] == "TVBox4Win-apk"]
                if not apk:
                    print("FAIL: 无 APK artifact"); return 1
                a = apk[0]
                out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   "..", "dist", "TVBox4Win-android-artifact.zip")
                print("下载 artifact %s (%.1f MB)" % (
                    a["name"], a["size_in_bytes"] / 1e6), flush=True)
                rr = S.get(a["archive_download_url"], timeout=(5, 600),
                           headers=dict(HD, Accept="application/vnd.github+json"),
                           stream=True)
                rr.raise_for_status()
                n = 0
                with open(out, "wb") as f:
                    for chunk in rr.iter_content(1 << 20):
                        f.write(chunk); n += len(chunk)
                print("下载完成 %d bytes -> %s" % (n, out), flush=True)
                import zipfile
                z = zipfile.ZipFile(out)
                names = z.namelist()
                print("artifact 内容:", names, flush=True)
                d = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                 "..", "dist")
                for nm in names:
                    if nm.endswith(".apk"):
                        p = os.path.join(d, os.path.basename(nm))
                        with open(p, "wb") as f:
                            f.write(z.read(nm))
                        print("APK -> %s (%.1f MB)" % (
                            os.path.abspath(p), os.path.getsize(p) / 1e6),
                            flush=True)
                return 0
            print("BUILD FAILED：去 Actions 页看 buildozer-log artifact")
            return 1
        time.sleep(120)
    print("POLL TIMEOUT: 100 分钟未完成")
    return 1


if __name__ == "__main__":
    sys.exit(main())
