# Lists the formats on the Windows clipboard: id, registered name and, for the three clipboard-history flags, their DWORD.
Add-Type -TypeDefinition @"
using System; using System.Text; using System.Runtime.InteropServices;
public class ClipFormats {
  [DllImport("user32.dll")] static extern bool OpenClipboard(IntPtr h);
  [DllImport("user32.dll")] static extern bool CloseClipboard();
  [DllImport("user32.dll")] static extern uint EnumClipboardFormats(uint f);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClipboardFormatName(uint f, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern IntPtr GetClipboardData(uint f);
  [DllImport("kernel32.dll")] static extern IntPtr GlobalLock(IntPtr h);
  [DllImport("kernel32.dll")] static extern bool GlobalUnlock(IntPtr h);
  public static string Dump() {
    var sb = new StringBuilder();
    for (int attempt = 0; attempt < 20 && !OpenClipboard(IntPtr.Zero); attempt++) System.Threading.Thread.Sleep(100);
    uint f = 0;
    while ((f = EnumClipboardFormats(f)) != 0) {
      var n = new StringBuilder(256); GetClipboardFormatName(f, n, 256);
      string name = n.ToString(), val = "";
      if (name.StartsWith("Exclude") || name.StartsWith("Can")) {
        IntPtr h = GetClipboardData(f);
        if (h != IntPtr.Zero) { IntPtr p = GlobalLock(h); if (p != IntPtr.Zero) { val = " = " + Marshal.ReadInt32(p); GlobalUnlock(h); } }
      }
      sb.AppendLine(f + " " + name + val);
    }
    CloseClipboard();
    return sb.ToString();
  }
}
"@
[ClipFormats]::Dump()
