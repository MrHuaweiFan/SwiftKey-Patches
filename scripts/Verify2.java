import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.reandroid.apk.ApkModule;
import java.io.File;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 1.2.2-specific verification of the patched SwiftKey APK: the FirebaseInitProvider
 * regression fix, the job service neutralization, and dex hygiene (no duplicate
 * classes after the multidex rewrite).
 */
public class Verify2 {

    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (ok) passed++; else failed++;
    }

    static String refOf(Instruction i) {
        return (i instanceof ReferenceInstruction) ? ((ReferenceInstruction) i).getReference().toString() : null;
    }

    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);

        // ---- manifest via ARSCLib -----------------------------------------------------
        ApkModule module = ApkModule.loadApkFile(apk);
        com.reandroid.arsc.chunk.xml.AndroidManifestBlock manifest = module.getAndroidManifestBlock();

        String provider = componentState(manifest, "provider", "com.google.firebase.provider.FirebaseInitProvider");
        check("FirebaseInitProvider still declared", provider != null);
        check("FirebaseInitProvider NOT disabled (1.2.2 crash-loop fix)",
                provider != null && !provider.contains("enabled=\"false\""));

        String jobService = componentState(manifest, "service", "com.touchtype.telemetry.TelemetryJobIntentService");
        check("TelemetryJobIntentService still declared", jobService != null);
        check("TelemetryJobIntentService NOT disabled (JobScheduler.enqueue must not throw)",
                jobService != null && !jobService.contains("enabled=\"false\""));

        check("SwiftKeyFirebaseMessagingService disabled",
                isDisabled(manifest, "service", "com.touchtype.cloudmessaging.SwiftKeyFirebaseMessagingService"));
        check("TelemetryService disabled",
                isDisabled(manifest, "service", "com.touchtype.telemetry.TelemetryService"));
        check("TokenSharingService disabled",
                isDisabled(manifest, "service", "com.microsoft.tokenshare.TokenSharingService"));
        check("FirebaseInstanceIdReceiver disabled",
                isDisabled(manifest, "receiver", "com.google.firebase.iid.FirebaseInstanceIdReceiver"));

        // ---- dex files -----------------------------------------------------------------
        Map<String, DexFile> dexByClass = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().startsWith("classes") || !entry.getName().endsWith(".dex")) continue;
                File tmp = File.createTempFile("dex", ".dex");
                tmp.deleteOnExit();
                try (java.io.InputStream in = zip.getInputStream(entry);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                DexFile dex = DexFileFactory.loadDexFile(tmp, null);
                for (ClassDef cls : dex.getClasses()) {
                    String type = cls.getType();
                    if (dexByClass.put(type, dex) != null) duplicates.add(type);
                }
            }
        }
        check("no duplicate classes across dex files (was the dual-statics risk)",
                duplicates.isEmpty());

        // The telemetry job service enqueue entry must start with return-void.
        boolean telemetryNoOp = false, syncNoOp = false;
        ClassDef telemetryService = classOf(dexByClass, "Lcom/touchtype/telemetry/TelemetryJobIntentService;");
        if (telemetryService != null) {
            for (Method m : telemetryService.getMethods()) {
                if (m.getImplementation() == null || (m.getAccessFlags() & 0x8) == 0) continue; // static only
                Instruction first = null;
                boolean callsBridge = false;
                for (Instruction i : m.getImplementation().getInstructions()) {
                    if (first == null) first = i;
                    String r = refOf(i);
                    if (r != null && r.startsWith("Landroidx/core/app/JobIntentService;->")) callsBridge = true;
                }
                if (callsBridge && first != null && first.getOpcode() == Opcode.RETURN_VOID) telemetryNoOp = true;
            }
        }
        check("TelemetryJobIntentService enqueue no-op'd (return-void)", telemetryNoOp);

        ClassDef syncService = classOf(dexByClass, "Lcom/touchtype/cloud/sync/SyncService;");
        if (syncService != null) {
            for (Method m : syncService.getMethods()) {
                if (m.getImplementation() == null || (m.getAccessFlags() & 0x8) == 0) continue;
                Instruction first = null;
                boolean callsBridge = false;
                for (Instruction i : m.getImplementation().getInstructions()) {
                    if (first == null) first = i;
                    String r = refOf(i);
                    if (r != null && r.startsWith("LNj/f;->")) callsBridge = true;
                }
                if (callsBridge && first != null && first.getOpcode() == Opcode.RETURN_VOID) syncNoOp = true;
            }
        }
        check("SyncService enqueue no-op'd (return-void)", syncNoOp);

        // The OkHttp connect method must call NetworkGuard.checkConnect before anything else.
        boolean okHttpGuarded = false;
        ClassDef okHttpConnection = classOf(dexByClass, "Lys/e;");
        if (okHttpConnection != null) {
            for (Method m : okHttpConnection.getMethods()) {
                if (m.getImplementation() == null) continue;
                if (m.getParameterTypes().size() != 5
                        || !m.getParameterTypes().get(0).toString().equals("I")) continue;
                int idx = 0;
                for (Instruction i : m.getImplementation().getInstructions()) {
                    if (idx >= 4) break;
                    String r = refOf(i);
                    if (r != null && r.contains("NetworkGuard;->checkConnect")) okHttpGuarded = true;
                    idx++;
                }
            }
        }
        check("OkHttp RealConnection guarded by NetworkGuard.checkConnect", okHttpGuarded);

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static ClassDef classOf(Map<String, DexFile> dexByClass, String type) {
        for (DexFile dex : new HashSet<>(dexByClass.values())) {
            for (ClassDef cls : dex.getClasses()) {
                if (cls.getType().equals(type)) return cls;
            }
        }
        return null;
    }

    static boolean isDisabled(com.reandroid.arsc.chunk.xml.AndroidManifestBlock manifest,
            String tag, String name) {
        String state = componentState(manifest, tag, name);
        return state != null && state.contains("enabled=BOOLEAN:false");
    }

    /** Returns the typed attribute text of the component element, or null when absent. */
    static String componentState(com.reandroid.arsc.chunk.xml.AndroidManifestBlock manifest,
            String tag, String name) {
        try {
            return findComponent(manifest.getDocumentElement(), tag, name);
        } catch (Throwable ignored) {
        }
        return null;
    }

    static String findComponent(com.reandroid.arsc.chunk.xml.ResXmlElement element, String tag, String name) {
        if (element != null && element.equalsName(tag)) {
            StringBuilder attrs = new StringBuilder();
            boolean matches = false;
            java.util.Iterator<? extends com.reandroid.arsc.chunk.xml.ResXmlAttribute> it =
                    element.getAttributes();
            while (it != null && it.hasNext()) {
                com.reandroid.arsc.chunk.xml.ResXmlAttribute attr = it.next();
                String n = attr.getName().replace("android:", "");
                String value;
                try {
                    value = n.equals("enabled") || n.equals("exported")
                            ? String.valueOf(attr.getValueType()) + ":" + attr.getValueAsBoolean()
                            : String.valueOf(attr.getValueAsString());
                } catch (Throwable t) {
                    value = String.valueOf(attr.getValueAsString());
                }
                attrs.append(n).append("=").append(value).append(' ');
                if (name.equals(value)) matches = true;
            }
            if (matches) return attrs.toString();
        }
        for (com.reandroid.arsc.chunk.xml.ResXmlElement child : iterElements(element)) {
            String found = findComponent(child, tag, name);
            if (found != null) return found;
        }
        return null;
    }

    static Iterable<com.reandroid.arsc.chunk.xml.ResXmlElement> iterElements(
            com.reandroid.arsc.chunk.xml.ResXmlElement element) {
        if (element == null) return java.util.Collections.emptyList();
        java.util.List<com.reandroid.arsc.chunk.xml.ResXmlElement> out = new java.util.ArrayList<>();
        try {
            for (java.util.Iterator<com.reandroid.arsc.chunk.xml.ResXmlElement> it = element.getElements();
                    it.hasNext(); ) {
                com.reandroid.arsc.chunk.xml.ResXmlElement child = it.next();
                if (child != null) out.add(child);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
