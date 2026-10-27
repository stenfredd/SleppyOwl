#!/usr/bin/env python3
"""A stand-in org.freedesktop.ScreenSaver for the test stand's D-Bus (tools/linux/stand.sh): prints every Inhibit and
UnInhibit, so the app's screensaver inhibition (L9) can be checked without the user's desktop.

  stand.sh bus python3 tools/linux/fake_screensaver.py > log &
"""
from gi.repository import Gio, GLib

XML = """<node><interface name="org.freedesktop.ScreenSaver">
<method name="Inhibit"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="u" direction="out"/></method>
<method name="UnInhibit"><arg type="u" direction="in"/></method>
</interface></node>"""
cookies = iter(range(1, 1 << 31))


def call(conn, sender, path, iface, method, args, invocation):
    if method == "Inhibit":
        cookie = next(cookies)
        print(f"Inhibit {args.unpack()} from {sender} -> {cookie}", flush=True)
        invocation.return_value(GLib.Variant("(u)", (cookie,)))
    else:
        print(f"UnInhibit {args.unpack()[0]} from {sender}", flush=True)
        invocation.return_value(None)


def acquired(conn, name):
    conn.register_object("/org/freedesktop/ScreenSaver", Gio.DBusNodeInfo.new_for_xml(XML).interfaces[0], call, None, None)
    # The real service drops an inhibition when its owner leaves the bus; show that moment.
    conn.signal_subscribe("org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged", None, None, 0,
                          lambda c, s, p, i, sig, a: a.unpack()[2] == "" and a.unpack()[0].startswith(":") and print(f"gone {a.unpack()[0]}", flush=True))
    print("ready", flush=True)


Gio.bus_own_name(Gio.BusType.SESSION, "org.freedesktop.ScreenSaver", 0, acquired, None, None)
GLib.MainLoop().run()
