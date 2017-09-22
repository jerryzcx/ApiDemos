/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.apis.graphics.spritetext;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Paint.Style;
import android.graphics.drawable.Drawable;
import android.opengl.GLUtils;

import java.util.ArrayList;

import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GL11;
import javax.microedition.khronos.opengles.GL11Ext;

/**
 * An OpenGL text label maker.
 * <p>
 * OpenGL labels are implemented by creating a Bitmap, drawing all the labels
 * into the Bitmap, converting the Bitmap into an Alpha texture, and drawing
 * portions of the texture using glDrawTexiOES.
 * <p>
 * The benefits of this approach are that the labels are drawn using the high
 * quality anti-aliased font rasterizer, full character set support, and all the
 * text labels are stored on a single texture, which makes it faster to use.
 * <p>
 * The drawbacks are that you can only have as many labels as will fit onto one
 * texture, and you have to recreate the whole texture if any label text
 * changes.
 */
@SuppressWarnings({"WeakerAccess", "FieldCanBeLocal"})
public class LabelMaker {

    /**
     * Width of text, rounded up to power of 2, set to the {@code strikeWidth} parameter to our
     * constructor.
     */
    private int mStrikeWidth;
    /**
     * Height of text, rounded up to power of 2, set to the {@code strikeHeight} parameter to our
     * constructor.
     */
    private int mStrikeHeight;
    /**
     * true if we want a full color backing store (4444), otherwise we generate a grey L8 backing
     * store. Set to the {@code boolean fullColor} parameter of our constructor, always true in our
     * case.
     */
    private boolean mFullColor;
    /**
     * {@code Bitmap} we create our labels in, and when done creating the labels we upload it as
     * GL_TEXTURE_2D for drawing the labels (and then recycle it).
     */
    private Bitmap mBitmap;
    /**
     * {@code Canvas} we use to draw into {@code Bitmap mBitmap}.
     */
    private Canvas mCanvas;
    /**
     * We create this as a black paint, with a style of FILL but never actually use it.
     */
    private Paint mClearPaint;

    /**
     * Texture name of our label texture.
     */
    private int mTextureID;

    @SuppressWarnings("unused")
    private float mTexelWidth;  // Convert texel to U
    @SuppressWarnings("unused")
    private float mTexelHeight; // Convert texel to V
    /**
     * {@code u} (x) coordinate to use when adding next label to our texture.
     */
    private int mU;
    /**
     * {@code v} (y) coordinate to use when adding next label to our texture.
     */
    private int mV;
    /**
     * Height of the current line of labels.
     */
    private int mLineHeight;
    /**
     * List of the {@code Label} objects in our texture. A {@code Label} instance contains information
     * about the location and size of the label's text in the texture, as well as the cropping
     * parameters to use to draw only that {@code Label}.
     */
    private ArrayList<Label> mLabels = new ArrayList<>();

    /**
     * Constant used to set our field {@code mState} to indicate that we are just starting the
     * creation of our {@code Label} texture and there are no resources that need to be freed if
     * our {@code GLSurface} is destroyed.
     */
    private static final int STATE_NEW = 0;
    /**
     * Constant used to set our field {@code mState} to indicate that our {@code initialize} method
     * has been called, and we are ready to begin adding labels. We have acquired a texture name
     * for our field {@code mTextureID}, bound it to GL_TEXTURE_2D and configured it so there is
     * a texture which needs to be freed if our {@code GLSurface} is destroyed.
     */
    private static final int STATE_INITIALIZED = 1;
    /**
     * Constant used to set our field {@code mState} to indicate that our {@code beginAdding} method
     * has been called, and we are ready to add a label (or an additional label). {@code initialize}
     * was called before us, and we have allocated a {@code Bitmap} for our field {@code Bitmap mBitmap}
     * so there is some needed if our {@code GLSurface} is destroyed.
     */
    private static final int STATE_ADDING = 2;
    /**
     * Constant used to set our field {@code mState} to indicate that our {@code beginDrawing} method
     * has been called and we are in the process of drawing the various {@code Label} objects located
     * in our texture.
     */
    private static final int STATE_DRAWING = 3;
    /**
     * State that our {@code LabelMaker} instance is in, one of the above constants. It is used by
     * our method {@code checkState} to make sure that a state change is "legal", and also by our
     * method {@code shutdown} to make sure that our texture is deleted when our surface has been
     * destroyed (a texture will only have been allocated if {@code mState>STATE_NEW}).
     */
    private int mState;

