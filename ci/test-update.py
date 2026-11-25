"""Exercise the updater from the previous signed release on an isolated CI emulator."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "app.imagedownloader"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=45)


def tap(label, *, resource=False, timeout=75):
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
                    adb("shell", "input", "tap", str((left+right)//2), str((top+bottom)//2))
                    return
        except (ValueError, ET.ParseError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise RuntimeError("Expected Android control did not appear: " + label)


old = os.path.join(os.environ["RUNNER_TEMP"], "previous", "Image-Downloader.apk")
adb("install", old)
adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
tap("Ayarlar")
tap("android.widget.EditText")
# Input is sent only to the masked field of our own app on this disposable emulator.
result = subprocess.run(["adb", "shell", "input", "text", os.environ["REPO_READ_TOKEN"]],
                        capture_output=True, timeout=30)
if result.returncode:
    raise RuntimeError("Could not enter the test repository credential")
adb("shell", "input", "keyevent", "4")
tap("Güncellemeleri kontrol et")
tap("İndir ve kur")
tap("android:id/button1", resource=True)
deadline = time.monotonic() + 90
while time.monotonic() < deadline:
    info = adb("shell", "dumpsys", "package", PACKAGE)
    if "versionName=" + os.environ["VERSION"] + "\n" in info:
        print("PASS: prior release found, downloaded and installed the private release through Settings")
        break
    time.sleep(1)
else:
    raise RuntimeError("Android did not install the expected version")
