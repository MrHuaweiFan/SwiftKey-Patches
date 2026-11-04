package com.morphe.swiftkey.toolbar;

import io.g;
import java.util.ArrayList;
import java.util.List;

/**
 * Toolbar helpers injected into SwiftKey by the Morphe patches.
 *
 * 1.2.0 works on the toolbar item MODEL instead of the rendered list, so the
 * "Debloat" items disappear and the blank elements show up everywhere the app
 * itself lists toolbar items - the strip, the toolgrid and the customizer's
 * "reorder items" screen - and can be reordered like any normal item.
 *
 * Every injection is a single branch-free call, because all looping lives
 * here in extension code.
 */
public final class MorpheToolbar {

    /**
     * The bloat items removed from every toolbar item list:
     *
     * 0x2d (45) Rewards, 0x05 (5) Stickers, 0x17 (23) Account sign-in,
     * 0x2c (44) Rich media, 0x2f (47) Search, 0x28 (40) Share,
     * 0x1d (29) Writing assistant (Copilot), 0x25 (37) Create (Bing image
     * creator) and 0x11 (17) Tips / new message (messaging centre).
     *
     * 1.2.1 also removes the two sticker PAGES of the rich content panel:
     * 0x1c (28) Stickers (the gallery tab of the emoji/GIF panel) and
     * 0x29 (41) Stickers (the dynamic sticker generation tab). Both live in
     * the panel tab list (Lio/e;->d) - the same catalog lists this helper
     * already filters - so adding them here deletes the tabs from the panel
     * itself instead of only hiding the stickers toolbar button.
     */
    private static final int[] BLOAT_ITEM_IDS = {
            0x2d, 0x05, 0x17, 0x2c, 0x2f, 0x28, 0x1d, 0x25, 0x11, 0x1c, 0x29,
    };

    /**
     * The items removed from the toolbar item lists by the "Remove GIFs and
     * translator" patch:
     *
     * 0x04 (4) GIF - the GIF toolbar item and the GIF page of the rich content
     * panel (the same id is the panel page of the emoji/GIF panel list
     * Lio/e;->d), and 0x0a (10) Translator - the toolbar item that binds the
     * external Microsoft Translator app.
     */
    private static final int[] GIF_TRANSLATOR_ITEM_IDS = {
            0x04, 0x0a,
    };

    /** First item id of the six blank elements (100..105). */
    public static final int FIRST_BLANK_ID = 100;

    /** Upper bound of the blank element ids (kept at six so factory requests
     *  for any blank id of an older ordering still resolve). */
    public static final int MAX_BLANK_COUNT = 6;

    /** Resource name of the blank element count integer written by the patch. */
    private static final String BLANK_COUNT_NAME = "morphe_blank_count";

    /** Resolved blank count, 0 until resolved. */
    private static volatile int blankCountCache;

    private MorpheToolbar() {
    }

    /** @return true when the id belongs to one of the removed bloat items. */
    static boolean isBloatId(int id) {
        for (int bloatId : BLOAT_ITEM_IDS) {
            if (id == bloatId) {
                return true;
            }
        }
        return false;
    }

