import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.reandroid.apk.ApkModule;
import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verifies the 1.1.0 patched APKs.
 *
 * Usage: java Verify110 <apk> <swiftkey-defaults|swiftkey-all|factory-defaults|factory-all>
 */
public class Verify110 {

    static int passCount = 0;
    static int failCount = 0;
    static Set<String> dexClassesGlobal = new HashSet<>();

    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        String mode = args[1];
        File decoded = new File(args[2]);

        ApkModule module = ApkModule.loadApkFile(apk);
        com.reandroid.arsc.chunk.xml.AndroidManifestBlock manifest = module.getAndroidManifestBlock();
        StringBuilder manifestText = new StringBuilder();
        walk(manifest.getDocumentElement(), manifestText);
        String mtext = manifestText.toString();

        // Loaded dex classes of the patched APK.
        dexClassesGlobal = new HashSet<>();
        java.util.Map<String, ClassDef> classDefs = new java.util.HashMap<>();
        try (ZipFile zip = new ZipFile(apk)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().endsWith(".dex")) continue;
                File tmp = File.createTempFile("dex", ".dex");
                tmp.deleteOnExit();
                try (java.io.InputStream in = zip.getInputStream(entry);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                for (ClassDef cls : DexFileFactory.loadDexFile(tmp, null).getClasses()) {
                    dexClassesGlobal.add(cls.getType());
                    classDefs.put(cls.getType(), cls);
                }
            }
        }
        check("dex classes loaded", dexClassesGlobal.size() > 10);

        if (mode.startsWith("swiftkey")) {
            verifySwiftKey(apk, manifest, mtext, classDefs, mode, decoded);
        } else {
            verifyFactory(apk, manifest, mtext, classDefs, mode, decoded);
        }

        System.out.println("RESULT: " + passCount + " passed, " + failCount + " failed");
        if (failCount > 0) System.exit(1);
    }

    static void verifySwiftKey(File apk, com.reandroid.arsc.chunk.xml.AndroidManifestBlock manifest,
                                String mtext, java.util.Map<String, ClassDef> classDefs, String mode, File decoded) throws Exception {
        check("extension classes merged",
                dexClassesGlobal.contains("Lcom/morphe/swiftkey/toolbar/MorpheToolbar;") &&
                dexClassesGlobal.contains("Lcom/morphe/swiftkey/toolbar/BlankToolbarItem;"));
        check("network extension classes absent",
                !dexClassesGlobal.contains("Lcom/morphe/swiftkey/network/NetworkGuard;"));
        // ---- Change package name -------------------------------------------------------------
        check("package renamed to .morphe", "com.touchtype.swiftkey.morphe".equals(manifest.getPackageName()));
        check("DYNAMIC_RECEIVER permission renamed", mtext.contains("com.touchtype.swiftkey.morphe.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"));
        check("fileprovider authority renamed", mtext.contains("com.touchtype.swiftkey.morphe.fileprovider"));

        // ---- Blank elements -------------------------------------------------------------------
        check("blank count resource written", readFile(decoded, "res/values/integers.xml").contains("morphe_blank_count"));
        check("blank count is six", readFile(decoded, "res/values/integers.xml").matches("(?s).*name=\"morphe_blank_count\">6<.*"));
        check("blank drawable written", zipHas(apk, "res/drawable/morphe_toolbar_blank.xml"));
        Method clinit = methodOf(classDefs, "Lio/e;", "<clinit>");
        check("io/e <clinit> found", clinit != null);
        if (clinit != null) {
            String body = instructionsAsString(clinit);
            check("catalog routed through withBlanks", body.contains("Lcom/morphe/swiftkey/toolbar/MorpheToolbar;->withBlanks(Ljava/util/List;)Ljava/util/List;"));
            check("catalog routed through withoutBloat", body.contains("Lcom/morphe/swiftkey/toolbar/MorpheToolbar;->withoutBloat(Ljava/util/List;)Ljava/util/List;"));
            if (mode.equals("swiftkey-all")) {
                check("catalog routed through withoutGifTranslator",
                        body.contains("Lcom/morphe/swiftkey/toolbar/MorpheToolbar;->withoutGifTranslator(Ljava/util/List;)Ljava/util/List;"));
            } else {
                check("withoutGifTranslator NOT applied by default",
                        !body.contains("Lcom/morphe/swiftkey/toolbar/MorpheToolbar;->withoutGifTranslator(Ljava/util/List;)Ljava/util/List;"));
            }
        }

        // ---- Debloat ---------------------------------------------------------------------------
        check("CloudSetupActivity removed from manifest", !mtext.contains("com.touchtype.cloud.uiv2.CloudSetupActivity"));
        String homeScreen = readFile(decoded, "res/xml/prefsx_home_screen.xml");
        check("account settings entry removed", !homeScreen.contains("pref_home_launch_cloud_account_prefs"));
        check("rewards entry removed", !homeScreen.contains("pref_home_launch_rewards_prefs"));
        String richInput = readFile(decoded, "res/xml/prefsx_rich_input_screen.xml");
        check("bing search settings removed", !richInput.contains("pref_bing_suggestions_bar_enabled_key"));

        // ---- Smart clip (the round search button left of the copied-text pill) -----------------
        Method chipU = methodOf(classDefs, "LYm/c;", "u");
        Method chipS = methodOf(classDefs, "LYm/c;", "s");
        check("smart clip chip factory u found", chipU != null);
        check("smart clip chip factory s found", chipS != null);
        if (chipU != null) {
            String body = instructionsAsString(chipU);
            check("chip factory u neutralized", body.startsWith("CONST_4") && body.contains("RETURN_OBJECT"));
        }
        if (chipS != null) {
            String body = instructionsAsString(chipS);
            check("chip factory s neutralized", body.startsWith("CONST_4") && body.contains("RETURN_OBJECT"));
        }

        // ---- Disable telemetry ------------------------------------------------------------------
        check("crashlytics disabled meta-data", mtext.contains("firebase_crashlytics_collection_enabled"));
        check("sessions disabled meta-data", mtext.contains("firebase_sessions_enabled"));
        check("AnalyticsReceiver disabled", disabled(mtext, "com.google.android.gms.analytics.AnalyticsReceiver"));
        check("AnalyticsService disabled", disabled(mtext, "com.google.android.gms.analytics.AnalyticsService"));
        check("AnalyticsJobService disabled", disabled(mtext, "com.google.android.gms.analytics.AnalyticsJobService"));
        check("Adjust lifecycle provider disabled", disabled(mtext, "com.adjust.sdk.SystemLifecycleContentProvider"));
        check("exception reporter disabled by resource flag",
                readFile(decoded, "res/values/bools.xml").matches("(?s).*name=\"exceptions_report_enabled\">false<.*"));
        check("cloud sign-in prompt disabled by resource flag",
                readFile(decoded, "res/values/bools.xml").matches("(?s).*name=\"onboarding_cloud_sign_in_enabled\">false<.*"));
        check("no network connections settings screen", !mtext.contains("com.morphe.swiftkey.network.MorpheNetworkSettingsActivity"));
        check("no network settings home entry", !mtext.contains("morphe_network_prefs"));

        Method dispatch = methodOf(classDefs, "Lcom/touchtype/telemetry/handlers/l;", "send");
        check("telemetry record dispatch found", dispatch != null);
        if (dispatch != null) {
            String body = instructionsAsString(dispatch);
            check("telemetry dispatch neutralized (return-void first)", body.startsWith("RETURN_VOID"));
        }
        Method primaryUpload = methodOf(classDefs, "Lqs/g;", "a");
        check("primary telemetry upload found", primaryUpload != null);
        if (primaryUpload != null) {
            String body = instructionsAsString(primaryUpload);
            check("primary upload returns Unit immediately",
                    body.startsWith("SGET_OBJECT") && body.contains("Lsr/D;->a:Lsr/D;") && body.contains("RETURN_OBJECT"));
        }
        Method secondaryUpload = methodOf(classDefs, "Lqs/g;", "b");
        check("secondary telemetry upload found", secondaryUpload != null);
        if (secondaryUpload != null) {
            String body = instructionsAsString(secondaryUpload);
            check("secondary upload returns Unit immediately",
                    body.startsWith("SGET_OBJECT") && body.contains("Lsr/D;->a:Lsr/D;") && body.contains("RETURN_OBJECT"));
        }
        Method adjustInit = methodOf(classDefs, "LKk/g;", "a");
        check("Adjust initializer found", adjustInit != null);
        if (adjustInit != null) {
            String body = instructionsAsString(adjustInit);
            check("Adjust.initSdk call removed", !body.contains("Lcom/adjust/sdk/Adjust;->initSdk"));
        }

        // ---- GIF source ---------------------------------------------------------------------------
        Method tenorFactory = methodOf(classDefs, "LHo/G;", "a");
        check("Tenor service factory found", tenorFactory != null);
        if (tenorFactory != null) {
            String body = instructionsAsString(tenorFactory);
            check("Tenor factory replaced with KLIPY",
                    body.contains("Lcom/morphe/swiftkey/klipy/MorpheKlipyService;-><init>()V"));
        }
        check("klipy api key string resource present",
                readFile(decoded, "res/values/strings.xml").contains("morphe_klipy_api_key"));
    }

    static void verifyFactory(File apk, com.reandroid.arsc.chunk.xml.AndroidManifestBlock manifest,
                              String mtext, java.util.Map<String, ClassDef> classDefs, String mode, File decoded) throws Exception {
        boolean renamed = mode.equals("factory-all");
        if (renamed) {
            check("package renamed to .morphe", "com.swiftkey.swiftkeyconfigurator.morphe".equals(manifest.getPackageName()));
            check("READCONFIG permission definition renamed",
                    mtext.contains("com.swiftkey.swiftkeyconfigurator.morphe.READCONFIG"));
            check("provider authority renamed", mtext.contains("authorities=com.swiftkey.swiftkeyconfigurator.morphe;"));
            check("provider permission reference renamed",
                    mtext.contains("permission=com.swiftkey.swiftkeyconfigurator.morphe.READCONFIG;"));
        } else {
            check("package unchanged", "com.swiftkey.swiftkeyconfigurator".equals(manifest.getPackageName()));
            check("READCONFIG permission unchanged", mtext.contains("com.swiftkey.swiftkeyconfigurator.READCONFIG"));
            check("provider authority unchanged", mtext.contains("authorities=com.swiftkey.swiftkeyconfigurator;"));
            check("provider still exported", mtext.contains("exported=true;"));
            check("provider still gated by READCONFIG", mtext.contains("permission=com.swiftkey.swiftkeyconfigurator.READCONFIG;"));
        }
        // The ids.xml scaffold is a build-time file: after the rebuild its (empty) contents are
        // compiled into resources.arsc, so it does not exist as a zip entry. The successful
        // rebuild itself is the check.

        // Any method holding the UriMatcher authority strings works; check the class' strings.
        ClassDef e = classDefs.get("Lcom/swiftkey/swiftkeyconfigurator/e;");
        check("query dispatcher class present", e != null);
        if (e != null) {
            java.util.List<String> constStrings = new ArrayList<>();
            for (Method m : e.getMethods()) {
                if (m.getImplementation() == null) continue;
                for (Instruction i : m.getImplementation().getInstructions()) {
                    if (i instanceof ReferenceInstruction) {
                        Object ref = ((ReferenceInstruction) i).getReference();
                        if (ref instanceof com.android.tools.smali.dexlib2.iface.reference.StringReference) {
                            constStrings.add(((com.android.tools.smali.dexlib2.iface.reference.StringReference) ref).getString());
                        }
                    }
                }
            }
            long oldCount = constStrings.stream().filter("com.swiftkey.swiftkeyconfigurator"::equals).count();
            long newCount = constStrings.stream().filter("com.swiftkey.swiftkeyconfigurator.morphe"::equals).count();
            if (renamed) {
                check("UriMatcher authority entries rewritten in code (11x .morphe, 0x old)",
                        newCount >= 11 && oldCount == 0);
            } else {
                check("UriMatcher authority entries untouched", oldCount >= 11 && newCount == 0);
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------
    static Method methodOf(java.util.Map<String, ClassDef> classDefs, String type, String name) {
        ClassDef cls = classDefs.get(type);
        if (cls == null) return null;
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getImplementation() != null) return m;
        }
        return null;
    }

    static String instructionsAsString(Method method) {
        StringBuilder sb = new StringBuilder();
        for (Instruction i : method.getImplementation().getInstructions()) {
            sb.append(i.getOpcode().name().toUpperCase());
            if (i instanceof ReferenceInstruction) {
                sb.append(' ').append(((ReferenceInstruction) i).getReference());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static boolean disabled(String mtext, String name) {
        int idx = mtext.indexOf("name=" + name + ";");
        while (idx >= 0) {
            int start = mtext.lastIndexOf('<', idx);
            int end = mtext.indexOf('>', idx);
            if (start < 0 || end < 0) return false;
            if (mtext.substring(start, end).contains("enabled=false")) return true;
            idx = mtext.indexOf("name=" + name + ";", end);
        }
        return false;
    }

    static String readFile(File decoded, String path) throws Exception {
        File file = new File(decoded, path);
        if (!file.exists()) throw new IllegalStateException("missing " + path);
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
    }

    static String readResource(File apk, String path) throws Exception {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) throw new IllegalStateException("missing " + path);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    static boolean zipHas(File apk, String path) throws Exception {
        try (ZipFile zip = new ZipFile(apk)) {
            return zip.getEntry(path) != null;
        }
    }

    static void check(String name, boolean ok) {
        if (ok) passCount++; else failCount++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
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
}
