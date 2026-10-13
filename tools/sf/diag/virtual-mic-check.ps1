# Checks a virtual microphone device pair (default: Steam Streaming Microphone) for use as a cable:
# lists playback and recording endpoints with their state (1 active, 2 disabled, 4 not present), then, if
# both sides are active, plays a quiet 1 kHz tone (about -30 dBFS, 1.5 s) into the playback side and
# measures it on the recording side. Nothing is audible: the playback side is a virtual device.
# Usage: powershell -ExecutionPolicy Bypass -File tools\sf\diag\virtual-mic-check.ps1 [-Match <name part>]
# 13.10.2026 on Martin's PC: 'Mikrofon (Steam Streaming Microphone)' active, its playback side
# 'Lautsprecher (Steam Streaming Microphone)' disabled (Sound settings, show disabled devices).
param([string]$Match = 'Steam Streaming Microphone', [double]$Seconds = 1.5)

$code = @"
using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Diagnostics;

namespace SfMic {
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom {}

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator {
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
    }
    [Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceCollection {
        int GetCount(out uint count);
        int Item(uint index, out IMMDevice device);
    }
    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice {
        int Activate(ref Guid iid, int clsCtx, IntPtr p, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        int OpenPropertyStore(int access, out IPropertyStore props);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetState(out int state);
    }
    [Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPropertyStore {
        int GetCount(out int c);
        int GetAt(int i, out PropertyKey k);
        int GetValue(ref PropertyKey k, out PropVariant v);
    }
    [StructLayout(LayoutKind.Sequential)] struct PropertyKey { public Guid fmtid; public int pid; }
    [StructLayout(LayoutKind.Explicit)] struct PropVariant { [FieldOffset(0)] public short vt; [FieldOffset(8)] public IntPtr ptr; }

    [Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient {
        int Initialize(int shareMode, int flags, long bufferDuration, long periodicity, IntPtr format, IntPtr session);
        int GetBufferSize(out uint frames);
        int GetStreamLatency(out long latency);
        int GetCurrentPadding(out uint padding);
        int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        int GetMixFormat(out IntPtr format);
        int GetDevicePeriod(out long def, out long min);
        int Start();
        int Stop();
        int Reset();
        int SetEventHandle(IntPtr h);
        int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }
    [Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioRenderClient {
        int GetBuffer(uint frames, out IntPtr data);
        int ReleaseBuffer(uint frames, uint flags);
    }
    [Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient {
        int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong pos, out ulong qpc);
        int ReleaseBuffer(uint frames);
        int GetNextPacketSize(out uint frames);
    }

    public static class Test {
        [DllImport("ole32.dll")] static extern int PropVariantClear(ref PropVariant pv);
        static readonly Guid IID_AudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");

        static string Name(IMMDevice d) {
            IPropertyStore ps; d.OpenPropertyStore(0, out ps);
            var key = new PropertyKey { fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), pid = 14 };
            PropVariant pv; ps.GetValue(ref key, out pv);
            string s = pv.vt == 31 ? Marshal.PtrToStringUni(pv.ptr) : "?";
            PropVariantClear(ref pv);
            return s;
        }

        static IMMDevice Find(int flow, string match, out string name) {
            var en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
            IMMDeviceCollection col; Marshal.ThrowExceptionForHR(en.EnumAudioEndpoints(flow, 1, out col));
            uint n; col.GetCount(out n);
            for (uint i = 0; i < n; i++) {
                IMMDevice d; col.Item(i, out d);
                string s = Name(d);
                if (s.IndexOf(match, StringComparison.OrdinalIgnoreCase) >= 0) { name = s; return d; }
            }
            name = null; return null;
        }

        // 0 = float32, 1 = int16
        static int Kind(IntPtr fmt, out int channels, out int rate, out int blockAlign) {
            channels = Marshal.ReadInt16(fmt, 2); rate = Marshal.ReadInt32(fmt, 4); blockAlign = Marshal.ReadInt16(fmt, 12);
            int tag = Marshal.ReadInt16(fmt, 0) & 0xFFFF; int bits = Marshal.ReadInt16(fmt, 14);
            if (tag == 3) return 0;
            if (tag == 0xFFFE) {
                byte[] g = new byte[16]; Marshal.Copy(fmt + 24, g, 0, 16);
                return new Guid(g) == new Guid("00000003-0000-0010-8000-00aa00389b71") ? 0 : 1;
            }
            return bits == 32 ? 0 : 1;
        }

        public static string List(int flow) {
            var en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
            IMMDeviceCollection col; Marshal.ThrowExceptionForHR(en.EnumAudioEndpoints(flow, 0xF, out col));
            uint n; col.GetCount(out n);
            var sb = new System.Text.StringBuilder();
            for (uint i = 0; i < n; i++) {
                IMMDevice d; col.Item(i, out d); int st; d.GetState(out st);
                string nm; try { nm = Name(d); } catch { nm = "?"; }
                if (nm.Contains("Steam") || st == 1) sb.AppendLine("  state=" + st + "  " + nm);
            }
            return sb.ToString();
        }

        public static string Run(string match, double seconds) {
            string rName, cName;
            IMMDevice rDev = Find(0, match, out rName), cDev = Find(1, match, out cName);
            if (rDev == null || cDev == null) return "loopback test skipped: playback side active=" + (rName != null) + ", recording side active=" + (cName != null) + " (enable disabled devices in the Sound settings)";
            Guid iid = IID_AudioClient; object o;
            Marshal.ThrowExceptionForHR(cDev.Activate(ref iid, 23, IntPtr.Zero, out o)); var cap = (IAudioClient)o;
            Marshal.ThrowExceptionForHR(rDev.Activate(ref iid, 23, IntPtr.Zero, out o)); var ren = (IAudioClient)o;
            IntPtr cFmt, rFmt; cap.GetMixFormat(out cFmt); ren.GetMixFormat(out rFmt);
            int cCh, cRate, cAlign, rCh, rRate, rAlign;
            int cKind = Kind(cFmt, out cCh, out cRate, out cAlign), rKind = Kind(rFmt, out rCh, out rRate, out rAlign);
            Marshal.ThrowExceptionForHR(cap.Initialize(0, 0, 2000000, 0, cFmt, IntPtr.Zero));
            Marshal.ThrowExceptionForHR(ren.Initialize(0, 0, 2000000, 0, rFmt, IntPtr.Zero));
            Guid iidC = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), iidR = new Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2");
            cap.GetService(ref iidC, out o); var cc = (IAudioCaptureClient)o;
            ren.GetService(ref iidR, out o); var rc = (IAudioRenderClient)o;
            uint rBuf; ren.GetBufferSize(out rBuf);

            double amp = 0.03; // about -30 dBFS
            long written = 0, total = (long)(seconds * rRate);
            double peakBefore = 0, peakDuring = 0; long capturedFrames = 0;
            var sw = Stopwatch.StartNew();
            cap.Start();
            bool rendering = false;
            while (sw.Elapsed.TotalSeconds < seconds + 1.0) {
                // 0.3 s silence baseline, then the tone
                if (!rendering && sw.Elapsed.TotalSeconds > 0.3) { ren.Start(); rendering = true; }
                if (rendering && written < total) {
                    uint pad; ren.GetCurrentPadding(out pad);
                    uint free = rBuf - pad;
                    if (free > 0) {
                        uint n = (uint)Math.Min(free, total - written);
                        IntPtr data; rc.GetBuffer(n, out data);
                        for (uint f = 0; f < n; f++) {
                            double v = amp * Math.Sin(2 * Math.PI * 1000 * (written + f) / rRate);
                            for (int ch = 0; ch < rCh; ch++) {
                                int off = (int)f * rAlign + ch * (rAlign / rCh);
                                if (rKind == 0) { byte[] b = BitConverter.GetBytes((float)v); Marshal.Copy(b, 0, data + off, 4); }
                                else Marshal.WriteInt16(data + off, (short)(v * 32767));
                            }
                        }
                        rc.ReleaseBuffer(n, 0); written += n;
                    }
                }
                uint next;
                while (cc.GetNextPacketSize(out next) == 0 && next > 0) {
                    IntPtr data; uint frames, flags; ulong p1, p2;
                    if (cc.GetBuffer(out data, out frames, out flags, out p1, out p2) != 0) break;
                    double peak = 0;
                    if ((flags & 2) == 0) {
                        for (uint f = 0; f < frames; f++) {
                            for (int ch = 0; ch < cCh; ch++) {
                                int off = (int)f * cAlign + ch * (cAlign / cCh);
                                double v = cKind == 0 ? BitConverter.ToSingle(BitConverter.GetBytes(Marshal.ReadInt32(data + off)), 0)
                                                      : Marshal.ReadInt16(data + off) / 32768.0;
                                peak = Math.Max(peak, Math.Abs(v));
                            }
                        }
                    }
                    if (rendering) peakDuring = Math.Max(peakDuring, peak); else peakBefore = Math.Max(peakBefore, peak);
                    capturedFrames += frames;
                    cc.ReleaseBuffer(frames);
                }
                Thread.Sleep(5);
            }
            ren.Stop(); cap.Stop();
            Marshal.FreeCoTaskMem(cFmt); Marshal.FreeCoTaskMem(rFmt);
            return string.Format("render: {0} ({1} Hz, {2} ch, {3})\ncapture: {4} ({5} Hz, {6} ch, {7})\ncaptured {8} frames; peak before tone {9:0.0000}, during tone {10:0.0000} (tone peak 0.0300)",
                rName, rRate, rCh, rKind == 0 ? "float" : "int16", cName, cRate, cCh, cKind == 0 ? "float" : "int16",
                capturedFrames, peakBefore, peakDuring);
        }
    }
}
"@
(Get-Process -Id $PID).PriorityClass = 'BelowNormal'
Add-Type -TypeDefinition $code -Language CSharp
'playback endpoints:'; [SfMic.Test]::List(0); 'recording endpoints:'; [SfMic.Test]::List(1)
[SfMic.Test]::Run($Match, $Seconds)
