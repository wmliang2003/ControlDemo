package com.example.mylibrary.todo;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.MediaStore.Images.Media;
import android.support.annotation.RequiresApi;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.List;
import timber.log.Timber;

/**
 * 截屏监听管理器
 *
 * 截屏判断依据: 监听媒体数据库的数据改变, 在有数据改变时获取最后
 * 插入数据库的一条图片数据, 如果符合以下规则, 则认为截屏了:
 * 1. 时间判断, 图片的生成时间在开始监听之后, 并与当前时间相隔10秒内;
 * 2. 尺寸判断, 图片的尺寸没有超过屏幕的尺寸;
 * 3. 路径判断, 图片路径符合包含特定的关键词。
 *
 *
 * manager = ScreenShotListenManager.newInstance(getContext());
 * manager.setListener(new ScreenShotListenManager.OnScreenShotListener() {
 * public void onShot(String imagePath) {
 * // do something } });  ...
 * Toast.makeText(getContext(),imagePath,Toast.LENGTH_LONG).show();
 * }
 * });
 * manager.startListen();
 * ....
 * manager.stopListen();
 */
public class ScreenShotListenManager {
  private static final String TAG = "ScreenShotListenManager";
  private static final String[] MEDIA_PROJECTIONS = {
      MediaStore.Images.ImageColumns.DATA, MediaStore.Images.ImageColumns.DATE_TAKEN,
  };

