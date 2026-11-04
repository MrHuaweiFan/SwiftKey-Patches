import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Final 1.2.2 sanity: meta-data collection flags, package, version. */
public class Verify3 {
    public static void main(String[] args) throws Exception {
        ApkModule module = ApkModule.loadApkFile(new File(args[0]));
        com.reandroid.arsc.chunk.xml.AndroidManifestBlock m = module.getAndroidManifestBlock();
        System.out.println("package     : " + m.getPackageName());
        System.out.println("versionName : " + m.getVersionName());
        System.out.println("versionCode : " + m.getVersionCode());

        String[] flags = {
            "firebase_analytics_collection_enabled",
            "firebase_crashlytics_collection_enabled",
            "firebase_sessions_enabled",
            "firebase_performance_collection_enabled",
            "firebase_analytics_collection_deactivated",
        };
        List<String[]> metaData = new ArrayList<>();
        collect(m.getDocumentElement(), metaData);
        for (String f : flags) {
            String val = null;
            for (String[] md : metaData) {
                if (f.equals(md[0])) { val = md[1]; break; }
            }
            System.out.println((val != null ? "  SET   " : "  absent") + "  " + f + " = " + val);
        }
        System.out.println("DONE");
    }

    static void collect(ResXmlElement el, List<String[]> out) {
        if (el == null) return;
        if (el.equalsName("meta-data")) {
            String name = null, value = null;
            Iterator<? extends ResXmlAttribute> it = el.getAttributes();
            while (it != null && it.hasNext()) {
                ResXmlAttribute a = it.next();
                String n = a.getName().replace("android:", "");
                String v;
                try { v = a.getValueAsString(); } catch (Throwable t) { v = null; }
                if ("name".equals(n)) name = v;
                if ("value".equals(n)) value = v;
            }
            if (name != null) out.add(new String[] { name, value });
        }
        for (ResXmlElement child : iterElements(el)) collect(child, out);
    }

    static Iterable<ResXmlElement> iterElements(ResXmlElement element) {
        if (element == null) return java.util.Collections.emptyList();
        List<ResXmlElement> out = new ArrayList<>();
        try {
            for (Iterator<? extends ResXmlElement> it = element.getElements(); it.hasNext(); ) {
                ResXmlElement child = it.next();
                if (child != null) out.add(child);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
