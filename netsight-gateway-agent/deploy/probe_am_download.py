# -*- coding: utf-8 -*-
"""探测 Alertmanager 下载源可用性（本地网络）"""
import urllib.request

urls = [
    ("github 直连 v0.28.1", "https://github.com/prometheus/alertmanager/releases/download/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz"),
    ("清华 tag 路径 v0.28.1", "https://mirrors.tuna.tsinghua.edu.cn/github-release/prometheus/alertmanager/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz"),
    ("清华 LATEST-RELEASE 目录", "https://mirrors.tuna.tsinghua.edu.cn/github-release/prometheus/alertmanager/LATEST-RELEASE/"),
    ("USTC LATEST-RELEASE 目录", "https://mirrors.ustc.edu.cn/github-release/prometheus/alertmanager/LATEST-RELEASE/"),
    ("USTC v0.28.1 文件", "https://mirrors.ustc.edu.cn/github-release/prometheus/alertmanager/v0.28.1/alertmanager-0.28.1.linux-amd64.tar.gz"),
]

for name, url in urls:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        resp = urllib.request.urlopen(req, timeout=20)
        data = resp.read(200)
        print("%s => HTTP %s 首包 %d 字节" % (name, resp.status, len(data)))
    except Exception as e:
        print("%s => FAIL: %s" % (name, e))
