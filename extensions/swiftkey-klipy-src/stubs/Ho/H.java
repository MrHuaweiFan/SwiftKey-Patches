package Ho;

/**
 * Compile-only stub of SwiftKey 9.12.13.13's Tenor GIF Retrofit service
 * (LHo/H; in the R8-renamed build).
 *
 * The real class is a Retrofit interface whose implementation is created at
 * runtime; the Morphe "GIF source" patch replaces the factory (LHo/G;->a)
 * so that an instance of com.morphe.swiftkey.klipy.MorpheKlipyService is
 * returned instead. The method signatures must keep their exact erased
 * shapes, because the app calls them through invoke-interface:
 *
 *  - a(String key, String q, String locale, Integer limit, String pos, Continuation)
 *      GET /v1/search (the GIF search)
 *  - b(String key, String id, String locale, String q, Continuation)
 *      GET /v1/registershare (share tracking)
 *  - c(String ids, String key, Integer limit, Continuation)
 *      GET /v1/gifs (GIFs by ids, for the recents row)
 *
 * The Continuation parameter (Lwr/c; = kotlin.coroutines.Continuation) marks
 * the methods as Kotlin suspend functions.
 */
public interface H {
    Object a(String key, String q, String locale, Integer limit, String pos, wr.c continuation);

    Object b(String key, String id, String locale, String q, wr.c continuation);

    Object c(String ids, String key, Integer limit, wr.c continuation);
}
