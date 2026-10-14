"""Pairing question on this PC's screen, started by receiver.py for every new phone.

A big black-on-white window (readable through the stream on the e-ink panel), centred on the monitor with the
mouse cursor (during a stream: the streamed one) and kept on top every second. A standard Windows message box is
not enough: Windows silently drops "always on top" for windows of a background process, so the question ended up
behind the open windows (seen 14.10.2026).

Exit code: 0 = allowed (click on "Zulassen" only), 1 = rejected (button or closing the window), 2 = no answer in time.
Usage: pythonw confirm_dialog.py --name "moto g(100)" --addr 192.168.178.96 --code "815 968" [--timeout 110] [--test]
"""
import argparse
import ctypes
import sys
import tkinter as tk
from ctypes import wintypes


class MONITORINFO(ctypes.Structure):
    _fields_ = [('cbSize', wintypes.DWORD), ('rcMonitor', wintypes.RECT), ('rcWork', wintypes.RECT),
                ('dwFlags', wintypes.DWORD)]


def cursor_work_area():
    """(x, y, width, height) of the work area of the monitor with the mouse cursor."""
    user32 = ctypes.windll.user32
    pt = wintypes.POINT()
    user32.GetCursorPos(ctypes.byref(pt))
    user32.MonitorFromPoint.restype = wintypes.HANDLE
    user32.MonitorFromPoint.argtypes = [wintypes.POINT, wintypes.DWORD]
    monitor = user32.MonitorFromPoint(pt, 2)  # MONITOR_DEFAULTTONEAREST
    info = MONITORINFO()
    info.cbSize = ctypes.sizeof(MONITORINFO)
    user32.GetMonitorInfoW.argtypes = [wintypes.HANDLE, ctypes.POINTER(MONITORINFO)]
    user32.GetMonitorInfoW(monitor, ctypes.byref(info))
    r = info.rcWork
    return r.left, r.top, r.right - r.left, r.bottom - r.top


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument('--name', default='Telefon')
    parser.add_argument('--addr', default='')
    parser.add_argument('--code', required=True)
    parser.add_argument('--timeout', type=float, default=110)
    parser.add_argument('--test', action='store_true', help='mark the window as a test')
    args = parser.parse_args(argv)
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)  # sharp text on scaled displays (e-ink: no blur)
    except (AttributeError, OSError):
        pass

    result = {'code': 2}
    root = tk.Tk()
    root.title('SelfFramed: Mikrofon koppeln')
    root.configure(bg='white', highlightthickness=4, highlightbackground='black', highlightcolor='black')
    root.resizable(False, False)

    def finish(code):
        result['code'] = code
        root.destroy()

    def label(text, size, bold=False, pady=(0, 8)):
        tk.Label(root, text=text, font=('Segoe UI', size, 'bold' if bold else 'normal'), bg='white', fg='black',
                 wraplength=640, justify='center').pack(padx=32, pady=pady)

    if args.test:
        label('TEST · schließt sich gleich', 12, True, (16, 0))
    label('Mikrofon zum PC', 22, True, (24, 8))
    who = '„%s“' % args.name + (' (%s)' % args.addr if args.addr else '')
    label('%s möchte dieses PC-Mikrofon nutzen.' % who, 15)
    label(args.code, 40, True, (8, 8))
    label('Derselbe Code steht oben im Stream.\nNur zulassen, wenn beide Codes gleich sind.', 13, False, (0, 16))
    buttons = tk.Frame(root, bg='white')
    buttons.pack(pady=(4, 28))
    for text, code, border in (('Zulassen', 0, 4), ('Ablehnen', 1, 2)):
        tk.Button(buttons, text=text, font=('Segoe UI', 17, 'bold' if code == 0 else 'normal'), width=10,
                  bg='white', fg='black', activebackground='black', activeforeground='white', relief='solid',
                  bd=border, command=lambda c=code: finish(c)).pack(side='left', padx=14)
    root.protocol('WM_DELETE_WINDOW', lambda: finish(1))
    # No keyboard shortcut for "Zulassen" and no grabbing of the keyboard focus: someone typing in another window
    # must not allow a pairing by accident. Only a deliberate click counts.

    def place():
        # Centre on the monitor with the cursor; done again once the window is shown, because Windows may put a
        # new window at its default spot first
        root.update_idletasks()
        x, y, w, h = cursor_work_area()
        root.geometry('+%d+%d' % (x + max(0, (w - root.winfo_reqwidth()) // 2),
                                  y + max(0, (h - root.winfo_reqheight()) // 2)))

    place()
    root.after(100, place)

    def keep_on_top():
        root.attributes('-topmost', True)
        root.lift()
        root.after(1000, keep_on_top)

    keep_on_top()
    root.after(int(args.timeout * 1000), lambda: finish(2))
    root.mainloop()
    return result['code']


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
