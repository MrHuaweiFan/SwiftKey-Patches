package hooman.morphe.patches.swiftkey.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

private const val GENERIC_RECORD = "Lorg/apache/avro/generic/GenericRecord;"

// SwiftKey's first-party analytics records all pass through
// Lcom/touchtype/telemetry/handlers/l;->send(GenericRecord) of 9.12.13.13, which fans each Avro
// record out to the configured telemetry sinks (the Lgq/f; interface). The class keeps its name in
// this build and is pinned by its (GenericRecord)V method plus the abstract class's Set
// constructor and no-arg abstract lifecycle method. Unlike 9.13.13.5 (hoomans' target, where the
// equivalent method is public final on the renamed Lzh0/n;->b), the 9.12.13.13 send method is
// public but NOT final, so no access flags are declared: the patcher compares access flags for
// exact equality.
internal object TelemetryRecordDispatchFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(GENERIC_RECORD),
    filters = listOf(
        methodCall(
            definingClass = "Lgq/f;",
            name = "a",
        ),
    ),
    custom = { _, classDef ->
        AccessFlags.ABSTRACT.isSet(classDef.accessFlags) &&
            classDef.methods.any { method ->
                method.name == "<init>" &&
                    method.parameterTypes.map { it.toString() } == listOf("Ljava/util/Set;")
            } &&
            classDef.methods.any { method ->
                AccessFlags.ABSTRACT.isSet(method.accessFlags) &&
                    method.returnType == "V" &&
                    method.parameterTypes.isEmpty()
            }
    },
)

// The PaperBoy record processor of 9.12.13.13 (Lqs/g;). It owns the paperboy configuration string
// and the three suspend methods around record uploads; pinned by the configuration string plus
// its (GenericRecord, Continuation) coroutine method. The 9.13.13.5 build of hoomans' patch pinned
// this class by its "LogSender" strings instead - that string does not exist in 9.12.13.13, so the
// paperboy configuration string is the 9.12.13.13 pin.
private val LOG_SENDER_CLASS = Fingerprint(
    strings = listOf("paperBoyConfig"),
    custom = { _, classDef ->
        classDef.methods.any { method ->
            method.returnType == "Ljava/lang/Object;" &&
                method.parameterTypes.map { it.toString() } ==
                listOf("Lorg/apache/avro/generic/GenericRecord;", "Lyr/c;")
        }
    },
)

// Primary PaperBoy upload loop (Lqs/g;->a(Z, Continuation) in this build). It drains queued records
// and uploads them; the continuation parameter class is pinned by the record enqueue method of the
// same class. No access flags are declared: the patcher compares access flags for exact equality,
// and the method is public final.
internal object PrimaryTelemetryUploadFingerprint : Fingerprint(
    classFingerprint = LOG_SENDER_CLASS,
    returnType = "Ljava/lang/Object;",
    custom = { method, _ ->
        val parameters = method.parameterTypes.map { it.toString() }
        parameters.size == 2 && parameters[0] == "Z" && parameters[1] == "Lyr/c;"
    },
)

// Secondary PaperBoy uploader in the same class. Patch it independently so no direct caller can
// reach the fallback endpoint after the primary loop has been disabled.
internal object SecondaryTelemetryUploadFingerprint : Fingerprint(
    classFingerprint = LOG_SENDER_CLASS,
    returnType = "Ljava/lang/Object;",
    custom = { method, _ ->
        method.parameterTypes.map { it.toString() } == listOf("Lyr/c;")
    },
)

// FullApplicationDelegate (LKk/g; in this build) initializes Adjust from the app-specific token.
// Pinning the token and the SDK call selects the exact startup method without relying on its R8
// class or method names. The token is the same in 9.12.13.13 and 9.13.13.5.
internal object AdjustInitializationFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("f30sdsr9mha8"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/adjust/sdk/Adjust;",
            name = "initSdk",
        ),
    ),
)
