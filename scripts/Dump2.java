import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class Dump2 {
    public static void main(String[] args) throws Exception {
        String target = args[1];
        File apk = new File(args[0]);
        try (ZipFile zip = new ZipFile(apk)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().endsWith(".dex")) continue;
                File tmp = File.createTempFile("dex", ".dex"); tmp.deleteOnExit();
                try (InputStream in = zip.getInputStream(entry); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                for (ClassDef cls : DexFileFactory.loadDexFile(tmp, null).getClasses()) {
                    if (!cls.getType().equals(target)) continue;
                    for (Method m : cls.getMethods()) {
                        if (m.getImplementation() == null) continue;
                        if (args.length > 2 && !m.getName().equals(args[2])) continue;
                        System.out.println("METHOD: " + m.getName());
                        for (Instruction i : m.getImplementation().getInstructions()) {
                            String ref = (i instanceof ReferenceInstruction) ? ((ReferenceInstruction) i).getReference().toString() : "";
                            System.out.println("  " + i.getOpcode() + "  " + ref);
                        }
                    }
                }
            }
        }
    }
}
