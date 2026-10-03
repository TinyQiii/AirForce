# -*- coding: utf-8 -*-
"""
把刚编译好的 APK 发布到局域网更新目录（update_site/）。

版本号直接从 AirForce/app/build.gradle.kts 里读，
所以只要改了 versionCode 再编译，这里就不用再手动改任何东西。

用法：python 发布更新.py "更新说明，可省略"
"""

import json
import os
import re
import shutil
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
GRADLE = os.path.join(BASE, "AirForce", "app", "build.gradle.kts")
APK_RELEASE = os.path.join(BASE, "AirForce", "app", "build", "outputs", "apk", "release", "app-release.apk")
APK_DEBUG = os.path.join(BASE, "AirForce", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
SITE = os.path.join(BASE, "update_site")
MANIFEST = os.path.join(SITE, "version.json")


def pick_apk():
    """优先用正式签名的 release 包，没有就退回 debug 包。"""
    for p in (APK_RELEASE, APK_DEBUG):
        if os.path.exists(p):
            return p
    return None


def main() -> int:
    apk = pick_apk()
    if apk is None:
        print("找不到 APK，请先编译：")
        print("  " + APK_RELEASE)
        return 1

    src = open(GRADLE, encoding="utf-8").read()
    m_code = re.search(r"versionCode\s*=\s*(\d+)", src)
    m_name = re.search(r'versionName\s*=\s*"([^"]+)"', src)
    if not m_code or not m_name:
        print("无法从 build.gradle.kts 读到版本号")
        return 1
    code, name = int(m_code.group(1)), m_name.group(1)

    notes = sys.argv[1] if len(sys.argv) > 1 else ""
    if not notes and os.path.exists(MANIFEST):
        try:
            notes = json.load(open(MANIFEST, encoding="utf-8")).get("notes", "")
        except (OSError, ValueError):
            notes = ""

    os.makedirs(SITE, exist_ok=True)
    shutil.copyfile(apk, os.path.join(SITE, "app.apk"))
    json.dump(
        {"versionCode": code, "versionName": name, "apkUrl": "app.apk", "notes": notes},
        open(MANIFEST, "w", encoding="utf-8"),
        ensure_ascii=False,
        indent=2,
    )
    print("已发布：v%s (build %d)" % (name, code))
    print("  来源   -> " + apk)
    print("  APK    -> " + os.path.join(SITE, "app.apk"))
    print("  清单   -> " + MANIFEST)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