    /** @return true when the id belongs to the GIF or the Translator item. */
    static boolean isGifTranslatorId(int id) {
        for (int item : GIF_TRANSLATOR_ITEM_IDS) {
            if (id == item) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves how many blank elements the "Add blank toolbar elements" patch
     * adds: the morphe_blank_count integer resource written by the patch from
     * the patch option (1..6). Resolved once through the process's application
     * context; until then (and whenever the resource is missing or out of
     * range) the historical default of six is used.
     */
    static int blankCount() {
        int cached = blankCountCache;
        if (cached != 0) {
            return cached;
        }
        int resolved = MAX_BLANK_COUNT;
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object app = threadClass.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                android.content.Context context = (android.content.Context) app;
                int id = context.getResources().getIdentifier(
                        BLANK_COUNT_NAME, "integer", context.getPackageName());
                if (id != 0) {
                    int configured = context.getResources().getInteger(id);
                    if (configured >= 1 && configured <= MAX_BLANK_COUNT) {
                        resolved = configured;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Keep the default: the blanks render exactly as before.
        }
        blankCountCache = resolved;
        return resolved;
    }

    /** @return true when the id is one of the six blank elements. */
    static boolean isBlankId(int id) {
        return id >= FIRST_BLANK_ID && id < FIRST_BLANK_ID + MAX_BLANK_COUNT;
    }

    /**
     * Removes the bloat items from a toolbar item catalog list. Called from
     * the catalog's static initializer right after each of its three lists is
     * built, so the bloat items vanish from the root: the customizer never
     * lists them, saved orderings that still contain them are dropped by the
     * catalog membership check, and the strip never renders them.
     */
    @SuppressWarnings("unused")
    public static List withoutBloat(List catalog) {
        if (catalog == null) {
            return catalog;
        }
        try {
            List result = new ArrayList(catalog.size());
            for (int index = 0; index < catalog.size(); index++) {
                Object entry = catalog.get(index);
                if (entry instanceof Integer) {
                    int id = (Integer) entry;
                    if (isBloatId(id)) {
                        continue;
                    }
                }
                result.add(entry);
            }
            return result;
        } catch (Throwable ignored) {
            // Never break the toolbar because of helper-internal errors.
            return catalog;
        }
    }

    /**
     * Appends the configured blank element ids to the main toolbar item
     * catalog. Called from the catalog's static initializer right after the
     * main list is built, so the blank elements become first-class catalog
     * items: they show up in the customizer's "reorder items" screen and can
     * be dragged into the strip like any normal item. How many ids are
     * appended (1..6) is the choice of the patch option, resolved from the
     * morphe_blank_count resource; see {@link #blankCount()}.
     */
    @SuppressWarnings("unused")
    public static List withBlanks(List catalog) {
        if (catalog == null) {
            return catalog;
        }
        try {
            int count = blankCount();
            List result = new ArrayList(catalog.size() + count);
            result.addAll(catalog);
            for (int index = 0; index < count; index++) {
                result.add(FIRST_BLANK_ID + index);
            }
            return result;
        } catch (Throwable ignored) {
            // Never break the toolbar because of helper-internal errors.
            return catalog;
        }
    }

    /**
     * The item factory hook: returns the blank element for a blank id, and
     * null for every other id (the app's own factory then runs unchanged).
     * Called from the one factory method every toolbar item of the app is
     * created through - strip, toolgrid and customizer alike.
     */
    @SuppressWarnings("unused")
    public static Object blankItem(int itemId) {
        try {
            if (isBlankId(itemId)) {
                return BlankToolbarItem.create(itemId - FIRST_BLANK_ID);
            }
        } catch (Throwable ignored) {
            // Never break the factory because of helper-internal errors.
        }
        return null;
    }

    /**
     * Removes the GIF and Translator items from a toolbar item catalog list.
     * Called from the catalog's static initializer right after each of its
     * three lists is built by the "Remove GIFs and translator" patch, so the
     * two items vanish from the root like the Debloat items do: the toolbar
     * button, the panel page (GIF) and every saved ordering lose them.
     */
    @SuppressWarnings("unused")
    public static List withoutGifTranslator(List catalog) {
        if (catalog == null) {
            return catalog;
        }
        try {
            List result = new ArrayList(catalog.size());
            for (int index = 0; index < catalog.size(); index++) {
                Object entry = catalog.get(index);
                if (entry instanceof Integer) {
                    int id = (Integer) entry;
                    if (isGifTranslatorId(id)) {
                        continue;
                    }
                }
                result.add(entry);
            }
            return result;
        } catch (Throwable ignored) {
            // Never break the toolbar because of helper-internal errors.
            return catalog;
        }
    }

    /**
     * Removes the bloat items from a rendered toolbar item list. Kept as a
     * safety net for orderings saved by older installs: the catalog already
     * drops them, and this catches anything that slips through anyway.
     */
    @SuppressWarnings("unused")
    public static void filterBloat(ArrayList items) {
        if (items == null) {
            return;
        }
        try {
            for (int index = 0; index < items.size(); ) {
                Object item = items.get(index);
                boolean bloat = false;
                if (item instanceof g) {
                    bloat = isBloatId(((g) item).getItemId());
                }
                if (bloat) {
                    items.remove(index);
                } else {
                    index++;
                }
            }
        } catch (Throwable ignored) {
            // Never break the toolbar because of helper-internal errors.
        }
    }
}
