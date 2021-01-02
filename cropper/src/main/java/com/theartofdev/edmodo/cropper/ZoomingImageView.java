// "Therefore those skilled at the unorthodox
// are infinite as heaven and earth,
// inexhaustible as the great rivers.
// When they come to an end,
// they begin again,
// like the days and months;
// they die and are reborn,
// like the four seasons."
//
// - Sun Tsu,
// "The Art of War"

package com.theartofdev.edmodo.cropper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import java.lang.ref.WeakReference;
import java.util.UUID;

import androidx.exifinterface.media.ExifInterface;

/** Custom view that provides cropping capabilities to an image. */
public class ZoomingImageView extends AbstractCropImageView {

  public ZoomingImageView(Context context) {
    this(context, null);
  }

  public ZoomingImageView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public void setCropRectF(RectF rect) {
    resetCropRect();
    mCropOverlayView.setCropWindowRect(rect);
    handleCropWindowChanged(false, true);
    mCropOverlayView.fixCurrentCropWindowRect();
  }

  @Override
  public void setAutoZoomEnabled(boolean autoZoomEnabled) {
    if (mAutoZoomEnabled != autoZoomEnabled) {
      mAutoZoomEnabled = autoZoomEnabled;
      handleCropWindowChanged(false, true);
      mCropOverlayView.invalidate();
    }
  }

  /**
   * Handle crop window change to:<br>
   * 1. Execute auto-zoom-in/out depending on the area covered of cropping window relative to the
   * available view area.<br>
   * 2. Slide the zoomed sub-area if the cropping window is outside of the visible view sub-area.
   * <br>
   *
   * @param inProgress is the crop window change is still in progress by the user
   * @param animate if to animate the change to the image matrix, or set it directly
   */
  protected void handleCropWindowChanged(boolean inProgress, boolean animate) {
    int width = getWidth();
    int height = getHeight();
    if (mBitmap != null && width > 0 && height > 0) {

      RectF cropRect = mCropOverlayView.getCropWindowRect();
      if (inProgress) {
        if (cropRect.left < 0
            || cropRect.top < 0
            || cropRect.right > width
            || cropRect.bottom > height) {
          applyImageMatrix(width, height, false, false);
        }
      } else if (mAutoZoomEnabled || mZoom > 1) {
        float newZoom = 0;
        // keep the cropping window covered area to 50%-65% of zoomed sub-area
        if (mZoom < mMaxZoom
            && cropRect.width() < width * 1f
            && cropRect.height() < height * 1f) {
          newZoom =
              Math.min(
                  mMaxZoom,
                  Math.min(
                      width / (cropRect.width() / mZoom),
                      height / (cropRect.height() / mZoom)));
        }
        if (mZoom > 1 && (cropRect.width() > width * 1f || cropRect.height() > height * 1f)) {
          newZoom =
              Math.max(
                  1,
                  Math.min(
                      width / (cropRect.width() / mZoom),
                      height / (cropRect.height() / mZoom)));
        }
        if (!mAutoZoomEnabled) {
          newZoom = 1;
        }

        if (newZoom > 0 && newZoom != mZoom) {
          if (animate) {
            if (mAnimation == null) {
              // lazy create animation single instance
              mAnimation = new CropImageAnimation(mImageView, mCropOverlayView);
            }
            // set the state for animation to start from
            mAnimation.setStartState(mImagePoints, mImageMatrix);
          }

          mZoom = newZoom;

          applyImageMatrix(width, height, true, animate);
        }
      }
      if (mOnSetCropWindowChangeListener != null && !inProgress) {
        mOnSetCropWindowChangeListener.onCropWindowChanged();
      }
    }
  }

