import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validates an .mpp bundle the way the Android Morphe app loads it: read the
 * classes.dex entries of the bundle zip and check every expected patch class
 * is defined in dex form.
 */
public class VerifyMppDex {
    public static void main(String[] args) throws Exception {
        File mpp = new File(args[0]);
        String[] expected = {
            "Lhooman/morphe/patches/swiftkey/SwiftKeyCompatibilityKt;",
            "Lhooman/morphe/patches/swiftkey/network/NetworkConnectionsPatchKt;",
            "Lhooman/morphe/patches/swiftkey/network/OkHttpConnectFingerprint;",
            "Lhooman/morphe/patches/swiftkey/privacy/DisableTelemetryPatchKt;",
            "Lhooman/morphe/patches/swiftkey/debloat/DebloatPatchKt;",
            "Lhooman/morphe/patches/swiftkey/login/DisableCloudSignInPromptPatchKt;",
            "Lhooman/morphe/patches/swiftkey/misc/BlankElementsPatchKt;",
            "Lhooman/morphe/patches/swiftkey/misc/ChangePackageNamePatchKt;",
        };
        int dexCount = 0;
        java.util.Set<String> classes = new java.util.HashSet<>();
        try (ZipFile zip = new ZipFile(mpp)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().endsWith(".dex")) continue;
                dexCount++;
                File tmp = File.createTempFile("mpp", ".dex");
                tmp.deleteOnExit();
                try (java.io.InputStream in = zip.getInputStream(entry);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                DexFile dex = DexFileFactory.loadDexFile(tmp, null);
                for (ClassDef cls : dex.getClasses()) classes.add(cls.getType());
            }
        }
        System.out.println("dex entries: " + dexCount);
        System.out.println("dex classes: " + classes.size());
        int missing = 0;
        for (String exp : expected) {
            boolean ok = classes.contains(exp);
            if (!ok) missing++;
            System.out.println((ok ? "  PASS  " : "  FAIL  ") + exp);
        }
        System.out.println("RESULT: " + (expected.length - missing) + " passed, " + missing + " failed");
        if (missing > 0 || dexCount == 0) System.exit(1);
    }
}
