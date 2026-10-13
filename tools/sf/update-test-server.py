"""Local stand-in for GitHub's releases/latest, to test the app's built-in updater in the emulator.

Writes latest.json (same fields the app reads from GitHub: tag_name, draft, prerelease, assets with
name, size, digest, browser_download_url) next to the APK and serves the folder over HTTP/1.1 with
keep-alive. Python's default HTTP/1.0 server broke large downloads through the emulator's NAT.
Port 8765 is taken by another service on Martin's PC, hence 47911.

Usage:
  python -I tools/sf/update-test-server.py <newer-release.apk> [--port 47911]
The app under test must be a release build with a lower versionCode, built with
  -Psf.updateApi=http://10.0.2.2:<port>/latest.json
Full procedure: docs/selfframed/RELEASE.md, "Update-Funktion lokal testen".
"""
import functools
import hashlib
import http.server
import json
import os
import re
import sys


def main(argv):
    port = 47911
    if '--port' in argv:
        port = int(argv[argv.index('--port') + 1])
    apk = os.path.abspath(argv[1])
    name = os.path.basename(apk)
    match = re.match(r'moonlight-eink-(.+)-nonRoot-release\.apk$', name)
    if not match:
        sys.exit('expected a file named moonlight-eink-<version>-nonRoot-release.apk')
    version = match.group(1)
    digest = hashlib.sha256(open(apk, 'rb').read()).hexdigest()
    folder = os.path.dirname(apk)
    base = 'http://10.0.2.2:%d/' % port
    release = {
        'tag_name': 'v' + version,
        'draft': False,
        'prerelease': False,
        'name': 'Moonlight E-Ink v%s (local test)' % version,
        'body': 'Local updater test.',
        'html_url': base,
        'assets': [{
            'name': name,
            'size': os.path.getsize(apk),
            'digest': 'sha256:' + digest,
            'browser_download_url': base + name,
        }],
    }
    with open(os.path.join(folder, 'latest.json'), 'w', encoding='utf-8') as f:
        json.dump(release, f, indent=1)

    class Handler(http.server.SimpleHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

    server = http.server.ThreadingHTTPServer(('127.0.0.1', port), functools.partial(Handler, directory=folder))
    print('serving %s on 127.0.0.1:%d (emulator: %slatest.json), Ctrl+C to stop' % (folder, port, base), flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main(sys.argv)
