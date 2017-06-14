package liubin.com.myapplication.fragments;

import com.trello.rxlifecycle2.LifecycleProvider;
import com.trello.rxlifecycle2.android.FragmentEvent;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import liubin.com.myapplication.api.CustomerApi;
import liubin.com.myapplication.bean.StringData;

public class MVPPresenter implements IListMVPPresenter<StringData> {

  private IListMVPView mView;
  private LifecycleProvider<FragmentEvent> mProvider;

  public MVPPresenter(IListMVPView mvpView, LifecycleProvider<FragmentEvent> provider) {
    this.mView = mvpView;
    this.mProvider = provider;
  }

  @Override public void loadData(final int pageSize, final boolean isRefresh) {
    CustomerApi.queryData(pageSize)//
        .compose(mProvider.<StringData>bindUntilEvent(FragmentEvent.DESTROY))//生命周期绑定
        //.doOnNext(mView.getDoOnNext(pageSize))//
        .subscribeOn(Schedulers.io())// 指定在这行代码之前的subscribe在io线程执行
        .doOnSubscribe(mView.getDoOnSubscribe())//开始执行之前的准备工作
        .subscribeOn(AndroidSchedulers.mainThread())//指定 前面的doOnSubscribe 在主线程执行
        .observeOn(AndroidSchedulers.mainThread())//指定这行代码之后的subscribe在io线程执行
        .subscribe(mView.getOnNext(isRefresh), mView.getOnError());
  }
}