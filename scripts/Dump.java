import com.reandroid.apk.ApkModule;
import java.io.File;

public class Dump {
    public static void main(String[] args) throws Exception {
        ApkModule module = ApkModule.loadApkFile(new File(args[0]));
        String xml = module.getAndroidManifestBlock().toString();
        System.out.println("=== manifest length: " + xml.length());
        System.out.println(xml.substring(0, Math.min(2500, xml.length())));
    }
}
