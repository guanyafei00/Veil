#!/usr/bin/env python3
"""Push a Veil APK onto a TCL TV through TCL's own 多屏互动 (com.tcl.MultiScreenInteraction_TV).

TCL's firmware refuses every ADB install and every in-app update of a HOME-declaring APK unless the
TV's *current* default home is already Veil (see app/src/main/AndroidManifest.xml: the
`com.tcl.app.type=com.tcl.ui` meta-data lets the launcherStrategy gate pass during a self-update).
多屏互动 runs as the system user (uid 1000) and installs APKs that TCL's phone app pushes to it,
bypassing the ADB/pm block. This script speaks that phone protocol: it asks the TV to download the
APK from `url` and install it. The TV shows one prompt (同意 / 拒绝); after 同意 it installs silently.

    tools/veil-tcast-push.py <tv-ip> <url> [package] [label] [versionCode] [timeout-seconds]

Defaults: package=com.veil.launcher, label=Veil, versionCode=0, timeout=180.

Use cases
---------
- Update an already-installed Veil (Veil is the default home): push the standard APK, one 同意 on
  the TV, no adb, no root. This is the cleanest "automatic bypass" for existing users.
- First-time install on a TCL TV (the stock launcher is still the default home): the TV refuses any
  APK that declares a home screen (stage 4 below), even through 多屏互动. Push the *guard* build
  instead (a Veil APK that does NOT declare MAIN+HOME) — see the guard-flavor plan. This script works
  for either; just point `url` at the right APK.

The protocol, read from 多屏互动 on TCL MT5879 (see Aerial's docs/devices/tcl-mt5879-cn.md, MIT,
port of that project's tools/tcast-push.py):
- TCP port 6553; every message is a 4-byte big-endian length followed by UTF-8 text, fields by ">>".
- "159>>1>>0" opens the session without encryption.
- "150>>" is the heartbeat; the TV answers "150>>YES" and drops a session that goes quiet.
- "223>>INSTALL>>package>>label>>url>>2>>versionCode>>sizeMB>>appMd5>>signatureMd5>>icon>>apkPathMd5":
  source 2 with an apkPathMd5 marks a phone-local APK, which skips the store lookup and asks the
  viewer. The TV answers "223>>INSTALL>>package>>label>>confirm_pending|denied|installing|installed
  >>apkPathMd5", then "223>>PROGRESS>>package>>label>>stage>>percent>>apkPathMd5":
  stage 1 downloading, 3 installing, 5 installed, 2 failed, 4 refused by the TV's installer after a
  complete download (TCL's verifier on an APK that declares HOME and is not the current home).

Exits 0 once the TV reports installed, 1 if refused / failed / timed out.
"""
import hashlib
import socket
import struct
import sys
import time

PORT = 6553
PKG = "com.veil.launcher"
LABEL = "Veil"


def send(sock, text):
    data = text.encode("utf-8")
    sock.sendall(struct.pack(">i", len(data)) + data)


def messages(buf):
    """Yield complete messages already in buf and return the remainder."""
    out = []
    while len(buf) >= 4:
        n = struct.unpack(">i", buf[:4])[0]
        if n < 0:
            raise ValueError("negative T-Cast message length: {}".format(n))
        if len(buf) < 4 + n:
            break
        out.append(buf[4:4 + n].decode("utf-8", "replace"))
        buf = buf[4 + n:]
    return out, buf


def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    tv, url = sys.argv[1], sys.argv[2]
    package = sys.argv[3] if len(sys.argv) > 3 else PKG
    label = sys.argv[4] if len(sys.argv) > 4 else LABEL
    version = sys.argv[5] if len(sys.argv) > 5 else "0"
    timeout = float(sys.argv[6]) if len(sys.argv) > 6 else 180

    sock = socket.create_connection((tv, PORT), timeout=5)
    sock.settimeout(1)
    send(sock, "159>>1>>0")
    path_md5 = hashlib.md5(url.encode()).hexdigest()
    send(sock, ">>".join(
        ["223", "INSTALL", package, label, url, "2", version, "20", "", "", "", path_md5]))

    buf = b""
    last_beat = time.monotonic()
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buf += chunk
        except socket.timeout:
            pass
        try:
            found, buf = messages(buf)
        except ValueError as exc:
            sock.close()
            print("Invalid response from the TV: {}.".format(exc), file=sys.stderr)
            return 1
        for msg in found:
            parts = msg.split(">>")
            if len(parts) < 5 or parts[0] != "223" or parts[2] != package:
                continue
            if parts[1] == "INSTALL":
                if parts[4] == "confirm_pending":
                    print("The TV is asking: choose 同意 (Agree) on the TV.", flush=True)
                elif parts[4] == "denied":
                    print("Refused on the TV.", file=sys.stderr)
                    return 1
            elif parts[1] == "PROGRESS":
                stage = parts[4]
                if stage == "5":
                    print("Installed.", flush=True)
                    return 0
                if stage == "2":
                    print("The TV could not download or install the APK.", file=sys.stderr)
                    return 1
                if stage == "4":
                    print("The TV downloaded the APK but refused to install it. TCL refuses any APK that "
                          "declares a home screen unless it is already the default home. Push the guard "
                          "(no-HOME) build, or install the standard APK through the TV's file manager.",
                          file=sys.stderr)
                    return 1
        # The TV drops a quiet session, and with it the pending install.
        if time.monotonic() - last_beat > 5:
            send(sock, "150>>")
            last_beat = time.monotonic()
    sock.close()
    print("Timed out waiting for the TV.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
