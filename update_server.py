# -*- coding: utf-8 -*-
"""
飞机大战 · 局域网更新服务

把 update_site/ 目录通过 HTTP 暴露给同一 Wi-Fi 下的手机，
游戏启动时会来这里检查有没有新版本。

用法：双击项目根目录的「启动更新服务.bat」，或直接运行本脚本。
保持窗口开着即可；关掉窗口 = 停止服务（游戏只是查不到更新，不会有任何报错）。
"""

import http.server
import os
import socket
import socketserver

PORT = 8765
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "update_site")


def lan_ip() -> str:
    """拿到本机在局域网的 IP（不会真的发包）"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def end_headers(self):
        # 更新清单必须每次取最新的，不能走缓存
        self.send_header("Cache-Control", "no-store, must-revalidate")
        super().end_headers()

    def log_message(self, fmt, *args):
        print("  " + (fmt % args))


def main() -> None:
    ip = lan_ip()
    print("=" * 56)
    print("  飞机大战 · 局域网更新服务")
    print("  更新目录 : " + ROOT)
    print("  手机访问 : http://%s:%d/version.json" % (ip, PORT))
    print("")
    print("  保持本窗口开着，游戏启动时会自动检查更新。")
    print("  按 Ctrl+C 停止。")
    print("=" * 56)
    print("")

    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer(("0.0.0.0", PORT), Handler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n已停止。")


if __name__ == "__main__":
    main()
