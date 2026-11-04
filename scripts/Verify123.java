import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.reandroid.apk.ApkModule;
import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verifies the 1.2.3 patched SwiftKey APKs: the merged Network connections patch
 * (telemetry + guard), the corrected allowlist, the removed header/footer texts,
 * and (for the no-internet build) the removed INTERNET permission.
 *
 * Usage: java Verify123 <apk> [defaults|no-internet]
 */
public class Verify123 {

    static int passCount = 0;
    static int failCount = 0;

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        File apk = new File(args[0]);
        boolean expectInternet = args.length < 2 || !args[1].equals("no-internet");

        // ---- manifest via ARSCLib ------------------------------------------------------
        ApkModule module = ApkModule.loadApkFile(apk);
        com.reandroid.arsc.chunk.xml.AndroidManifestBlock manifest = module.getAndroidManifestBlock();
        StringBuilder manifestText = new StringBuilder();
        walk(manifest.getDocumentElement(), manifestText);
        String mtext = manifestText.toString();
        System.out.println("[manifest read in " + (System.currentTimeMillis() - t0) + " ms]");

        check("package renamed to .morphe",
                "com.touchtype.swiftkey.morphe".equals(manifest.getPackageName()));
        check(expectInternet ? "INTERNET permission present" : "INTERNET permission removed",
                mtext.contains("name=android.permission.INTERNET;") == expectInternet);
        check("MorpheNetworkSettingsActivity registered",
                manifest.getActivity("com.morphe.swiftkey.network.MorpheNetworkSettingsActivity", false) != null);
        check("network settings intent action",
                mtext.contains("com.morphe.swiftkey.OPEN_NETWORK_CONNECTIONS"));
        check("firebase analytics disabled meta-data", mtext.contains("firebase_analytics_collection_enabled"));
        check("crashlytics disabled meta-data", mtext.contains("firebase_crashlytics_collection_enabled"));
        check("sessions disabled meta-data", mtext.contains("firebase_sessions_enabled"));

        check("AnalyticsReceiver disabled", disabled(mtext, "com.google.android.gms.analytics.AnalyticsReceiver"));
        check("AnalyticsService disabled", disabled(mtext, "com.google.android.gms.analytics.AnalyticsService"));
        check("AnalyticsJobService disabled", disabled(mtext, "com.google.android.gms.analytics.AnalyticsJobService"));
        check("Adjust lifecycle provider disabled", disabled(mtext, "com.adjust.sdk.SystemLifecycleContentProvider"));
        check("TelemetryService disabled", disabled(mtext, "com.touchtype.telemetry.TelemetryService"));
        check("TokenSharingService disabled", disabled(mtext, "com.microsoft.tokenshare.TokenSharingService"));
        check("PaperBoyAvroService disabled", disabled(mtext, "net.swiftkey.androidlibs.paperboy.PaperBoyAvroService"));
        check("SwiftKeyFirebaseMessagingService disabled", disabled(mtext, "com.touchtype.cloudmessaging.SwiftKeyFirebaseMessagingService"));
        check("FirebaseInitProvider stays enabled", staysEnabled(mtext, "com.google.firebase.provider.FirebaseInitProvider"));

        // ---- resources -------------------------------------------------------------------
        String prefs = null, home = null, arscStrings = null;
        boolean blankDrawable = false;
        try (ZipFile zip = new ZipFile(apk)) {
            byte[] prefsXml = bytes(zip, "res/xml/prefsx_morphe_network.xml");
            prefs = new String(prefsXml, java.nio.charset.StandardCharsets.ISO_8859_1);
            home = new String(bytes(zip, "res/xml/prefsx_home_screen.xml"), java.nio.charset.StandardCharsets.ISO_8859_1);
            arscStrings = strings(bytes(zip, "resources.arsc"));
            blankDrawable = zip.getEntry("res/drawable/morphe_toolbar_blank.xml") != null;
        }

        check("network screen has the 3 switches",
                contains(prefs, "morphe_network_gif") && contains(prefs, "morphe_network_translator")
                        && contains(prefs, "morphe_network_language_downloads"));
        check("header preference removed (1.2.3)", !contains(prefs, "morphe_network_header"));
        check("footer preference removed (1.2.3)", !contains(prefs, "morphe_network_footer"));
        check("home screen links the network section",
                contains(home, "OPEN_NETWORK_CONNECTIONS") || contains(home, "morphe_network"));
        check("header title string removed (1.2.3)", !contains(arscStrings, "Allowed connections"));
        check("header summary string removed (1.2.3)", !contains(arscStrings, "cut off at the root and can never connect"));
        check("footer summary string removed (1.2.3)", !contains(arscStrings, "Switches apply immediately"));
        check("switch title strings present",
                contains(arscStrings, "GIF search") && contains(arscStrings, "Language & model downloads"));
        check("language summary names jenson", contains(arscStrings, "jenson.api.swiftkey.com"));
        check("blank drawable present", blankDrawable);

