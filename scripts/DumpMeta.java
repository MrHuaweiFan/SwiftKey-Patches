import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Dump every meta-data entry (any typing) in the APK manifest. */
public class DumpMeta {
    public static void main(String[] args) throws Exception {
        ApkModule module = ApkModule.loadApkFile(new File(args[0]));
        com.reandroid.arsc.chunk.xml.AndroidManifestBlock m = module.getAndroidManifestBlock();
        List<String[]> metaData = new ArrayList<>();
        walk(m.getDocumentElement(), metaData, 0);
        System.out.println("meta-data count: " + metaData.size());
        for (String[] md : metaData) System.out.println("  " + md[0] + " = " + md[1] + " (type " + md[2] + ")");
    }

    static void walk(ResXmlElement el, List<String[]> out, int depth) {
        if (el == null) return;
        String tag = null;
        try { tag = el.getName(); } catch (Throwable ignored) { }
        if ("meta-data".equals(tag)) {
            String name = null, value = null, type = null;
            Iterator<? extends ResXmlAttribute> it = el.getAttributes();
            while (it != null && it.hasNext()) {
                ResXmlAttribute a = it.next();
                String n;
                try { n = a.getName().replace("android:", ""); } catch (Throwable t) { continue; }
                String vs; String vt;
                try { vt = String.valueOf(a.getValueType()); } catch (Throwable t) { vt = "?"; }
                try { vs = a.getValueAsString(); } catch (Throwable t) { vs = null; }
                if (vs == null) {
                    try { vs = "bool:" + a.getValueAsBoolean(); } catch (Throwable t) { vs = "<err>"; }
                }
                if ("name".equals(n)) { name = vs; type = vt; }
                if ("value".equals(n)) value = vt + ":" + vs;
            }
            out.add(new String[] { String.valueOf(name), String.valueOf(value), String.valueOf(type) });
        }
        for (ResXmlElement child : iterElements(el)) walk(child, out, depth + 1);
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
