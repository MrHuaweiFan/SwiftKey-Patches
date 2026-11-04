package com.swiftkey.telemetry;

import androidx.appcompat.app.AppCompatActivity;
import com.swiftkey.avro.telemetry.sk.android.PageName;

/**
 * COMPILE-ONLY STUB of SwiftKey's tracked AppCompat activity base of
 * 9.12.13.13 (Lcom/swiftkey/telemetry/TrackedAppCompatActivity;).
 *
 * The real class implements the R8-renamed telemetry page interface (Lqj/f;)
 * whose two methods stay abstract here:
 *
 *   public abstract PageName d();   // the settings screen's page name
 *   public abstract PageOrigin f(); // implemented by TrackedContainerActivity
 *
 * Declaring d() abstract on the stub forces every extension activity that
 * extends the app's settings bases to implement it - exactly what the real
 * class hierarchy requires, and what was missing in 1.1.0: the real
 * TrackedAppCompatActivity.onCreate calls d() before anything else, so an
 * activity without the override crashed with an AbstractMethodError.
 *
 * This class is never packaged into the extension.
 */
public abstract class TrackedAppCompatActivity extends AppCompatActivity {

    public abstract PageName d();
}