    /**
     * Create a label maker. For maximum compatibility with various OpenGL ES implementations, the
     * strike width and height must be powers of two, We want the strike width to be at least as
     * wide as the widest window. First we initialize our field {@code boolean mFullColor} to our
     * parameter {@code boolean fullColor}, {@code int mStrikeWidth} to {@code int strikeWidth}, and
     * {@code int mStrikeHeight} to {@code int strikeHeight}. We configure 3 fields which are never
     * used: {@code mTexelWidth}. {@code mTexelHeight}, and {@code mPaint}. Finally we set our field
     * {@code int mState} to STATE_NEW (in this state we do not yet have a texture that will need to
     * be freed if our surface is destroyed, but we are ready to begin building our label texture).
     *
     * @param fullColor    true if we want a full color backing store (4444),
     *                     otherwise we generate a grey L8 backing store.
     * @param strikeWidth  width of strike
     * @param strikeHeight height of strike
     */
    public LabelMaker(boolean fullColor, int strikeWidth, int strikeHeight) {
        mFullColor = fullColor;
        mStrikeWidth = strikeWidth;
        mStrikeHeight = strikeHeight;
        mTexelWidth = (float) (1.0 / mStrikeWidth); // UNUSED
        mTexelHeight = (float) (1.0 / mStrikeHeight); // UNUSED
        mClearPaint = new Paint();
        mClearPaint.setARGB(0, 0, 0, 0);
        mClearPaint.setStyle(Style.FILL);
        mState = STATE_NEW;
    }

