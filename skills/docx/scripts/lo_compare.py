"""Write a tracked-changes .docx that shows OLD -> NEW, with LibreOffice Compare Document.

Usage: lo_compare.py OLD.docx NEW.docx OUT.docx
Run with a python3 that can import uno; `docx.clj compare` finds one.
Starts a private headless soffice with a throwaway profile, so it does not touch a running
LibreOffice session.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import time

import uno
from com.sun.star.beans import PropertyValue


def pv(name, value):
    p = PropertyValue()
    p.Name, p.Value = name, value
    return p


def url(path):
    return uno.systemPathToFileUrl(os.path.abspath(path))


def main(old, new, out):
    profile = tempfile.mkdtemp(prefix="lo-profile-")
    port = 20000 + os.getpid() % 10000
    proc = subprocess.Popen(["soffice", "--headless", "--invisible", "--norestore", "--nologo",
                             f"-env:UserInstallation=file://{profile}",
                             f"--accept=socket,host=127.0.0.1,port={port};urp;"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    desktop = None
    try:
        local = uno.getComponentContext()
        resolver = local.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", local)
        ctx, last = None, None
        for _ in range(120):
            try:
                ctx = resolver.resolve(f"uno:socket,host=127.0.0.1,port={port};urp;StarOffice.ComponentContext")
                break
            except Exception as e:  # NoConnectException until soffice listens
                last = e
                time.sleep(0.5)
        if ctx is None:
            sys.exit(f"soffice did not accept a UNO connection: {last!r}")
        smgr = ctx.ServiceManager
        desktop = smgr.createInstanceWithContext("com.sun.star.frame.Desktop", ctx)
        dispatcher = smgr.createInstanceWithContext("com.sun.star.frame.DispatchHelper", ctx)
        doc = desktop.loadComponentFromURL(url(new), "_blank", 0, (pv("Hidden", True),))
        frame = doc.getCurrentController().getFrame()
        dispatcher.executeDispatch(frame, ".uno:CompareDocuments", "", 0, (pv("URL", url(old)),))
        doc.storeToURL(url(out), (pv("FilterName", "MS Word 2007 XML"),))
        doc.close(True)
    finally:
        try:
            if desktop is not None:
                desktop.terminate()
        except Exception:
            pass
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            proc.kill()
        shutil.rmtree(profile, ignore_errors=True)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__.strip())
    main(*sys.argv[1:])