        // ---- dex classes: ONE pass -------------------------------------------------------
        Map<String, ClassDef> classMap = new HashMap<>();
        List<File> dexTmp = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.getName().startsWith("classes") && entry.getName().endsWith(".dex")) {
                    File tmp = File.createTempFile("dex", ".dex");
                    tmp.deleteOnExit();
                    dexTmp.add(tmp);
                    try (java.io.InputStream in = zip.getInputStream(entry);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            }
        }
        System.out.println("[extracted " + dexTmp.size() + " dex files]");

        Set<String> guardStrings = new HashSet<>();
        int openConnectionSites = 0, loadUrlSites = 0, bindSites = 0;
        boolean adjustGone = true;
        for (File dexFile : dexTmp) {
            DexFile dex = DexFileFactory.loadDexFile(dexFile, null);
            for (ClassDef cls : dex.getClasses()) {
                classMap.put(cls.getType(), cls);
                String type = cls.getType();
                boolean isAppClass = type.startsWith("Lcom/") || type.startsWith("Lnet/swiftkey");
                for (Method m : cls.getMethods()) {
                    if (m.getImplementation() == null) continue;
                    for (Instruction i : m.getImplementation().getInstructions()) {
                        String ref = (i instanceof ReferenceInstruction)
                                ? ((ReferenceInstruction) i).getReference().toString() : null;
                        if (ref == null) continue;
                        if (type.equals("Lcom/morphe/swiftkey/network/NetworkGuard;")
                                && (i instanceof com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
                                || i instanceof com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c)) {
                            guardStrings.add(ref);
                        }
                        if (ref.contains("NetworkGuard;->checked(Ljava/net/URL;)")) openConnectionSites++;
                        if (ref.contains("NetworkGuard;->checkedUrl(Ljava/lang/String;)")) loadUrlSites++;
                        if (ref.contains("NetworkGuard;->checkedBind(Landroid/content/Intent;)")) bindSites++;
                        if (isAppClass && ref.contains("adjust/sdk/Adjust;->initSdk")) adjustGone = false;
                    }
                }
            }
        }
        System.out.println("[dex scan done in " + (System.currentTimeMillis() - t0) + " ms]");

        check("NetworkGuard class", classMap.containsKey("Lcom/morphe/swiftkey/network/NetworkGuard;"));
        check("guard allows jenson.api.swiftkey.com (1.2.3 fix)", hasString(guardStrings, "jenson.api.swiftkey.com"));
        check("guard allows cdn.swiftkey.microsoft.com", hasString(guardStrings, "cdn.swiftkey.microsoft.com"));
        check("guard allows bibo.api.swiftkey.com (1.2.3 fix)", hasString(guardStrings, "bibo.api.swiftkey.com"));
        check("guard allows tenor.com", hasString(guardStrings, "tenor.com"));
        check("guard no longer allows touchtype-fluency.com", !hasString(guardStrings, "touchtype-fluency.com"));
        check("guard no longer allows azurefd/azureedge",
                !hasString(guardStrings, "z01.azurefd.net") && !hasString(guardStrings, "azureedge.net"));
        check("guard dead-end redirect present", hasString(guardStrings, "http://127.0.0.1:9/"));

        // The OkHttp connect method starts with the guard chain.
        ClassDef realConnection = classMap.get("Lys/e;");
        boolean connectGuarded = false;
        for (Method m : realConnection.getMethods()) {
            if (!m.getName().equals("b") || m.getImplementation() == null) continue;
            if (m.getParameters().size() != 5) continue;
            List<Instruction> ins = new ArrayList<>();
            for (Instruction i : m.getImplementation().getInstructions()) ins.add(i);
            if (ins.size() < 4) continue;
            boolean fieldChain = ins.get(0).getOpcode() == Opcode.IGET_OBJECT
                    && ins.get(1).getOpcode() == Opcode.IGET_OBJECT
                    && ins.get(2).getOpcode() == Opcode.IGET_OBJECT;
            String invoke = refOf(ins.get(3));
            connectGuarded = fieldChain && invoke != null
                    && invoke.contains("NetworkGuard;->checkConnect(Ljava/lang/String;)V");
        }
        check("OkHttp connect guarded at entry", connectGuarded);

        check("TelemetryJobIntentService enqueue no-op",
                enqueueNeutralized(classMap, "Lcom/touchtype/telemetry/TelemetryJobIntentService;", "Landroidx/core/app/JobIntentService;->b("));
        check("SyncService enqueue no-op",
                enqueueNeutralized(classMap, "Lcom/touchtype/cloud/sync/SyncService;", "LNj/f;->b("));

        // First-party telemetry record dispatch neutralized.
        ClassDef dispatch = classMap.get("Lcom/touchtype/telemetry/handlers/l;");
        boolean dispatchDead = false;
        for (Method m : dispatch.getMethods()) {
            if (m.getImplementation() == null || m.getParameters().size() != 1) continue;
            if (!m.getParameters().get(0).getType().equals("Lorg/apache/avro/generic/GenericRecord;")) continue;
            for (Instruction i : m.getImplementation().getInstructions()) {
                dispatchDead = i.getOpcode() == Opcode.RETURN_VOID;
                break;
            }
        }
        check("telemetry record dispatch no-op (merged patch)", dispatchDead);

        // Both PaperBoy upload loops return kotlin.Unit immediately.
        ClassDef paperboy = classMap.get("Lqs/g;");
        int unitReturns = 0;
        for (Method m : paperboy.getMethods()) {
            if (m.getImplementation() == null) continue;
            List<Instruction> ins = new ArrayList<>();
            for (Instruction i : m.getImplementation().getInstructions()) ins.add(i);
            if (ins.size() >= 2 && ins.get(0).getOpcode() == Opcode.SGET_OBJECT
                    && ins.get(1).getOpcode() == Opcode.RETURN_OBJECT
                    && "Lsr/D;->a:Lsr/D;".equals(refOf(ins.get(0)))) {
                unitReturns++;
            }
        }
        check("both upload loops return Unit (merged patch)", unitReturns == 2);
        check("Adjust.initSdk nop'ed", adjustGone);
        check("openConnection call sites guarded (" + openConnectionSites + ")", openConnectionSites > 0);
        check("WebView.loadUrl call sites guarded (" + loadUrlSites + ")", loadUrlSites > 0);
        check("bindService call sites guarded (" + bindSites + ")", bindSites > 0);

        System.out.println();
        System.out.println("RESULT: " + passCount + " passed, " + failCount + " failed ("
                + (System.currentTimeMillis() - t0) + " ms)");
        if (failCount > 0) System.exit(1);
    }

    /** True when the class's method that references the given enqueue bridge starts with return-void. */
    static boolean enqueueNeutralized(Map<String, ClassDef> classMap, String type, String bridgeRef) {
        ClassDef cls = classMap.get(type);
        if (cls == null) return false;
        for (Method m : cls.getMethods()) {
            if (m.getImplementation() == null) continue;
            boolean referencesBridge = false;
            for (Instruction i : m.getImplementation().getInstructions()) {
                String ref = refOf(i);
                if (ref != null && ref.contains(bridgeRef)) referencesBridge = true;
            }
            if (!referencesBridge) continue;
            for (Instruction i : m.getImplementation().getInstructions()) {
                return i.getOpcode() == Opcode.RETURN_VOID;
            }
        }
        return false;
    }

    static boolean disabled(String mtext, String name) {
        int idx = mtext.indexOf("name=" + name + ";");
        if (idx < 0) return false;
        int start = mtext.lastIndexOf('<', idx);
        int end = mtext.indexOf('>', idx);
        if (start < 0 || end < 0) return false;
        return mtext.substring(start, end).contains("enabled=false");
    }

    static boolean staysEnabled(String mtext, String name) {
        return !disabled(mtext, name);
    }

    static boolean hasString(Set<String> strings, String value) {
        for (String s : strings) if (s.contains(value)) return true;
        return false;
    }

    static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.contains(needle);
    }

    static byte[] bytes(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return new byte[0];
        try (java.io.InputStream in = zip.getInputStream(entry);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    static String strings(byte[] data) {
        StringBuilder out = new StringBuilder();
        StringBuilder current = new StringBuilder();
        for (byte b : data) {
            if (b >= 32 && b < 127) {
                current.append((char) b);
            } else {
                if (current.length() >= 4) out.append(current).append('\n');
                current.setLength(0);
            }
        }
        if (current.length() >= 4) out.append(current);
        return out.toString();
    }

    static void check(String name, boolean ok) {
        if (ok) {
            passCount++;
            System.out.println("  PASS  " + name);
        } else {
            failCount++;
            System.out.println("  FAIL  " + name);
        }
    }

    static void walk(com.reandroid.arsc.chunk.xml.ResXmlElement element, StringBuilder out) {
        out.append('<');
        for (java.util.Iterator<com.reandroid.arsc.chunk.xml.ResXmlAttribute> it = element.getAttributes(); it.hasNext(); ) {
            com.reandroid.arsc.chunk.xml.ResXmlAttribute attribute = it.next();
            String value = attribute.getValueAsEncoded() != null
                    ? String.valueOf(attribute.getValueAsEncoded()) : null;
            if (value != null && !value.isEmpty()) {
                out.append(attribute.getName()).append('=').append(value).append(';');
            }
        }
        out.append('>');
        for (java.util.Iterator<com.reandroid.arsc.chunk.xml.ResXmlElement> it = element.getElements(); it.hasNext(); ) {
            walk(it.next(), out);
        }
        out.append("</>");
    }

    static String refOf(Instruction i) {
        if (i instanceof ReferenceInstruction) {
            return ((ReferenceInstruction) i).getReference().toString();
        }
        return null;
    }
}