  /**
   * Apply matrix to handle the image inside the image view.
   *
   * @param width the width of the image view
   * @param height the height of the image view
   */
  protected void applyImageMatrix(float width, float height, boolean center, boolean animate) {
    if (mBitmap != null && width > 0 && height > 0) {

      mImageMatrix.invert(mImageInverseMatrix);
      RectF cropRect = mCropOverlayView.getCropWindowRect();
      mImageInverseMatrix.mapRect(cropRect);

      mImageMatrix.reset();

      // move the image to the center of the image view first so we can manipulate it from there
      mImageMatrix.postTranslate(
          (width - mBitmap.getWidth()) / 2, (height - mBitmap.getHeight()) / 2);
      mapImagePointsByImageMatrix();

      // rotate the image the required degrees from center of image
      if (mDegreesRotated > 0) {
        mImageMatrix.postRotate(
            mDegreesRotated,
            BitmapUtils.getRectCenterX(mImagePoints),
            BitmapUtils.getRectCenterY(mImagePoints));
        mapImagePointsByImageMatrix();
      }

      // scale the image to the image view, image rect transformed to know new width/height
      float scale =
          Math.min(
              width / BitmapUtils.getRectWidth(mImagePoints),
              height / BitmapUtils.getRectHeight(mImagePoints));
      if (mScaleType == ScaleType.FIT_CENTER
          || (mScaleType == ScaleType.CENTER_INSIDE && scale < 1)
          || (scale > 1 && mAutoZoomEnabled)) {
        mImageMatrix.postScale(
            scale,
            scale,
            BitmapUtils.getRectCenterX(mImagePoints),
            BitmapUtils.getRectCenterY(mImagePoints));
        mapImagePointsByImageMatrix();
      }

      // scale by the current zoom level
      float scaleX = mFlipHorizontally ? -mZoom : mZoom;
      float scaleY = mFlipVertically ? -mZoom : mZoom;
      mImageMatrix.postScale(
          scaleX,
          scaleY,
          BitmapUtils.getRectCenterX(mImagePoints),
          BitmapUtils.getRectCenterY(mImagePoints));
      mapImagePointsByImageMatrix();

      mImageMatrix.mapRect(cropRect);

      if (center) {
        // set the zoomed area to be as to the center of cropping window as possible
        mZoomOffsetX =
            width > BitmapUtils.getRectWidth(mImagePoints)
                ? 0
                : Math.max(
                        Math.min(
                            width / 2 - cropRect.centerX(), -BitmapUtils.getRectLeft(mImagePoints)),
                        getWidth() - BitmapUtils.getRectRight(mImagePoints))
                    / scaleX;
        mZoomOffsetY =
            height > BitmapUtils.getRectHeight(mImagePoints)
                ? 0
                : Math.max(
                        Math.min(
                            height / 2 - cropRect.centerY(), -BitmapUtils.getRectTop(mImagePoints)),
                        getHeight() - BitmapUtils.getRectBottom(mImagePoints))
                    / scaleY;
      } else {
        // adjust the zoomed area so the crop window rectangle will be inside the area in case it
        // was moved outside
        mZoomOffsetX =
            Math.min(Math.max(mZoomOffsetX * scaleX, -cropRect.left), -cropRect.right + width)
                / scaleX;
        mZoomOffsetY =
            Math.min(Math.max(mZoomOffsetY * scaleY, -cropRect.top), -cropRect.bottom + height)
                / scaleY;
      }

      // apply to zoom offset translate and update the crop rectangle to offset correctly
      mImageMatrix.postTranslate(mZoomOffsetX * scaleX, mZoomOffsetY * scaleY);
      cropRect.offset(mZoomOffsetX * scaleX, mZoomOffsetY * scaleY);
      mCropOverlayView.setCropWindowRect(cropRect);
      mapImagePointsByImageMatrix();
      mCropOverlayView.invalidate();

      // set matrix to apply
      if (animate) {
        // set the state for animation to end in, start animation now
        mAnimation.setEndState(mImagePoints, mImageMatrix);
        mImageView.startAnimation(mAnimation);
      } else {
        mImageView.setImageMatrix(mImageMatrix);
      }

      // update the image rectangle in the crop overlay
      updateImageBounds(false);
    }
  }



  /**
   * Adjust the given image rectangle by image transformation matrix to know the final rectangle of
   * the image.<br>
   * To get the proper rectangle it must be first reset to original image rectangle.
   */
  protected void mapImagePointsByImageMatrix() {
    mImagePoints[0] = 0;
    mImagePoints[1] = 0;
    mImagePoints[2] = mBitmap.getWidth();
    mImagePoints[3] = 0;
    mImagePoints[4] = mBitmap.getWidth();
    mImagePoints[5] = mBitmap.getHeight();
    mImagePoints[6] = 0;
    mImagePoints[7] = mBitmap.getHeight();
    mImageMatrix.mapPoints(mImagePoints);
    mScaleImagePoints[0] = 0;
    mScaleImagePoints[1] = 0;
    mScaleImagePoints[2] = 100;
    mScaleImagePoints[3] = 0;
    mScaleImagePoints[4] = 100;
    mScaleImagePoints[5] = 100;
    mScaleImagePoints[6] = 0;
    mScaleImagePoints[7] = 100;
    mImageMatrix.mapPoints(mScaleImagePoints);
  }

  /**
   * Set visibility of crop overlay to hide it when there is no image or specificly set by client.
   */
  protected void setCropOverlayVisibility() {
    if (mCropOverlayView != null) {
      mCropOverlayView.setVisibility(mShowCropOverlay && mBitmap != null ? VISIBLE : INVISIBLE);
    }
  }



  /**
   * Set visibility of progress bar when async loading/cropping is in process and show is enabled.
   */
  protected void setProgressBarVisibility() {
    boolean visible =
        mShowProgressBar
            && (mBitmap == null && mBitmapLoadingWorkerTask != null
                || mBitmapCroppingWorkerTask != null);
    mProgressBar.setVisibility(visible ? VISIBLE : INVISIBLE);
  }

  /** Update the scale factor between the actual image bitmap and the shown image.<br> */
  protected void updateImageBounds(boolean clear) {
    if (mBitmap != null && !clear) {

      // Get the scale factor between the actual Bitmap dimensions and the displayed dimensions for
      // width/height.
      float scaleFactorWidth =
          100f * mLoadedSampleSize / BitmapUtils.getRectWidth(mScaleImagePoints);
      float scaleFactorHeight =
          100f * mLoadedSampleSize / BitmapUtils.getRectHeight(mScaleImagePoints);
      mCropOverlayView.setCropWindowLimits(
          getWidth(), getHeight(), scaleFactorWidth, scaleFactorHeight);
    }

    // set the bitmap rectangle and update the crop window after scale factor is set
    mCropOverlayView.setBounds(clear ? null : mImagePoints, getWidth(), getHeight());
  }
  // endregion

  // region: Inner class: CropShape


}
