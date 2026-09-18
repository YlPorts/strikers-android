using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;

internal static class Program
{
    const string GameCode = "CPUE";
    const int DirBase = 0xF000;
    static byte[] fnt, fat;
    static FileStream rom;
    static string root;
    static int dirCount;

    [STAThread]
    static int Main()
    {
        try
        {
            root = AppDomain.CurrentDomain.BaseDirectory;
            string game = Path.Combine(root, "main.exe");
            if (!File.Exists(game)) return Fail("No se encontro main.exe junto al launcher.");

            string romPath = FindRom();
            if (romPath == null)
                return Fail("Pon tu ROM USA de Pokemon Platinum (.nds) en esta carpeta.\nGame code requerido: CPUE.");

            FileInfo fi = new FileInfo(romPath);
            string marker = fi.Length.ToString() + "|" + fi.LastWriteTimeUtc.Ticks.ToString();
            string markerPath = Path.Combine(root, ".pokeplatinum_rom_ready");

            if (!File.Exists(markerPath) || File.ReadAllText(markerPath).Trim() != marker)
            {
                Console.Title = "Pokemon Platinum PC - Preparando ROM";
                Console.WriteLine("Preparando ROM... Esto solo es necesario la primera vez.");
                Extract(romPath);
                File.WriteAllText(markerPath, marker);
                Console.WriteLine("ROM preparada.");
            }

            ProcessStartInfo p = new ProcessStartInfo();
            p.FileName = game;
            p.WorkingDirectory = root;
            p.UseShellExecute = false;
            Process.Start(p);
            return 0;
        }
        catch (Exception ex)
        {
            return Fail("No se pudo preparar o iniciar el juego:\n" + ex.Message);
        }
    }

    static int Fail(string s)
    {
        Console.Error.WriteLine();
        Console.Error.WriteLine(s);
        Console.Error.WriteLine();
        Console.Error.WriteLine("Presiona una tecla para cerrar...");
        try { Console.ReadKey(true); } catch { }
        return 1;
    }

    static string FindRom()
    {
        string[] files = Directory.GetFiles(root, "*.nds", SearchOption.TopDirectoryOnly);
        foreach (string f in files)
        {
            try
            {
                using (FileStream x = File.OpenRead(f))
                {
                    if (x.Length < 16) continue;
                    byte[] h = new byte[16];
                    ReadAll(x, h, 0, h.Length);
                    if (Encoding.ASCII.GetString(h, 12, 4) == GameCode) return f;
                }
            }
            catch { }
        }
        return null;
    }

    static void Extract(string path)
    {
        using (rom = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.Read))
        {
            if (rom.Length < 0x160) throw new InvalidDataException("ROM demasiado pequena.");
            byte[] h = new byte[0x160];
            ReadAll(rom, h, 0, h.Length);
            if (Encoding.ASCII.GetString(h, 12, 4) != GameCode)
                throw new InvalidDataException("La ROM no es Pokemon Platinum USA (CPUE).");

            uint fo = U32(h, 0x40), fs = U32(h, 0x44);
            uint ao = U32(h, 0x48), az = U32(h, 0x4C);
            Range(fo, fs); Range(ao, az);
            if (fs > int.MaxValue || az > int.MaxValue || (az & 7) != 0)
                throw new InvalidDataException("FNT/FAT invalida.");

            fnt = new byte[(int)fs];
            fat = new byte[(int)az];
            rom.Position = fo; ReadAll(rom, fnt, 0, fnt.Length);
            rom.Position = ao; ReadAll(rom, fat, 0, fat.Length);

            if (fnt.Length < 8) throw new InvalidDataException("FNT truncada.");
            dirCount = U16(fnt, 6);
            if (dirCount < 1 || dirCount * 8 > fnt.Length)
                throw new InvalidDataException("Directorios NitroFS invalidos.");

            Walk(DirBase, "");
            File.WriteAllBytes(Path.Combine(root, "header.bin"), h);
        }
    }

    static void Walk(int id, string relDir)
    {
        int di = id - DirBase;
        if (di < 0 || di >= dirCount) throw new InvalidDataException("ID de directorio invalido.");
        int e = checked(di * 8);
        int pos = checked((int)U32(fnt, e));
        int fileId = U16(fnt, e + 4);
        if (pos < dirCount * 8 || pos >= fnt.Length) throw new InvalidDataException("FNT invalida.");

        while (true)
        {
            if (pos >= fnt.Length) throw new EndOfStreamException("FNT truncada.");
            int t = fnt[pos++];
            if (t == 0) break;
            bool isDir = (t & 0x80) != 0;
            int n = t & 0x7F;
            if (n < 1 || pos + n > fnt.Length) throw new InvalidDataException("Nombre NitroFS invalido.");
            string name = Encoding.ASCII.GetString(fnt, pos, n);
            pos += n;
            if (name == "." || name == ".." || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
                throw new InvalidDataException("Nombre NitroFS inseguro.");

            string rel = relDir.Length == 0 ? name : Path.Combine(relDir, name);
            if (isDir)
            {
                if (pos + 2 > fnt.Length) throw new EndOfStreamException();
                int child = U16(fnt, pos); pos += 2;
                Directory.CreateDirectory(Safe(rel));
                Walk(child, rel);
            }
            else
            {
                FileOut(fileId++, rel);
            }
        }
    }

    static void FileOut(int id, string rel)
    {
        int p = checked(id * 8);
        if (p < 0 || p + 8 > fat.Length) throw new InvalidDataException("ID FAT invalido.");
        uint a = U32(fat, p), b = U32(fat, p + 4);
        if (b < a || b > rom.Length) throw new InvalidDataException("Rango FAT invalido.");

        string dst = Safe(rel);
        string parent = Path.GetDirectoryName(dst);
        if (!String.IsNullOrEmpty(parent)) Directory.CreateDirectory(parent);

        rom.Position = a;
        long left = (long)b - a;
        byte[] buf = new byte[65536];
        using (FileStream o = File.Create(dst))
        {
            while (left > 0)
            {
                int want = (int)Math.Min((long)buf.Length, left);
                int got = rom.Read(buf, 0, want);
                if (got <= 0) throw new EndOfStreamException("ROM truncada.");
                o.Write(buf, 0, got);
                left -= got;
            }
        }
    }

    static string Safe(string rel)
    {
        string r = Path.GetFullPath(root);
        if (!r.EndsWith(Path.DirectorySeparatorChar.ToString())) r += Path.DirectorySeparatorChar;
        string p = Path.GetFullPath(Path.Combine(root, rel));
        if (!p.StartsWith(r, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Ruta NitroFS insegura.");
        return p;
    }

    static void Range(uint o, uint s)
    {
        if ((ulong)o + (ulong)s > (ulong)rom.Length) throw new InvalidDataException("Tabla fuera de la ROM.");
    }

    static ushort U16(byte[] b, int o)
    {
        if (o < 0 || o + 2 > b.Length) throw new EndOfStreamException();
        return (ushort)(b[o] | (b[o + 1] << 8));
    }

    static uint U32(byte[] b, int o)
    {
        if (o < 0 || o + 4 > b.Length) throw new EndOfStreamException();
        return (uint)(b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24));
    }

    static void ReadAll(Stream s, byte[] b, int o, int n)
    {
        while (n > 0)
        {
            int k = s.Read(b, o, n);
            if (k <= 0) throw new EndOfStreamException();
            o += k; n -= k;
        }
    }
}