    /**
     * Call to initialize the class. Call whenever the surface has been created. First we set our
     * field {@code int mState} to STATE_INITIALIZED (in this state we have generated a texture name,
     * bound that texture to GL_TEXTURE_2D and configured it to our liking, but no image data has
     * been uploaded yet). Next we generate a texture name and save it in our field {@code int mTextureID}.
     * <p>
     * We bind the texture {@code mTextureID} to the target GL_TEXTURE_2D (GL_TEXTURE_2D becomes an
     * alias for our texture), and set both the texture parameters GL_TEXTURE_MIN_FILTER and
     * GL_TEXTURE_MAG_FILTER of GL_TEXTURE_2D to GL_NEAREST (uses the value of the texture element
     * that is nearest (in Manhattan distance) to the center of the pixel being textured when the
     * pixel being textured maps to an area greater than one texture element, as well as when the
     * the pixel being textured maps to an area less than or equal to one texture element). We set
     * the texture parameters GL_TEXTURE_WRAP_S and GL_TEXTURE_WRAP_T of GL_TEXTURE_2D both to
     * GL_CLAMP_TO_EDGE (when the fragment being textured is larger than the texture, the texture
     * elements at the edges will be used for the rest of the fragment).
     * <p>
     * Finally we set the texture environment parameter GL_TEXTURE_ENV_MODE of the texture environment
     * GL_TEXTURE_ENV to GL_REPLACE (the texture will replace whatever was in the fragment).
     *
     * @param gl the gl interface
     */
    public void initialize(GL10 gl) {
        mState = STATE_INITIALIZED;

        int[] textures = new int[1];
        gl.glGenTextures(1, textures, 0);
        mTextureID = textures[0];

        gl.glBindTexture(GL10.GL_TEXTURE_2D, mTextureID);

        // Use Nearest for performance.
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST);
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_NEAREST);

        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);

        gl.glTexEnvf(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_MODE, GL10.GL_REPLACE);
    }

    /**
     * Call when the surface has been destroyed NOT TRUE - it is called when it is necessary to update
     * the label texture used by this instance of {@code LabelMaker}. To do this if it has already
     * passed to a state where a texture name has been allocated by the hardware ({@code mState>STATE_NEW})
     * we must delete our texture {@code int mTextureID} and move our state field {@code int mState}
     * to the state STATE_NEW (ready to start building a new label texture).
     *
     * @param gl the gl interface
     */
    public void shutdown(GL10 gl) {
        if (gl != null) {
            if (mState > STATE_NEW) {
                int[] textures = new int[1];
                textures[0] = mTextureID;
                gl.glDeleteTextures(1, textures, 0);
                mState = STATE_NEW;
            }
        }
    }

    /**
     * Call before adding labels, and after calling {@code initialize}. Clears out any existing labels.
     * First we call {@code checkState} to make sure we are currently in STATE_INITIALIZED, and if so
     * changing to state STATE_ADDING. Next we clear our current list of {@code Label} objects contained
     * in {@code ArrayList<LabelMaker.Label> mLabels}. We reset the texture coordinates for our next
     * label {@code int mU} and {@code int mV}, and set the height of the current line {@code int mLineHeight}
     * to 0. We set {@code Bitmap.Config config} to ARGB_4444 if our field {@code boolean mFullColor}
     * is true, or to ALPHA_8 if it is false. Then we initialize our field {@code Bitmap mBitmap} with
     * a new instance of {@code Bitmap} using {@code config} which is {@code mStrikeWidth} pixels by
     * {@code mStrikeHeight} pixels. We initialize {@code Canvas mCanvas} with a canvas which will use
     * {@code mBitmap} to draw to, then set the entire {@code mBitmap} to black.
     *
     * @param gl the gl interface UNUSED
     */
    @SuppressWarnings("UnusedParameters")
    public void beginAdding(GL10 gl) {
        checkState(STATE_INITIALIZED, STATE_ADDING);
        mLabels.clear();
        mU = 0;
        mV = 0;
        mLineHeight = 0;
        Bitmap.Config config = mFullColor ?
                Bitmap.Config.ARGB_4444 : Bitmap.Config.ALPHA_8;
        mBitmap = Bitmap.createBitmap(mStrikeWidth, mStrikeHeight, config);
        mCanvas = new Canvas(mBitmap);
        mBitmap.eraseColor(0);
    }

    /**
     * Call to add a label, convenience function to call the full argument {@code add} with a null
     * {@code Drawable background} and {@code winWidth} and {@code minHeight} both equal to 0. We
     * simply supply a null for {@code Drawable background} and pass the call on.
     *
     * @param gl        the gl interface
     * @param text      the text of the label
     * @param textPaint the paint of the label
     * @return the id of the label, used to measure and draw the label
     */
    public int add(GL10 gl, String text, Paint textPaint) {
        return add(gl, null, text, textPaint);
    }

    /**
     * Call to add a label, convenience function to call the full argument {@code add} with
     * {@code winWidth} and {@code minHeight} both equal to 0. We simply supply 0 for both
     * {@code int minWidth} and {@code int minHeight} and pass the call on.
     *
     * @param gl         the gl interface
     * @param background background {@code Drawable} to use
     * @param text       the text of the label
     * @param textPaint  the paint of the label
     * @return the id of the label, used to measure and draw the label
     */
    public int add(GL10 gl, Drawable background, String text, Paint textPaint) {
        return add(gl, background, text, textPaint, 0, 0);
    }

    /**
     * Call to add a label UNUSED
     *
     * @param gl         the gl interface
     * @param background background {@code Drawable} to use
     * @param minWidth   minimum width of label
     * @param minHeight  minimum height of label
     * @return the id of the label, used to measure and draw the label
     */
    public int add(GL10 gl, Drawable background, int minWidth, int minHeight) {
        return add(gl, background, null, null, minWidth, minHeight);
    }

    /**
     * Call to add a label.
     *
     * @param gl        the gl interface UNUSED
     * @param text      the text of the label
     * @param textPaint the paint of the label
     * @param minWidth   minimum width of label
     * @param minHeight  minimum height of label
     * @return the id of the label, used to measure and draw the label
     */
    @SuppressWarnings("UnusedParameters")
    public int add(GL10 gl, Drawable background, String text, Paint textPaint, int minWidth, int minHeight) {
        checkState(STATE_ADDING, STATE_ADDING);
        boolean drawBackground = background != null;
        boolean drawText = (text != null) && (textPaint != null);

        Rect padding = new Rect();
        if (drawBackground) {
            background.getPadding(padding);
            minWidth = Math.max(minWidth, background.getMinimumWidth());
            minHeight = Math.max(minHeight, background.getMinimumHeight());
        }

        int ascent = 0;
        int descent = 0;
        int measuredTextWidth = 0;
        if (drawText) {
            // Paint.ascent is negative, so negate it.
            ascent = (int) Math.ceil(-textPaint.ascent());
            descent = (int) Math.ceil(textPaint.descent());
            measuredTextWidth = (int) Math.ceil(textPaint.measureText(text));
        }
        int textHeight = ascent + descent;
        int textWidth = Math.min(mStrikeWidth, measuredTextWidth);

        int padHeight = padding.top + padding.bottom;
        int padWidth = padding.left + padding.right;
        int height = Math.max(minHeight, textHeight + padHeight);
        int width = Math.max(minWidth, textWidth + padWidth);
        int effectiveTextHeight = height - padHeight;
        int effectiveTextWidth = width - padWidth;

        int centerOffsetHeight = (effectiveTextHeight - textHeight) / 2;
        int centerOffsetWidth = (effectiveTextWidth - textWidth) / 2;

        // Make changes to the local variables, only commit them
        // to the member variables after we've decided not to throw
        // any exceptions.

        int u = mU;
        int v = mV;
        int lineHeight = mLineHeight;

        if (width > mStrikeWidth) {
            width = mStrikeWidth;
        }

        // Is there room for this string on the current line?
        if (u + width > mStrikeWidth) {
            // No room, go to the next line:
            u = 0;
            v += lineHeight;
            lineHeight = 0;
        }
        lineHeight = Math.max(lineHeight, height);
        if (v + lineHeight > mStrikeHeight) {
            throw new IllegalArgumentException("Out of texture space.");
        }

        @SuppressWarnings("unused")
        int u2 = u + width;
        int vBase = v + ascent;
        @SuppressWarnings("unused")
        int v2 = v + height;

        if (drawBackground) {
            background.setBounds(u, v, u + width, v + height);
            background.draw(mCanvas);
        }

        if (drawText) {
            mCanvas.drawText(text,
                    u + padding.left + centerOffsetWidth,
                    vBase + padding.top + centerOffsetHeight,
                    textPaint);
        }

        // We know there's enough space, so update the member variables
        mU = u + width;
        mV = v;
        mLineHeight = lineHeight;
        mLabels.add(new Label(width, height, ascent, u, v + height, width, -height));
        return mLabels.size() - 1;
    }

    /**
     * Call to end adding labels. Must be called before drawing starts.
     *
     * @param gl the gl interface
     */
    public void endAdding(GL10 gl) {
        checkState(STATE_ADDING, STATE_INITIALIZED);
        gl.glBindTexture(GL10.GL_TEXTURE_2D, mTextureID);
        GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, mBitmap, 0);
        // Reclaim storage used by bitmap and canvas.
        mBitmap.recycle();
        mBitmap = null;
        mCanvas = null;
    }

    /**
     * Get the width in pixels of a given label.
     *
     * @param labelID index of label
     * @return the width in pixels
     */
    public float getWidth(int labelID) {
        return mLabels.get(labelID).width;
    }

    /**
     * Get the height in pixels of a given label.
     *
     * @param labelID index of label
     * @return the height in pixels
     */
    public float getHeight(int labelID) {
        return mLabels.get(labelID).height;
    }

    /**
     * Get the baseline of a given label. That's how many pixels from the top of
     * the label to the text baseline. (This is equivalent to the negative of
     * the label's paint's ascent.)
     *
     * @param labelID index of label
     * @return the baseline in pixels.
     */
    @SuppressWarnings("unused")
    public float getBaseline(int labelID) {
        return mLabels.get(labelID).baseline;
    }

    /**
     * Begin drawing labels. Sets the OpenGL state for rapid drawing.
     *
     * @param gl         the gl interface
     * @param viewWidth  view width
     * @param viewHeight view height
     */
    public void beginDrawing(GL10 gl, float viewWidth, float viewHeight) {
        checkState(STATE_INITIALIZED, STATE_DRAWING);
        gl.glBindTexture(GL10.GL_TEXTURE_2D, mTextureID);
        gl.glShadeModel(GL10.GL_FLAT);
        gl.glEnable(GL10.GL_BLEND);
        gl.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);
        gl.glColor4x(0x10000, 0x10000, 0x10000, 0x10000);
        gl.glMatrixMode(GL10.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadIdentity();
        gl.glOrthof(0.0f, viewWidth, 0.0f, viewHeight, 0.0f, 1.0f);
        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();
        // Magic offsets to promote consistent rasterization.
        gl.glTranslatef(0.375f, 0.375f, 0.0f);
    }

    /**
     * Draw a given label at a given x,y position, expressed in pixels, with the
     * lower-left-hand-corner of the view being (0,0).
     *
     * @param gl      the gl interface
     * @param x       x coordinate to draw at
     * @param y       y coordinate to draw at
     * @param labelID index of {@code Label} in the list {@code ArrayList<Label> mLabels} to draw
     */
    public void draw(GL10 gl, float x, float y, int labelID) {
        checkState(STATE_DRAWING, STATE_DRAWING);
        Label label = mLabels.get(labelID);
        gl.glEnable(GL10.GL_TEXTURE_2D);
        ((GL11) gl).glTexParameteriv(GL10.GL_TEXTURE_2D, GL11Ext.GL_TEXTURE_CROP_RECT_OES, label.mCrop, 0);
        ((GL11Ext) gl).glDrawTexiOES((int) x, (int) y, 0, (int) label.width, (int) label.height);
    }

    /**
     * Ends the drawing and restores the OpenGL state.
     *
     * @param gl the gl interface
     */
    public void endDrawing(GL10 gl) {
        checkState(STATE_DRAWING, STATE_INITIALIZED);
        gl.glDisable(GL10.GL_BLEND);
        gl.glMatrixMode(GL10.GL_PROJECTION);
        gl.glPopMatrix();
        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glPopMatrix();
    }

    private void checkState(int oldState, int newState) {
        if (mState != oldState) {
            throw new IllegalArgumentException("Can't call this method now.");
        }
        mState = newState;
    }

    private static class Label {

        public float width;
        public float height;
        public float baseline;
        public int[] mCrop;

        public Label(float width, float height, float baseLine, int cropU, int cropV, int cropW, int cropH) {
            this.width = width;
            this.height = height;
            this.baseline = baseLine;
            int[] crop = new int[4];
            crop[0] = cropU;
            crop[1] = cropV;
            crop[2] = cropW;
            crop[3] = cropH;
            mCrop = crop;
        }
    }
}
