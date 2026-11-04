package com.morphe.swiftkey.toolbar;

import Pg.t2;
import eo.a;
import io.f;
import io.g;
import java.util.Collection;
import java.util.Collections;

/**
 * A blank toolbar element of the "Add blank toolbar elements" Morphe patch.
 *
 * It is a first-class toolbar item: it lives in the item catalog with a real
 * id (100..105), a real label ("Blank 1" .. "Blank 6") and a real icon (the
 * transparent drawable added by the patch), so it appears in the customizer's
 * "reorder items" screen and can be dragged around exactly like the emoji,
 * GIF or incognito items. In the strip it renders as an empty, transparent
 * button of the same size as the regular buttons and does nothing at all
 * when tapped or long-pressed.
 */
@SuppressWarnings("unused")
public final class BlankToolbarItem implements g {

    /** Resource name of the transparent drawable added by the patch. */
    private static final String BLANK_DRAWABLE_NAME = "morphe_toolbar_blank";

    /** Resolved drawable resource id of the transparent icon (0 until resolved). */
    private static volatile int blankDrawableId;

    private final int id;

    private BlankToolbarItem(int id) {
        this.id = id;
    }

    /**
     * Create the blank element with the given index (0..5).
     *
     * @param index the index of the element (0 to 5), making six distinct items.
     * @return the new blank toolbar element.
     */
    public static g create(int index) {
        return new BlankToolbarItem(MorpheToolbar.FIRST_BLANK_ID + index);
    }

    /**
     * Resolve the transparent drawable id once, through the process's
     * application context. The factory creates items before any view exists,
     * so the anchor-view based resolution of 1.1.0 had to go.
     */
    private static int drawableId() {
        int result = blankDrawableId;
        if (result == 0) {
            try {
                Class<?> threadClass = Class.forName("android.app.ActivityThread");
                Object app = threadClass.getMethod("currentApplication").invoke(null);
                if (app instanceof android.content.Context) {
                    android.content.Context context = (android.content.Context) app;
                    result = context.getResources().getIdentifier(
                            BLANK_DRAWABLE_NAME, "drawable", context.getPackageName());
                    if (result != 0) {
                        blankDrawableId = result;
                    }
                }
            } catch (Throwable ignored) {
                // Keep 0: the renderer falls back to a framework resource.
            }
        }
        return result;
    }

    // ---- io.g implementation (the R8-renamed toolbar item interface) ----

    /** Long-press / context support: not supported. */
    @Override
    public boolean a(f event) {
        return false;
    }

    /** Toolbar item type: the most generic existing type constant. */
    @Override
    public t2 b() {
        return t2.a;
    }

    /** Click handler: never called because {@link #g()} reports disabled. */
    @Override
    public void c(f event) {
    }

    /** Coach mark data: none. */
    @Override
    public a d() {
        return null;
    }

    /** Customiser label: "Blank 1" .. "Blank 6", so the items are identifiable. */
    @Override
    public String e() {
        return "Blank " + (id - MorpheToolbar.FIRST_BLANK_ID + 1);
    }

    /** Icon resource: the transparent drawable added by the patch. */
    @Override
    public int f() {
        int resolved = drawableId();
        // Never return 0: the theme change handler of the toolbar expects a
        // drawable. Fall back to the framework's transparent color resource
        // (android.R.color.transparent, a stable framework id).
        return resolved != 0 ? resolved : 0x01060000;
    }

    /** Enabled state: false, so taps are ignored entirely. */
    @Override
    public boolean g() {
        return false;
    }

    /** Accessibility description: empty. */
    @Override
    public String getContentDescription() {
        return "";
    }

    /** The unique id of this blank element. */
    @Override
    public int getItemId() {
        return id;
    }

    /** Additional data: none. */
    @Override
    public Collection h() {
        return Collections.EMPTY_LIST;
    }

    /**
     * Enabled-in-state-model: true. The state builder drops every item whose
     * i() returns false before it ever reaches the strip or the customizer,
     * so a blank element must report true here (this was false in 1.1.0).
     */
    @Override
    public boolean i() {
        return true;
    }
}
