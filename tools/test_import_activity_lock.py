#!/usr/bin/env python3
"""Check that long-running imports do not hold SDL's Activity monitor.

Run after assembleDebug. SDL's surface callback synchronizes on its Activity;
the import workers must serialize on a separate object or surface restoration can freeze.
"""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
classes = root / 'android/botwrecompandroid/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
text = subprocess.run(['javap', '-p', '-c', '-classpath', str(classes),
                       'com.botwrecompandroid.BOTWRecompAndroidActivity'],
                      check=True, text=True, capture_output=True).stdout
for name in ('importSwitchArchive', 'importSwitchDlcArchive', 'importDlc', 'importGame'):
    match = re.search(r'(?m)^  private (?:synchronized )?void ' + name + r'\([^\n]*\);\n(.*?)(?=\n  (?:private|public|protected) |\n})', text, re.S)
    if not match:
        raise SystemExit(f'{name}: method is missing')
    block = match.group(0)
    if 'private synchronized void' in block or 'Field IMPORT_LOCK:Ljava/lang/Object;' not in block or 'monitorenter' not in block:
        raise SystemExit(f'{name}: import must lock IMPORT_LOCK, not the Activity')
    if f'Method {name}Locked:' not in block:
        raise SystemExit(f'{name}: locked implementation is not called')
print('Import workers serialize on a dedicated monitor, leaving the SDL Activity monitor free')
