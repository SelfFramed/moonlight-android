# Passive probe of the default playback device: format, device period and how the WASAPI loopback
# (the path Sunshine captures from) delivers audio over a few seconds. Read-only, plays nothing.
# Usage: powershell -ExecutionPolicy Bypass -File tools\sf\diag\audio-loopback-probe.ps1 [-Seconds 6]
# Measured 12.10.2026 on Martin's PC: UR44, 48 kHz, 10 ms packets every 10 ms (max 10.7 ms).
param([int]$Seconds = 6)

$code = @"
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

namespace SfProbe {
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom {}

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator {
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice {
        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        int OpenPropertyStore(int access, out IPropertyStore props);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
    }

    [Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPropertyStore {
        int GetCount(out int count);
        int GetAt(int index, out PropertyKey key);
        int GetValue(ref PropertyKey key, out PropVariant value);
    }

    [StructLayout(LayoutKind.Sequential)] struct PropertyKey { public Guid fmtid; public int pid; }
    [StructLayout(LayoutKind.Explicit)] struct PropVariant {
        [FieldOffset(0)] public short vt;
        [FieldOffset(8)] public IntPtr ptr;
    }

    [Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient {
        int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
        int GetBufferSize(out uint frames);
        int GetStreamLatency(out long latency);
        int GetCurrentPadding(out uint padding);
        int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        int GetMixFormat(out IntPtr format);
        int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        int Start();
        int Stop();
        int Reset();
        int SetEventHandle(IntPtr handle);
        int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient {
        int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        int ReleaseBuffer(uint frames);
        int GetNextPacketSize(out uint frames);
    }

    public static class Probe {
        [DllImport("kernel32.dll")] static extern IntPtr CreateEvent(IntPtr attrs, bool manualReset, bool initialState, string name);
        [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle, uint ms);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
        [DllImport("ole32.dll")] static extern int PropVariantClear(ref PropVariant pv);

        static double Pct(List<double> v, double p) {
            if (v.Count == 0) return double.NaN;
            var s = new List<double>(v); s.Sort();
            return s[Math.Min(s.Count - 1, (int)Math.Round(p * (s.Count - 1)))];
        }

        public static string Run(int seconds) {
            var sb = new System.Text.StringBuilder();
            var en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
            IMMDevice dev;
            Marshal.ThrowExceptionForHR(en.GetDefaultAudioEndpoint(0, 0, out dev));
            string id; dev.GetId(out id);
            IPropertyStore ps; dev.OpenPropertyStore(0, out ps);
            var key = new PropertyKey { fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), pid = 14 };
            PropVariant pv; ps.GetValue(ref key, out pv);
            string name = pv.vt == 31 ? Marshal.PtrToStringUni(pv.ptr) : "?";
            PropVariantClear(ref pv);
            sb.AppendLine("default playback device: " + name);

            Guid iidClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
            object o; Marshal.ThrowExceptionForHR(dev.Activate(ref iidClient, 23, IntPtr.Zero, out o));
            var client = (IAudioClient)o;
            IntPtr fmt; Marshal.ThrowExceptionForHR(client.GetMixFormat(out fmt));
            int rate = Marshal.ReadInt32(fmt, 4);
            int ch = Marshal.ReadInt16(fmt, 2);
            int bits = Marshal.ReadInt16(fmt, 14);
            int blockAlign = Marshal.ReadInt16(fmt, 12);
            long defP, minP; client.GetDevicePeriod(out defP, out minP);
            sb.AppendLine(string.Format("mix format: {0} Hz, {1} ch, {2} bit; device period default {3:0.00} ms, minimum {4:0.00} ms",
                rate, ch, bits, defP / 10000.0, minP / 10000.0));

            // Shared-mode loopback, event driven, like Sunshine (AUDCLNT_STREAMFLAGS_LOOPBACK | EVENTCALLBACK)
            const int LOOPBACK = 0x00020000, EVENTCALLBACK = 0x00040000;
            Marshal.ThrowExceptionForHR(client.Initialize(0, LOOPBACK | EVENTCALLBACK, 200000, 0, fmt, IntPtr.Zero));
            uint bufFrames; client.GetBufferSize(out bufFrames);
            IntPtr ev = CreateEvent(IntPtr.Zero, false, false, null);
            Marshal.ThrowExceptionForHR(client.SetEventHandle(ev));
            Guid iidCap = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
            object co; Marshal.ThrowExceptionForHR(client.GetService(ref iidCap, out co));
            var cap = (IAudioCaptureClient)co;

            var sizes = new List<double>();
            var gaps = new List<double>();
            int silent = 0, discont = 0, timeouts = 0;
            var sw = Stopwatch.StartNew();
            double last = -1;
            client.Start();
            while (sw.Elapsed.TotalSeconds < seconds) {
                if (WaitForSingleObject(ev, 200) != 0) { timeouts++; continue; }
                uint next;
                while (cap.GetNextPacketSize(out next) == 0 && next > 0) {
                    IntPtr data; uint frames, flags; ulong dp, qp;
                    if (cap.GetBuffer(out data, out frames, out flags, out dp, out qp) != 0) break;
                    double now = sw.Elapsed.TotalMilliseconds;
                    if (last >= 0) gaps.Add(now - last);
                    last = now;
                    sizes.Add(frames * 1000.0 / rate);
                    if ((flags & 2) != 0) silent++;
                    if ((flags & 1) != 0) discont++;
                    cap.ReleaseBuffer(frames);
                }
            }
            client.Stop();
            CloseHandle(ev);
            Marshal.FreeCoTaskMem(fmt);

            sb.AppendLine(string.Format("loopback buffer {0:0.0} ms; {1} packets in {2} s ({3} flagged silent, {4} discontinuities, {5} waits without data)",
                bufFrames * 1000.0 / rate, sizes.Count, seconds, silent, discont, timeouts));
            if (sizes.Count > 0) {
                sb.AppendLine(string.Format("packet length ms: min {0:0.00} / median {1:0.00} / max {2:0.00}",
                    Pct(sizes, 0), Pct(sizes, 0.5), Pct(sizes, 1)));
            }
            if (gaps.Count > 0) {
                sb.AppendLine(string.Format("time between packets ms: median {0:0.0} / p95 {1:0.0} / p99 {2:0.0} / max {3:0.0}",
                    Pct(gaps, 0.5), Pct(gaps, 0.95), Pct(gaps, 0.99), Pct(gaps, 1)));
            }
            return sb.ToString();
        }
    }
}
"@

[System.Threading.Thread]::CurrentThread.Priority = 'BelowNormal'
(Get-Process -Id $PID).PriorityClass = 'BelowNormal'
Add-Type -TypeDefinition $code -Language CSharp
[SfProbe.Probe]::Run($Seconds)
