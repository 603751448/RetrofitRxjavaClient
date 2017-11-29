package com.jzw.dev.http;


import com.jzw.dev.http.client.HttpClient;
import com.jzw.dev.http.exception.ApiException;
import com.jzw.dev.http.exception.ExceptionEngine;
import com.jzw.dev.http.callback.OnRequestListener;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

/**
 * 初始化httpClient 装载ApiService并对外提供统一调用接口
 * 所有的http 请求都使用这个类的实例处理请求。
 * <p>
 * 使用静态内部类的形式构建一个单例
 * Created by 景占午 on 2017/9/14 0014.
 */

public class HttpManager {
    private static Retrofit retrofit;
    private static HttpManager mInstance = null;
    private HttpClient okhttpManager = null;

    private HttpManager() {
    }

    /**
     * 获取本类的一个实例对象，使用DCC的模式设计单俐
     *
     * @return
     */
    public static HttpManager get() {
        if (mInstance == null) {
            synchronized (HttpManager.class) {
                if (mInstance == null) {
                    mInstance = new HttpManager();
                }
            }
        }
        return mInstance;
    }

    /**
     * 初始化一些操作对象，包括retrofit和httpClient
     * 这个方法一般在应用的入口处值调用一次即可，不用多次调用
     */
    public void init() {
        okhttpManager = HttpConfig.getHttpClient();
        Retrofit.Builder rbuilder = new Retrofit.Builder()
                .baseUrl(HttpConfig.getBaseUrl())
                //如果请求返回的不是json 而是字符串，则使用下面的解析器
                .addConverterFactory(ScalarsConverterFactory.create())
                //如果请求返回额是json则使用下面的解析器，
                //注意：这两句的顺序不能对调，否则不能同时兼容字符串和json
                .addConverterFactory(GsonConverterFactory.create(buildGson()))
                //添加Rxjava的支持，把Retrofit转成Rxjava可用的适配类
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .client(okhttpManager.buildClient());

        retrofit = rbuilder.build();
    }

    public Retrofit getRetrofit() {
        return retrofit;
    }

    /**
     * 获取指定的Apiservice
     *
     * @param clazz
     * @param <T>
     * @return
     */
    public <T> T getApiService(Class<T> clazz) {
        return getRetrofit().create(clazz);
    }


    /**
     * 网络请求对外暴露的方法，所有的请求都走这个方法
     *
     * @param observable
     * @param observer
     * @param <T>
     */
    public <T> void subscriber(Observable<T> observable, Observer<T> observer) {
        observable.subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .doOnSubscribe(new Consumer<Disposable>() {
                    @Override
                    public void accept(@NonNull Disposable disposable) throws Exception {
                        //可以处理网络状态

                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(observer);
    }


    public <T> void subscriber(final Observable<T> observable, final OnRequestListener<T> listener) {
        final Disposable disposable = observable.subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .doOnSubscribe(new Consumer<Disposable>() {
                    @Override
                    public void accept(@NonNull Disposable disposable) throws Exception {
                        //可以处理网络状态

                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<T>() {
                               @Override
                               public void accept(T t) throws Exception {
                                   listener.onComplete();
                                   listener.onSuccess(t);
                               }
                           },
                        new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable throwable) throws Exception {
                                listener.onComplete();
                                ApiException ex = ExceptionEngine.handleException(throwable);
                                listener.onFaild(ex.getCode(), ex.getMsg());
                            }
                        }, new Action() {
                            @Override
                            public void run() throws Exception {
                                listener.onComplete();
                            }
                        });

        disposable.dispose();
    }

    /**
     * 普通的使用 请求，返回的是Call
     *
     * @param call
     * @param listener
     * @param <T>
     */
    public <T> void request(Call<T> call, final OnRequestListener<T> listener) {
        call.enqueue(new Callback<T>() {
            @Override
            public void onResponse(Call<T> call, Response<T> response) {
                listener.onComplete();
                if (response.code() == 200) {
                    if (response.body() != null) {
                        listener.onSuccess(response.body());
                    } else {
                        listener.onFaild(2000, "出错了");
                    }
                } else {
                    ApiException exception = new ApiException(response.code());
                    listener.onFaild(exception.getCode(), exception.getMsg());
                }
                call.cancel();
            }

            @Override
            public void onFailure(Call<T> call, Throwable t) {
                listener.onComplete();
                try {
                    ApiException exception = ExceptionEngine.handleException(t);
                    listener.onFaild(exception.getCode(), exception.getMsg());
                } catch (Exception e1) {
                    e1.printStackTrace();
                    listener.onFaild(2000, "未知异常");
                }
                call.cancel();
            }
        });
    }

    /**
     * 构建一个 MuiltiPartBody
     *
     * @param params
     * @param files
     * @param fileFlag
     * @return
     */
    public List<MultipartBody.Part> buildMultPartList(Map<String, String> params, List<File> files, String fileFlag) {
        List<MultipartBody.Part> parts = new ArrayList<>(files.size());
        for (File file : files) {
            RequestBody requestBody = RequestBody.create(MediaType.parse("image/*"), file);
            MultipartBody.Part part = MultipartBody.Part.createFormData(fileFlag, file.getName(), requestBody);
            parts.add(part);
        }
        //添加普通参数
        if (params != null && params.size() > 0) {
            for (Map.Entry<String, String> p : params.entrySet()) {
                MultipartBody.Part part = MultipartBody.Part.createFormData(p.getKey(), p.getValue());
                parts.add(part);
            }
        }
        return parts;
    }

    /**
     * 构建一个 MuiltiPartBody
     *
     * @param params
     * @param files
     * @param fileFlag
     * @return
     */
    public MultipartBody buildMultipartBody(Map<String, String> params, List<File> files, String fileFlag) {
        //创建一个MultipartBody Builder对象，指定提交的类型为from表单形式
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM);

        //添加普通参数
        if (params != null && params.size() > 0) {
            for (Map.Entry<String, String> p : params.entrySet()) {
                builder.addFormDataPart(p.getKey(), p.getValue());
            }
        }
        //添加文件
        if (files != null && files.size() > 0) {
            for (int i = 0; i < files.size(); i++) {
                RequestBody body = RequestBody.create(MediaType.parse("image/*"), files.get(i));
                builder.addFormDataPart(fileFlag, files.get(i).getName(), body);
            }

        }
        return builder.build();
    }


    public Gson buildGson() {
        return new GsonBuilder().setLenient()// json宽松
                .enableComplexMapKeySerialization()//支持Map的key为复杂对象的形式
                .serializeNulls() //智能null,支持输出值为null的属性
                .setPrettyPrinting()//格式化输出（序列化）
                .create();
    }
}
