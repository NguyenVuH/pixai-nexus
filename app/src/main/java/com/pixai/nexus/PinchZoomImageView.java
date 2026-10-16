package com.pixai.nexus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import androidx.appcompat.widget.AppCompatImageView;

public class PinchZoomImageView extends AppCompatImageView {

    private Matrix matrix = new Matrix();
    private Matrix prevMatrix = new Matrix();

    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int mode = NONE;

    private PointF start = new PointF();
    private PointF mid = new PointF();
    private float oldDist = 1f;

    private float minScale = 1.0f;
    private float maxScale = 6.0f;
    private float saveScale = 1.0f;

    private float[] m = new float[9];
    private int viewWidth, viewHeight;
    private float origWidth, origHeight;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    public PinchZoomImageView(Context context) {
        super(context);
        init(context);
    }

    public PinchZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public PinchZoomImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        super.setClickable(true);
        setScaleType(ScaleType.MATRIX);
        matrix = new Matrix();

        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());

        setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);

            int pointerCount = event.getPointerCount();
            if (pointerCount > 1 || saveScale > 1.05f || scaleDetector.isInProgress()) {
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
            }

            PointF curr = new PointF(event.getX(), event.getY());

            switch (event.getAction() & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:
                    prevMatrix.set(matrix);
                    start.set(event.getX(), event.getY());
                    mode = DRAG;
                    if (saveScale > 1.05f && v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (mode == DRAG && saveScale > 1.05f) {
                        float deltaX = curr.x - start.x;
                        float deltaY = curr.y - start.y;
                        matrix.set(prevMatrix);
                        matrix.postTranslate(deltaX, deltaY);
                        fixTranslation();
                        setImageMatrix(matrix);
                        invalidate();
                    } else if (saveScale <= 1.05f && pointerCount == 1) {
                        // Cho phép ScrollView cha cuộn tự nhiên khi ảnh ở tỷ lệ mặc định
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        return false;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    mode = NONE;
                    if (saveScale <= 1.05f && v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    oldDist = spacing(event);
                    if (oldDist > 10f) {
                        prevMatrix.set(matrix);
                        midPoint(mid, event);
                        mode = ZOOM;
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    break;

                case MotionEvent.ACTION_CANCEL:
                    mode = NONE;
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    break;
            }

            setImageMatrix(matrix);
            invalidate();
            return true;
        });
    }

    private float spacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private void midPoint(PointF point, MotionEvent event) {
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            mode = ZOOM;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            float origScale = saveScale;
            saveScale *= scaleFactor;
            if (saveScale > maxScale) {
                saveScale = maxScale;
                scaleFactor = maxScale / origScale;
            } else if (saveScale < minScale) {
                saveScale = minScale;
                scaleFactor = minScale / origScale;
            }

            if (saveScale <= 1.02f) {
                fitCenterImage();
            } else {
                matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                fixTranslation();
            }

            setImageMatrix(matrix);
            invalidate();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            if (saveScale <= 1.05f) {
                resetZoom();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (saveScale > 1.05f) {
                // Thu về mặc định
                resetZoom();
            } else {
                // Phóng to 2.5x tại điểm chạm
                saveScale = 2.5f;
                matrix.postScale(2.5f, 2.5f, e.getX(), e.getY());
                fixTranslation();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            setImageMatrix(matrix);
            invalidate();
            return true;
        }
    }

    public void resetZoom() {
        saveScale = 1.0f;
        mode = NONE;
        fitCenterImage();
        setImageMatrix(matrix);
        invalidate();
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
    }

    private void fixTranslation() {
        matrix.getValues(m);
        float transX = m[Matrix.MTRANS_X];
        float transY = m[Matrix.MTRANS_Y];

        float fixTransX = getFixTrans(transX, viewWidth, origWidth * saveScale);
        float fixTransY = getFixTrans(transY, viewHeight, origHeight * saveScale);

        if (fixTransX != 0 || fixTransY != 0) {
            matrix.postTranslate(fixTransX, fixTransY);
        }
    }

    private float getFixTrans(float trans, float viewSize, float contentSize) {
        float minTrans, maxTrans;

        if (contentSize <= viewSize) {
            minTrans = (viewSize - contentSize) / 2f;
            maxTrans = (viewSize - contentSize) / 2f;
        } else {
            minTrans = viewSize - contentSize;
            maxTrans = 0;
        }

        if (trans < minTrans) return -trans + minTrans;
        if (trans > maxTrans) return -trans + maxTrans;
        return 0;
    }

    private void fitCenterImage() {
        Drawable drawable = getDrawable();
        if (drawable == null || drawable.getIntrinsicWidth() == 0 || drawable.getIntrinsicHeight() == 0) return;
        if (viewWidth == 0 || viewHeight == 0) return;

        int bmWidth = drawable.getIntrinsicWidth();
        int bmHeight = drawable.getIntrinsicHeight();

        float scaleX = (float) viewWidth / (float) bmWidth;
        float scaleY = (float) viewHeight / (float) bmHeight;
        float scale = Math.min(scaleX, scaleY);

        matrix.setScale(scale, scale);

        float redundantYSpace = (float) viewHeight - (scale * (float) bmHeight);
        float redundantXSpace = (float) viewWidth - (scale * (float) bmWidth);
        redundantYSpace /= 2;
        redundantXSpace /= 2;

        matrix.postTranslate(redundantXSpace, redundantYSpace);

        origWidth = scale * bmWidth;
        origHeight = scale * bmHeight;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        viewWidth = MeasureSpec.getSize(widthMeasureSpec);
        viewHeight = MeasureSpec.getSize(heightMeasureSpec);

        if (saveScale == 1f) {
            fitCenterImage();
            setImageMatrix(matrix);
        }
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        saveScale = 1.0f;
        fitCenterImage();
        setImageMatrix(matrix);
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        saveScale = 1.0f;
        fitCenterImage();
        setImageMatrix(matrix);
    }
}
