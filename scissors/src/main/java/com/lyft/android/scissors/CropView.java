/*
 * Copyright (C) 2015 Lyft, Inc.
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
package com.lyft.android.scissors;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import com.lyft.android.scissors.CropViewExtensions.CropRequest;
import com.lyft.android.scissors.CropViewExtensions.LoadRequest;

import java.io.File;
import java.io.OutputStream;

/**
 * An {@link ImageView} with a fixed viewport and cropping capabilities.
 */
public class CropView extends ImageView {

	private static final String TAG = "CropView";

	/**
	 * OverlayShape defines shape of dark overlay.
	 *
	 * RECT: used as default, handles by original Lyft library.
	 * SQUARE: width and height are equals, the minimum of the 2 values is used.
	 * CIRCLE: same behavior than SQUARE, except the overlay is a circle.
	 */
	public enum OverlayShape {
		RECT, SQUARE, CIRCLE
	}

	private static final int MAX_TOUCH_POINTS = 2;
	private TouchManager touchManager;

	private Paint viewportPaint = new Paint();
	private Paint bitmapPaint   = new Paint();
	private Paint clearPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

	/**
	 * Bitmap used for the picture.
	 */
	private Bitmap bitmap;
	/**
	 * Bitmap used for the dark overlay.
	 */
	private Bitmap overlayBitmap;

	private Matrix transform = new Matrix();
	private Extensions extensions;

	private Canvas       overlayCanvas = new Canvas();
	/**
	 * Shape of dark overlay. Rectangle is the default value.
	 */
	private OverlayShape overlayShape  = OverlayShape.RECT;

	public CropView(Context context) {
		super(context);
		initCropView(context, null);
		initOverlay();
	}

	public CropView(Context context, AttributeSet attrs) {
		super(context, attrs);

		initCropView(context, attrs);
		initOverlay();
	}

	void initCropView(Context context, AttributeSet attrs) {
		CropViewConfig config = CropViewConfig.from(context, attrs);

		touchManager = new TouchManager(MAX_TOUCH_POINTS, config);

		bitmapPaint.setFilterBitmap(true);
		viewportPaint.setColor(config.getViewportOverlayColor());
	}

