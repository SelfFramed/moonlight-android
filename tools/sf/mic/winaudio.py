"""Windows recording devices via Core Audio COM (ctypes only, no extra packages): list them, read and set the
default recording device. The receiver makes the virtual microphone the default while the phone sends, so
applications set to "default" pick it up without any selection, and restores the previous default afterwards.
Call init() once in the thread that uses the other functions.
"""
import ctypes
from ctypes import (HRESULT, POINTER, WINFUNCTYPE, byref, c_int, c_uint, c_ulong, c_ushort, c_ubyte, c_void_p,
                    c_wchar_p)

_ole32 = ctypes.OleDLL('ole32')     # functions returning HRESULT (failures raise OSError)
_ole32v = ctypes.WinDLL('ole32')    # functions returning nothing

CLSID_MMDeviceEnumerator = '{BCDE0395-E52F-467C-8E3D-C4579291692E}'
IID_IMMDeviceEnumerator = '{A95664D2-9614-4F35-A746-DE8DB63617E6}'
CLSID_PolicyConfig = '{870af99c-171d-4f9e-af0d-e63df40c2bc9}'
IID_IPolicyConfig = '{f8679f50-850a-41cf-9c72-430f290290c8}'
PKEY_FRIENDLY_NAME = ('{a45c254e-df1c-4efd-8020-67d146a850e0}', 14)
CLSCTX_ALL = 23
CAPTURE = 1
ACTIVE = 1
ROLES = (0, 1, 2)  # console, multimedia, communications


class GUID(ctypes.Structure):
    _fields_ = [('Data1', c_ulong), ('Data2', c_ushort), ('Data3', c_ushort), ('Data4', c_ubyte * 8)]


class PROPERTYKEY(ctypes.Structure):
    _fields_ = [('fmtid', GUID), ('pid', c_ulong)]


class PROPVARIANT(ctypes.Structure):
    _fields_ = [('vt', c_ushort), ('r1', c_ushort), ('r2', c_ushort), ('r3', c_ushort),
                ('p', c_void_p), ('p2', c_void_p)]


def _guid(text):
    g = GUID()
    _ole32.CLSIDFromString(c_wchar_p(text), byref(g))
    return g


def _method(obj, index, *argtypes):
    vtable = ctypes.cast(obj, POINTER(POINTER(c_void_p)))[0]
    return WINFUNCTYPE(HRESULT, c_void_p, *argtypes)(vtable[index])


def _release(obj):
    if obj:
        vtable = ctypes.cast(obj, POINTER(POINTER(c_void_p)))[0]
        WINFUNCTYPE(c_ulong, c_void_p)(vtable[2])(obj)


def _create(clsid, iid):
    obj = c_void_p()
    _ole32.CoCreateInstance(byref(_guid(clsid)), None, CLSCTX_ALL, byref(_guid(iid)), byref(obj))
    return obj


def init():
    try:
        _ole32.CoInitializeEx(None, 0)
    except OSError:
        pass  # already initialised differently in this thread; COM still works


def _device_id(device):
    s = c_wchar_p()
    _method(device, 5, POINTER(c_wchar_p))(device, byref(s))
    value = s.value
    _ole32v.CoTaskMemFree(s)
    return value


def _friendly_name(device):
    store = c_void_p()
    _method(device, 4, c_ulong, POINTER(c_void_p))(device, 0, byref(store))
    try:
        key = PROPERTYKEY(_guid(PKEY_FRIENDLY_NAME[0]), PKEY_FRIENDLY_NAME[1])
        pv = PROPVARIANT()
        _method(store, 5, POINTER(PROPERTYKEY), POINTER(PROPVARIANT))(store, byref(key), byref(pv))
        name = ctypes.wstring_at(pv.p) if pv.vt == 31 and pv.p else ''
        _ole32.PropVariantClear(byref(pv))
        return name
    finally:
        _release(store)


def capture_devices():
    """[(id, name)] of the active recording devices."""
    enum = _create(CLSID_MMDeviceEnumerator, IID_IMMDeviceEnumerator)
    try:
        col = c_void_p()
        _method(enum, 3, c_int, c_ulong, POINTER(c_void_p))(enum, CAPTURE, ACTIVE, byref(col))
        try:
            count = c_uint()
            _method(col, 3, POINTER(c_uint))(col, byref(count))
            devices = []
            for i in range(count.value):
                dev = c_void_p()
                _method(col, 4, c_uint, POINTER(c_void_p))(col, i, byref(dev))
                try:
                    devices.append((_device_id(dev), _friendly_name(dev)))
                finally:
                    _release(dev)
            return devices
        finally:
            _release(col)
    finally:
        _release(enum)


def default_capture(role):
    """Id of the default recording device for a role, or None."""
    enum = _create(CLSID_MMDeviceEnumerator, IID_IMMDeviceEnumerator)
    try:
        dev = c_void_p()
        try:
            _method(enum, 4, c_int, c_int, POINTER(c_void_p))(enum, CAPTURE, role, byref(dev))
        except OSError:
            return None
        try:
            return _device_id(dev)
        finally:
            _release(dev)
    finally:
        _release(enum)


def set_default(device_id, roles=ROLES):
    policy = _create(CLSID_PolicyConfig, IID_IPolicyConfig)
    try:
        call = _method(policy, 13, c_wchar_p, c_int)  # IPolicyConfig::SetDefaultEndpoint
        for role in roles:
            call(policy, device_id, role)
    finally:
        _release(policy)
