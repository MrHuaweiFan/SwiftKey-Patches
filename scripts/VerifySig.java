import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.cert.X509Certificate;

public class VerifySig {
    public static void main(String[] args) throws Exception {
        for (String name : args) {
            File apk = new File(name);
            ApkVerifier.Builder builder = new ApkVerifier.Builder(apk);
            ApkVerifier.Result result = builder.build().verify();
            boolean verified = result.isVerified();
            boolean warnsOnly = result.getWarnings().isEmpty() || result.getErrors().isEmpty();
            System.out.println(apk.getName() + ": verified=" + verified
                    + " errors=" + result.getErrors().size()
                    + " warnings=" + result.getWarnings().size());
            result.getErrors().forEach(e -> System.out.println("  ERROR: " + e));
            if (!result.getSignerCertificates().isEmpty()) {
                X509Certificate cert = result.getSignerCertificates().get(0);
                System.out.println("  signer: " + cert.getSubjectX500Principal().getName()
                        + " valid until " + cert.getNotAfter());
            }
            if (!verified) System.exit(1);
        }
    }
}
