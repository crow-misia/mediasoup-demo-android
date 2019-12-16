package org.mediasoup.droid.lib;

import androidx.annotation.NonNull;

import org.json.JSONObject;
import org.mediasoup.droid.Logger;
import org.mediasoup.droid.lib.socket.WebSocketTransport;
import org.protoojs.droid.ProtooException;

import io.reactivex.Observable;

@SuppressWarnings("WeakerAccess")
public class Protoo extends org.protoojs.droid.Peer {

  private static final String TAG = "Protoo";

  interface RequestGenerator {
    void request(JSONObject req);
  }

  public Protoo(@NonNull WebSocketTransport transport, @NonNull Listener listener) {
    super(transport, listener);
  }

  public Observable<String> request(String method) {
    return request(method, new JSONObject());
  }

  public Observable<String> request(String method, @NonNull RequestGenerator generator) {
    JSONObject req = new JSONObject();
    generator.request(req);
    return request(method, req);
  }

  private Observable<String> request(String method, @NonNull JSONObject data) {
    Logger.d(TAG, "request(), method: " + method);
    return Observable.create(
        emitter ->
            request(
                method,
                data,
                new ClientRequestHandler() {
                  @Override
                  public void resolve(String data) {
                    if (!emitter.isDisposed()) {
                      emitter.onNext(data);
                    }
                  }

                  @Override
                  public void reject(long error, String errorReason) {
                    if (!emitter.isDisposed()) {
                      emitter.onError(new ProtooException(error, errorReason));
                    }
                  }
                }));
  }
}