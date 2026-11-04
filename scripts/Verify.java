import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.reandroid.apk.ApkModule;
import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verifies the patched SwiftKey APKs with the same stack that built them. */
public class Verify {

    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        boolean expectBlanks = args.length > 1 && args[1].equals("all");

        // ---- zip entries ------------------------------------------------------------
        try (ZipFile zip = new ZipFile(apk)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    System.out.println("[zip] " + name + " (" + entry.getSize() + " bytes)");
                }
            }
            check("morphe_toolbar_blank drawable",
                    !expectBlanks || zip.getEntry("res/drawable/morphe_toolbar_blank.xml") != null);
            check("no blank drawable without the patch",
                    expectBlanks || zip.getEntry("res/drawable/morphe_toolbar_blank.xml") == null);
        }

        // ---- manifest via ARSCLib ----------------------------------------------------
        ApkModule module = ApkModule.loadApkFile(apk);
        com.reandroid.arsc.chunk.xml.AndroidManifestBlock manifest = module.getAndroidManifestBlock();
        StringBuilder manifestText = new StringBuilder();
        walk(manifest.getDocumentElement(), manifestText);

        check("package renamed to .morphe",
                "com.touchtype.swiftkey.morphe".equals(manifest.getPackageName()));
        check("CloudSetupActivity deleted",
                manifest.getActivity("com.touchtype.cloud.uiv2.CloudSetupActivity", false) == null);
        check("MorpheNetworkSettingsActivity registered",
                manifest.getActivity("com.morphe.swiftkey.network.MorpheNetworkSettingsActivity", false) != null);
        check("network settings intent action",
                manifestText.indexOf("com.morphe.swiftkey.OPEN_NETWORK_CONNECTIONS") >= 0);
        check("firebase analytics disabled meta-data",
                manifestText.indexOf("firebase_analytics_collection_enabled") >= 0);
        check("crashlytics disabled meta-data",
                manifestText.indexOf("firebase_crashlytics_collection_enabled") >= 0);

        // ---- dex classes --------------------------------------------------------------
        java.util.Map<String, DexFile> dexByClass = new java.util.HashMap<>();
        java.util.List<DexFile> dexes = new java.util.ArrayList<>();
        try (ZipFile zip = new ZipFile(apk)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.getName().startsWith("classes") && entry.getName().endsWith(".dex")) {
                    File tmp = File.createTempFile("dex", ".dex");
                    tmp.deleteOnExit();
                    try (java.io.InputStream in = zip.getInputStream(entry);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    DexFile dex = DexFileFactory.loadDexFile(tmp, null);
                    dexes.add(dex);
                    for (ClassDef cls : dex.getClasses()) dexByClass.put(cls.getType(), dex);
                }
            }
        }

        check("NetworkGuard class", dexByClass.containsKey("Lcom/morphe/swiftkey/network/NetworkGuard;"));
        check("MorpheNetworkSettingsActivity class",
                dexByClass.containsKey("Lcom/morphe/swiftkey/network/MorpheNetworkSettingsActivity;"));
        check("MorpheNetworkFragment class",
                dexByClass.containsKey("Lcom/morphe/swiftkey/network/MorpheNetworkFragment;"));
        check("MorpheToolbar class", dexByClass.containsKey("Lcom/morphe/swiftkey/toolbar/MorpheToolbar;"));
        check("BlankToolbarItem class",
                dexByClass.containsKey("Lcom/morphe/swiftkey/toolbar/BlankToolbarItem;"));

        ClassDef activity = classOf(dexByClass, "Lcom/morphe/swiftkey/network/MorpheNetworkSettingsActivity;");
        boolean hasPageName = false;
        for (Method m : activity.getMethods()) {
            if (m.getName().equals("d") && m.toString().contains("PageName")) hasPageName = true;
        }
        check("activity implements d() page name override", hasPageName);

        boolean inflatesPrefsActivity = false;
        for (Method m : activity.getMethods()) {
            if (!m.getName().equals("onCreate") || m.getImplementation() == null) continue;
            for (Instruction i : m.getImplementation().getInstructions()) {
                String ref = refOf(i);
                if (ref != null && ref.endsWith(";->t(I)V")) {
                    inflatesPrefsActivity = true;
                }
            }
        }
        check("activity inflates prefs_activity via t()", inflatesPrefsActivity);

        ClassDef guard = classOf(dexByClass, "Lcom/morphe/swiftkey/network/NetworkGuard;");
        boolean deadEndPresent = false;
        for (Method m : guard.getMethods()) {
            if (m.getImplementation() == null) continue;
            for (Instruction i : m.getImplementation().getInstructions()) {
                if (i instanceof com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
                        || i instanceof com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c) {
                    String ref = refOf(i);
                    if (ref != null && ref.contains("http://127.0.0.1:9/")) deadEndPresent = true;
                }
            }
        }
        check("NetworkGuard redirects to dead-end (no throw)", deadEndPresent);

        ClassDef catalog = classOf(dexByClass, "Lio/e;");
        boolean withoutBloat = false, withBlanks = false;
        for (Method m : catalog.getMethods()) {
            if (!m.getName().equals("<clinit>") || m.getImplementation() == null) continue;
            for (Instruction i : m.getImplementation().getInstructions()) {
                String ref = refOf(i);
                if (ref == null) continue;
                if (ref.contains("MorpheToolbar;->withoutBloat")) withoutBloat = true;
                if (ref.contains("MorpheToolbar;->withBlanks")) withBlanks = true;
            }
        }
        check("catalog lists filtered (withoutBloat)", withoutBloat);
        check("blank ids appended to catalog", !expectBlanks || withBlanks);
        check("no blank ids without the patch", expectBlanks || !withBlanks);

        ClassDef factory = classOf(dexByClass, "LXn/e;");
        boolean factoryHook = false;
        for (Method m : factory.getMethods()) {
            if (m.getImplementation() == null) continue;
            for (Instruction i : m.getImplementation().getInstructions()) {
                String ref = refOf(i);
                if (ref != null && ref.contains("MorpheToolbar;->blankItem")) factoryHook = true;
            }
        }
        check("item factory hook present", !expectBlanks || factoryHook);
        check("no factory hook without the patch", expectBlanks || !factoryHook);

        ClassDef stateBuilder = classOf(dexByClass, "Lio/v;");
        int accountAddCalls = 0, accountInsertCalls = 0;
        for (Method m : stateBuilder.getMethods()) {
            if (m.getImplementation() == null) continue;
            for (Instruction i : m.getImplementation().getInstructions()) {
                String ref = refOf(i);
                if (ref == null) continue;
                if (ref.equals("Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z")) accountAddCalls++;
                if (ref.equals("Ljava/util/ArrayList;->add(ILjava/lang/Object;)V")) accountInsertCalls++;
            }
        }
        check("account gate add removed (4 add(Object) calls left)", accountAddCalls == 4);
        check("forced sign-in insert removed (no add(I,Object) left)", accountInsertCalls == 0);

        ClassDef toolbar = classOf(dexByClass, "Lcom/touchtype/keyboard/toolbar/Toolbar;");
        boolean stripFilter = false;
        for (Method m : toolbar.getMethods()) {
            if (m.getImplementation() == null) continue;
            for (Instruction i : m.getImplementation().getInstructions()) {
                String ref = refOf(i);
                if (ref != null && ref.contains("MorpheToolbar;->filterBloat")) stripFilter = true;
            }
        }
        check("strip filter safety net present", stripFilter);

        System.out.println();
        System.out.println("RESULT: " + passCount + " passed, " + failCount + " failed");
        if (failCount > 0) System.exit(1);
    }

    static int passCount = 0;
    static int failCount = 0;

    static void check(String name, boolean ok) {
        if (ok) {
            passCount++;
            System.out.println("  PASS  " + name);
        } else {
            failCount++;
            System.out.println("  FAIL  " + name);
        }
    }

    /** Flattens a manifest element tree into "name=value" text for containment checks. */
    static void walk(com.reandroid.arsc.chunk.xml.ResXmlElement element, StringBuilder out) {
        for (java.util.Iterator<com.reandroid.arsc.chunk.xml.ResXmlAttribute> it = element.getAttributes(); it.hasNext(); ) {
            com.reandroid.arsc.chunk.xml.ResXmlAttribute attribute = it.next();
            String value = attribute.getValueString();
            if (value != null) {
                out.append(attribute.getName()).append('=').append(value).append('\n');
            }
        }
        for (java.util.Iterator<com.reandroid.arsc.chunk.xml.ResXmlElement> it = element.getElements(); it.hasNext(); ) {
            walk(it.next(), out);
        }
    }

    static String refOf(Instruction i) {
        if (i instanceof ReferenceInstruction) {
            return ((ReferenceInstruction) i).getReference().toString();
        }
        return null;
    }

    static ClassDef classOf(java.util.Map<String, DexFile> dexByClass, String type) {
        DexFile dex = dexByClass.get(type);
        if (dex == null) throw new IllegalStateException("class not found: " + type);
        for (ClassDef cls : dex.getClasses()) {
            if (cls.getType().equals(type)) return cls;
        }
        throw new IllegalStateException("class not found: " + type);
    }
}