	void initOverlay() {
		clearPaint.setColor(Color.TRANSPARENT);
		clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

		//In versions > 3.0 need to define layer Type
		if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			setLayerType(View.LAYER_TYPE_SOFTWARE, null);
		}
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);

		if (bitmap == null) {
			return;
		}

		drawBitmap(canvas);
		drawOverlay(canvas);
	}

	private void drawBitmap(Canvas canvas) {
		transform.reset();
		touchManager.applyPositioningAndScale(transform);

		canvas.drawBitmap(bitmap, transform, bitmapPaint);
	}

	private void drawOverlay(Canvas canvas) {
		final int viewportWidth = touchManager.getViewportWidth();
		final int viewportHeight = touchManager.getViewportHeight();

		/**
		 * Draws plain full size overlay first.
		 */
		overlayBitmap.eraseColor(Color.TRANSPARENT);
		overlayCanvas.drawPaint(viewportPaint);

		if (overlayShape == OverlayShape.CIRCLE) {
			/**
			 * Overlay shape is CIRCLE.
			 */
			final float centerX = getWidth() / 2;
			final float centerY = getHeight() / 2;
			final float radius = Math.min(viewportWidth, viewportHeight) / 2;

			overlayCanvas.drawCircle(centerX, centerY, radius, clearPaint);

		} else if (overlayShape == OverlayShape.SQUARE) {
			/**
			 * Overlay shape is SQUARE.
			 */
			final float centerX = getWidth() / 2;
			final float centerY = getHeight() / 2;
			final float side = Math.min(viewportWidth, viewportHeight) / 2;

			overlayCanvas.drawRect(centerX - side, centerY - side, centerX + side, centerY + side, clearPaint);

		} else {
			/**
			 * Overlay shape is RECT.
			 */
			final float left = (getWidth() - viewportWidth) / 2;
			final float top = (getHeight() - viewportHeight) / 2;
			final float right = left + viewportWidth;
			final float bottom = top + viewportHeight;

			overlayCanvas.drawRect(left, top, right, bottom, clearPaint);
		}

		canvas.drawBitmap(overlayBitmap, 0, 0, null);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);

		if (overlayBitmap == null) {
			overlayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
			overlayCanvas.setBitmap(overlayBitmap);
		}

		resetTouchManager();
	}

	/**
	 * Returns the native aspect ratio of the image.
	 *
	 * @return The native aspect ratio of the image.
	 */
	public float getImageRatio() {
		Bitmap bitmap = getImageBitmap();
		return bitmap != null ? (float) bitmap.getWidth() / (float) bitmap.getHeight() : 0f;
	}

	/**
	 * Returns the aspect ratio of the viewport and crop rect.
	 *
	 * @return The current viewport aspect ratio.
	 */
	public float getViewportRatio() {
		return touchManager.getAspectRatio();
	}

	/**
	 * Sets the aspect ratio of the viewport and crop rect.  Defaults to
	 * the native aspect ratio if <code>ratio == 0</code>.
	 *
	 * @param ratio The new aspect ratio of the viewport.
	 */
	public void setViewportRatio(float ratio) {
		if (Float.compare(ratio, 0) == 0) {
			ratio = getImageRatio();
		}
		touchManager.setAspectRatio(ratio);
		resetTouchManager();
		invalidate();
	}

	/**
	 * Sets the shape of the dark overlay.
	 *
	 * Possible values:
	 * <ul>
	 * <li>{@link OverlayShape#RECT}</li>
	 * <li>{@link OverlayShape#SQUARE}</li>
	 * <li>{@link OverlayShape#CIRCLE}</li>
	 * </ul>
	 *
	 * @param overlayShape The new clear shape of the dark overlay.
	 */
	public void setOverlayShape(OverlayShape overlayShape) {
		this.overlayShape = overlayShape;
		invalidate();
	}

	@Override
	public void setImageResource(@DrawableRes int resId) {
		final Bitmap bitmap = resId > 0
				? BitmapFactory.decodeResource(getResources(), resId)
				: null;
		setImageBitmap(bitmap);
	}

	@Override
	public void setImageDrawable(@Nullable Drawable drawable) {
		final Bitmap bitmap;
		if (drawable instanceof BitmapDrawable) {
			BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
			bitmap = bitmapDrawable.getBitmap();
		} else if (drawable != null) {
			bitmap = Utils.asBitmap(drawable, getWidth(), getHeight());
		} else {
			bitmap = null;
		}

		setImageBitmap(bitmap);
	}

	@Override
	public void setImageURI(@Nullable Uri uri) {
		extensions().load(uri);
	}

	@Override
	public void setImageBitmap(@Nullable Bitmap bitmap) {
		this.bitmap = bitmap;
		resetTouchManager();
		invalidate();
	}

	/**
	 * @return Current working Bitmap or <code>null</code> if none has been set yet.
	 */
	@Nullable
	public Bitmap getImageBitmap() {
		return bitmap;
	}

	private void resetTouchManager() {
		final boolean invalidBitmap = bitmap == null;
		final int bitmapWidth = invalidBitmap ? 0 : bitmap.getWidth();
		final int bitmapHeight = invalidBitmap ? 0 : bitmap.getHeight();
		touchManager.resetFor(bitmapWidth, bitmapHeight, getWidth(), getHeight());
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		super.dispatchTouchEvent(event);

		touchManager.onEvent(event);
		invalidate();
		return true;
	}

	/**
	 * Performs synchronous image cropping based on configuration.
	 *
	 * @return A {@link Bitmap} cropped based on viewport and user panning and zooming or <code>null</code> if no {@link Bitmap} has been
	 * provided.
	 */
	@Nullable
	public Bitmap crop() {
		if (bitmap == null) {
			return null;
		}

		final Bitmap src = bitmap;
		final Bitmap.Config srcConfig = src.getConfig();
		final Bitmap.Config config = srcConfig == null ? Bitmap.Config.ARGB_8888 : srcConfig;

		final int viewportHeight;
		final int viewportWidth;

		if (overlayShape == OverlayShape.SQUARE || overlayShape == OverlayShape.CIRCLE) {
			/**
			 * Square crop, even if it's a CIRCLE.
			 */
			viewportWidth = Math.min(touchManager.getViewportWidth(), touchManager.getViewportHeight());
			viewportHeight = viewportWidth;
		} else {
			/**
			 * Default crop (the shape is RECT).
			 */
			viewportWidth = touchManager.getViewportWidth();
			viewportHeight = touchManager.getViewportHeight();
		}

		final Bitmap dst = Bitmap.createBitmap(viewportWidth, viewportHeight, config);

		Canvas canvas = new Canvas(dst);
		final int left = (getRight() - viewportWidth) / 2;
		final int top = (getBottom() - viewportHeight) / 2;
		canvas.translate(-left, -top);

		drawBitmap(canvas);

		return dst;
	}

	/**
	 * Obtain current viewport width.
	 *
	 * @return Current viewport width.
	 * <p>Note: It might be 0 if layout pass has not been completed.</p>
	 */
	public int getViewportWidth() {
		return touchManager.getViewportWidth();
	}

	/**
	 * Obtain current viewport height.
	 *
	 * @return Current viewport height.
	 * <p>Note: It might be 0 if layout pass has not been completed.</p>
	 */
	public int getViewportHeight() {
		return touchManager.getViewportHeight();
	}

	/**
	 * Offers common utility extensions.
	 *
	 * @return Extensions object used to perform chained calls.
	 */
	public Extensions extensions() {
		if (extensions == null) {
			extensions = new Extensions(this);
		}
		return extensions;
	}

	/**
	 * Optional extensions to perform common actions involving a {@link CropView}
	 */
	public static class Extensions {

		private final CropView cropView;

		Extensions(CropView cropView) {
			this.cropView = cropView;
		}

		/**
		 * Load a {@link Bitmap} using an automatically resolved {@link BitmapLoader} which will attempt to scale image to fill view.
		 *
		 * @param model Model used by {@link BitmapLoader} to load desired {@link Bitmap}
		 * @see PicassoBitmapLoader
		 * @see GlideBitmapLoader
		 */
		public void load(@Nullable Object model) {
			new LoadRequest(cropView)
					.load(model);
		}

		/**
		 * Load a {@link Bitmap} using given {@link BitmapLoader}, you must call {@link LoadRequest#load(Object)} afterwards.
		 *
		 * @param bitmapLoader {@link BitmapLoader} used to load desired {@link Bitmap}
		 * @see PicassoBitmapLoader
		 * @see GlideBitmapLoader
		 */
		public LoadRequest using(@Nullable BitmapLoader bitmapLoader) {
			return new LoadRequest(cropView).using(bitmapLoader);
		}

		/**
		 * Perform an asynchronous crop request.
		 *
		 * @return {@link CropRequest} used to chain a configure cropping request, you must call either one of:
		 * <ul>
		 * <li>{@link CropRequest#into(File)}</li>
		 * <li>{@link CropRequest#into(OutputStream, boolean)}</li>
		 * </ul>
		 */
		public CropRequest crop() {
			return new CropRequest(cropView);
		}

		/**
		 * Perform a pick image request using {@link Activity#startActivityForResult(Intent, int)}.
		 */
		public void pickUsing(@NonNull Activity activity, int requestCode) {
			CropViewExtensions.pickUsing(activity, requestCode);
		}
	}
}