  /** 读取媒体数据库时需要读取的列, 其中 WIDTH 和 HEIGHT 字段在 API 16 以后才有 */
  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN) private static final String[]
      MEDIA_PROJECTIONS_API_16 = {
      MediaStore.Images.ImageColumns.DATA, MediaStore.Images.ImageColumns.DATE_TAKEN,
      MediaStore.Images.ImageColumns.WIDTH, MediaStore.Images.ImageColumns.HEIGHT,
  };
  /** 截屏依据判断关键字 */
  private static final String[] KEYWORDS = {
      "screenshot", "screen_shot", "screen-shot", "screen shot", "screencapture", "screen_capture",
      "screen-capture", "screen capture", "screencap", "screen_cap", "screen-cap", "screen cap"
  };
  private static Point sScreenRealSize;
  /** 已回调过的路径 */
  private static final List<String> sHasCallbackPaths = new ArrayList<>();
  private Context mContext;
  private OnScreenShotListener mListener;
  private long mStartListenTime;
  /** 内部存储器内容观察者 */
  private MediaContentObserver mInternalObserver;
  /** 外部存储器内容观察者 */
  private MediaContentObserver mExternalObserver;
  /** 运行在 UI 线程的 Handler, 用于运行监听器回调 */
  private Handler mUiHandler;

  private ScreenShotListenManager(Context context) {
    if (context == null) {
      throw new IllegalArgumentException("The context must not be null.");
    }
    mContext = context; // 获取屏幕真实的分辨率

    if (sScreenRealSize == null) {
      sScreenRealSize = Utils.getRealScreenSize(mContext);
      if (sScreenRealSize != null) {
        Timber.d("Screen Real Size: " + sScreenRealSize.x + " * " + sScreenRealSize.y);
      } else {
        Timber.w("Get screen real size failed.");
      }
    }
  }

  public static ScreenShotListenManager newInstance(Context context) {
    assertInMainThread();
    return new ScreenShotListenManager(context);
  }

  /** 启动监听 */
  public void startListen() {
    assertInMainThread();
    sHasCallbackPaths.clear(); // 记录开始监听的时间戳
    mStartListenTime = System.currentTimeMillis(); // 创建运行在 UI 线程的Handler
    mUiHandler = new Handler(Looper.getMainLooper()); // 创建内容观察者
    mInternalObserver = new MediaContentObserver(Media.INTERNAL_CONTENT_URI, mUiHandler);
    mExternalObserver = new MediaContentObserver(Media.EXTERNAL_CONTENT_URI, mUiHandler); // 注册内容观察者
    mContext.getContentResolver()
        .registerContentObserver(Media.INTERNAL_CONTENT_URI, false, mInternalObserver);
    mContext.getContentResolver()
        .registerContentObserver(Media.EXTERNAL_CONTENT_URI, false, mExternalObserver);
  }

  /** 停止监听 */
  public void stopListen() {
    assertInMainThread(); // 注销内容观察者
    if (mInternalObserver != null) {
      try {
        mContext.getContentResolver().unregisterContentObserver(mInternalObserver);
      } catch (Exception e) {
        e.printStackTrace();
      }
      mInternalObserver = null;
    }
    if (mExternalObserver != null) {
      try {
        mContext.getContentResolver().unregisterContentObserver(mExternalObserver);
      } catch (Exception e) {
        e.printStackTrace();
      }
      mExternalObserver = null;
    } // 清空数据
    mStartListenTime = 0;
    mUiHandler = null;
    sHasCallbackPaths.clear();
  }

  /** 处理媒体数据库的内容改变 */
  private void handleMediaContentChange(Uri contentUri) {
    Cursor cursor = null;
    try { // 数据改变时查询数据库中最后加入的一条数据
      cursor = mContext.getContentResolver()
          .query(contentUri,
              Build.VERSION.SDK_INT < 16 ? MEDIA_PROJECTIONS : MEDIA_PROJECTIONS_API_16, null, null,
              MediaStore.Images.ImageColumns.DATE_ADDED + " desc limit 1");
      if (cursor == null) {
        Timber.e("Deviant logic.");
        return;
      }
      if (!cursor.moveToFirst()) {
        Timber.d("Cursor no data.");
        return;
      } // 获取各列的索引
      int dataIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
      int dateTakenIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN);
      int widthIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.WIDTH);
      int heightIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.HEIGHT); // 获取行数据
      String data = cursor.getString(dataIndex);
      long dateTaken = cursor.getLong(dateTakenIndex);
      int width = 0;
      int height = 0;
      if (widthIndex >= 0 && heightIndex >= 0) {
        width = cursor.getInt(widthIndex);
        height = cursor.getInt(heightIndex);
      } else { // API 16 之前, 宽高要手动获取
        Point size = Utils.getImageSize(data);
        width = size.x;
        height = size.y;
      } // 处理获取到的第一行数据
      handleMediaRowData(data, dateTaken, width, height);
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      if (cursor != null && !cursor.isClosed()) {
        cursor.close();
      }
    }
  }

  /** 处理获取到的一行数据 */
  private void handleMediaRowData(String data, long dateTaken, int width, int height) {
    if (checkScreenShot(data, dateTaken, width, height)) {
      Timber.d("ScreenShot: path = "
          + data
          + "; size = "
          + width
          + " * "
          + height
          + "; date = "
          + dateTaken);
      if (mListener != null && !checkCallback(data)) {
        mListener.onShot(data);
      }
    } else { // 如果在观察区间媒体数据库有数据改变，又不符合截屏规则，则输出到 log 待分析
      Timber.w("Media content changed, but not screenshot: path = "
          + data
          + "; size = "
          + width
          + " * "
          + height
          + "; date = "
          + dateTaken);
    }
  }

  /** 判断指定的数据行是否符合截屏条件 */
  private boolean checkScreenShot(String data, long dateTaken, int width, int height) {
      /* * 判断依据一: 时间判断 */
    // 如果加入数据库的时间在开始监听之前, 并与当前时间相差在10秒内(也就是刚刚加入数据库的), 则认为当前没有截屏
    if (dateTaken < mStartListenTime || (System.currentTimeMillis() - dateTaken) > 10 * 1000) {
      return false;
    }
      /* * 判断依据二: 尺寸判断 */
    if (sScreenRealSize != null) { // 如果图片尺寸超出屏幕, 则认为当前没有截屏
      if (!((width <= sScreenRealSize.x && height <= sScreenRealSize.y) || (height
          <= sScreenRealSize.x && width <= sScreenRealSize.y))) {
        return false;
      }
    } /* * 判断依据三: 路径判断 */
    if (TextUtils.isEmpty(data)) {
      return false;
    }
    data = data.toLowerCase();
    // 判断图片路径是否含有指定的关键字之一, 如果有, 则认为当前截屏了
    for (String keyWork : KEYWORDS) {
      if (data.contains(keyWork)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断是否已回调过, 某些手机ROM截屏一次会发出几次内容改变的通知自;
   * 删除一个图片也会发通知, 同时防止删除图片时误将上一张符合截屏规则的图片当做是前截屏.
   */
  private boolean checkCallback(String imagePath) {
    if (sHasCallbackPaths.contains(imagePath)) {
      return true;
    } // 大概缓存15~20条记录便可
    if (sHasCallbackPaths.size() >= 20) {
      for (int i = 0; i < 5; i++) {
        sHasCallbackPaths.remove(0);
      }
    }
    sHasCallbackPaths.add(imagePath);
    return false;
  }

  /**
   * 设置截屏监听器
   *
   * @param listener {@link OnScreenShotListener}
   */
  public void setListener(OnScreenShotListener listener) {
    mListener = listener;
  }

  public static interface OnScreenShotListener {
    public void onShot(String imagePath);
  }

  private static void assertInMainThread() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      StackTraceElement[] elements = Thread.currentThread().getStackTrace();
      String methodMsg = null;
      if (elements != null && elements.length >= 4) {
        methodMsg = elements[3].toString();
      }
      throw new IllegalStateException("Call the method must be in main thread: " + methodMsg);
    }
  }

  /**
   * 媒体内容观察者(观察媒体数据库的改变)
   */
  private class MediaContentObserver extends ContentObserver {
    private Uri mContentUri;

    public MediaContentObserver(Uri contentUri, Handler handler) {
      super(handler);
      mContentUri = contentUri;
    }

    @Override public void onChange(boolean selfChange) {
      super.onChange(selfChange);
      handleMediaContentChange(mContentUri);
    }
  }
}