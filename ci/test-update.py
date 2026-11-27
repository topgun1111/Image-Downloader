"""Exercise the updater from the previous signed release on an isolated CI emulator."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "app.imagedownloader"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=45)


def tap(label, *, resource=False, timeout=75, click=True, optional=False):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            xml = adb("exec-out", "uiautomator", "dump", "/dev/tty")
            start, end = xml.index("<?xml"), xml.index("</hierarchy>") + len("</hierarchy>")
            for node in ET.fromstring(xml[start:end]).iter("node"):
                if resource:
                    match = node.get("resource-id") == label
                else:
                    match = any(node.get(k, "").casefold() == label.casefold() for k in ("text", "content-desc", "class"))
                if match and node.get("enabled") == "true":
                    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
                    if click:
                        adb("shell", "input", "tap", str((left+right)//2), str((top+bottom)//2))
                    return True
        except (ValueError, ET.ParseError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    if optional:
        return False
    raise RuntimeError("Expected Android control did not appear: " + label)


old = os.path.join(os.environ["RUNNER_TEMP"], "previous", "Image-Downloader.apk")
adb("install", old)
adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
tap("Ayarlar")
# The first key-free release needs a one-time manual migration from the old updater.
# Future releases exercise the full anonymous in-app installation path.
if tap("android.widget.EditText", timeout=8, click=False, optional=True):
    adb("install", "-r", os.path.join(os.environ["RUNNER_TEMP"], "current.apk"))
    adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
    tap("Ayarlar")
    if tap("android.widget.EditText", timeout=5, click=False, optional=True):
        raise RuntimeError("Access-key field remains after migration")
    tap("Güncellemeleri kontrol et")
    tap("Uygulama güncel.", click=False)
    print("PASS: manual migration and anonymous update check; in-app install requires the next release")
else:
    tap("Güncellemeleri kontrol et")
    # New updaters expose the persistent background download capability.
    background = tap("Güncellemeler GitHub Releases üzerinden indirilir. İndirme arka planda devam eder.", timeout=3, click=False, optional=True)
    tap("İndir ve kur")
    if background:
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        try:
            adb("shell", "wm", "size", "1600x1200")
            adb("shell", "wm", "density", "240")
            time.sleep(5)
            adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
            # The existing task may reopen the settings activity or the gallery.
            if tap("Ayarlar", timeout=5, optional=True):
                pass
            tap("Güncellemeyi kur", timeout=180)
        finally:
            adb("shell", "wm", "size", "reset")
            adb("shell", "wm", "density", "reset")
    tap("android:id/button1", resource=True)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        info = adb("shell", "dumpsys", "package", PACKAGE)
        if "versionName=" + os.environ["VERSION"] + "\n" in info:
            print("PASS: preceding release downloaded and installed the public update through Settings")
            break
        time.sleep(1)
    else:
        raise RuntimeError("Android did not install the expected version")
