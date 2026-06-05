package io.muserver.murp;

import io.muserver.AsyncHandle;
import io.muserver.DoneCallback;
import io.muserver.HeaderNames;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.RequestBodyListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class RequestBodyHandler {
    private static final Logger log = LoggerFactory.getLogger(RequestBodyHandler.class);

    private final AsyncHandle asyncHandle;
    private final MuRequest clientRequest;
    private final MuResponse clientResponse;
    private final ProxyListener proxyListener;
    private final boolean hasRequestBody;
    private final ConcurrentLinkedDeque<DoneCallback> doneCallbacks = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean requestBodyCompleted;
    private final AtomicBoolean drainClientBody = new AtomicBoolean(false);
    private final AtomicBoolean requestBodyListenerRegistered = new AtomicBoolean(false);
    private final AtomicReference<Flow.Subscriber<? super ByteBuffer>> subscriberRef = new AtomicReference<>();
    private final AtomicLong requestBodyTotalByteCount = new AtomicLong(0L);
    private final AtomicReference<Runnable> drainCompletion = new AtomicReference<>();
    private final AtomicReference<Consumer<Throwable>> drainErrorHandler = new AtomicReference<>();

    private final RequestBodyListener requestBodyListener = new RequestBodyListener() {
        @Override
        public void onDataReceived(ByteBuffer byteBuffer, DoneCallback doneCallback) throws Exception {
            if (drainClientBody.get()) {
                doneCallback.onComplete(null);
                return;
            }

            doneCallbacks.add(doneCallback);
            ByteBuffer copy = cloneByteBuffer(byteBuffer);

            int position = copy.position();
            int remaining = copy.remaining();

            if (proxyListener != null) {
                try {
                    proxyListener.onBeforeRequestBodyChunkSentToTarget(clientRequest, clientResponse, copy.position(position));
                } catch (Exception e) {
                    log.warn("proxyListener.onBeforeRequestBodyChunkSentToTarget failed", e);
                }
            }

            Flow.Subscriber<? super ByteBuffer> subscriber = subscriberRef.get();
            if (subscriber == null) {
                doneCallback.onComplete(new IllegalStateException("Request body subscriber was not ready"));
                return;
            }

            subscriber.onNext(copy.position(position));
            requestBodyTotalByteCount.addAndGet(remaining);

            if (proxyListener != null) {
                try {
                    proxyListener.onRequestBodyChunkSentToTarget(clientRequest, clientResponse, copy.position(position));
                } catch (Exception e) {
                    log.warn("proxyListener.onRequestBodyChunkSentToTarget failed", e);
                }
            }
        }

        @Override
        public void onComplete() {
            requestBodyCompleted.set(true);

            if (drainClientBody.get()) {
                Runnable onComplete = drainCompletion.getAndSet(null);
                if (onComplete != null) {
                    onComplete.run();
                }
                drainErrorHandler.set(null);
                return;
            }

            Flow.Subscriber<? super ByteBuffer> subscriber = subscriberRef.get();
            if (subscriber != null) {
                subscriber.onComplete();
            }

            if (proxyListener != null) {
                try {
                    proxyListener.onRequestBodyFullSentToTarget(clientRequest, clientResponse, requestBodyTotalByteCount.get());
                } catch (Exception e) {
                    log.warn("proxyListener.onRequestBodyFullSentToTarget failed", e);
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (drainClientBody.get()) {
                Consumer<Throwable> onError = drainErrorHandler.getAndSet(null);
                if (onError != null) {
                    onError.accept(throwable);
                }
                drainCompletion.set(null);
            }
            // otherwise do nothing as asyncHandle response complete listener will trigger cancellation
        }
    };

    RequestBodyHandler(AsyncHandle asyncHandle, MuRequest clientRequest, MuResponse clientResponse, ProxyListener proxyListener) {
        this.asyncHandle = asyncHandle;
        this.clientRequest = clientRequest;
        this.clientResponse = clientResponse;
        this.proxyListener = proxyListener;
        this.hasRequestBody = hasRequestBody(clientRequest);
        this.requestBodyCompleted = new AtomicBoolean(!this.hasRequestBody);
    }

    boolean hasRequestBody() {
        return hasRequestBody;
    }

    boolean isCompleted() {
        return requestBodyCompleted.get();
    }

    HttpRequest.BodyPublisher bodyPublisher() {
        if (!hasRequestBody) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return new HttpRequest.BodyPublisher() {
            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                try {
                    AtomicBoolean isFirst = new AtomicBoolean(true);
                    subscriberRef.set(subscriber);

                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                            DoneCallback doneCallback = doneCallbacks.poll();
                            if (doneCallback != null) {
                                try {
                                    doneCallback.onComplete(null);
                                } catch (Exception e) {
                                    log.warn("onComplete failed", e);
                                    this.cancel();
                                }
                            }

                            if (isFirst.compareAndSet(true, false)) {
                                ensureReadListenerRegistered();
                            }
                        }

                        @Override
                        public void cancel() {
                            log.info("cancel request body pumping");
                        }
                    });
                } catch (Throwable throwable) {
                    log.info("body subscribe error", throwable);
                    throw throwable;
                }
            }

            @Override
            public long contentLength() {
                String contentLength = clientRequest.headers().get(HeaderNames.CONTENT_LENGTH);
                if (contentLength != null) {
                    return Long.parseLong(contentLength);
                }
                return -1;
            }
        };
    }

    void drainAndDiscard(Runnable onComplete, Consumer<Throwable> onError) {
        if (!hasRequestBody || requestBodyCompleted.get()) {
            onComplete.run();
            return;
        }
        drainCompletion.set(onComplete);
        drainErrorHandler.set(onError);
        drainClientBody.set(true);
        completePendingCallbacks();
        ensureReadListenerRegistered();
    }

    private void ensureReadListenerRegistered() {
        if (requestBodyListenerRegistered.compareAndSet(false, true)) {
            asyncHandle.setReadListener(requestBodyListener);
        }
    }

    private void completePendingCallbacks() {
        DoneCallback doneCallback;
        while ((doneCallback = doneCallbacks.poll()) != null) {
            try {
                doneCallback.onComplete(null);
            } catch (Exception e) {
                log.warn("onComplete failed", e);
            }
        }
    }

    private static boolean hasRequestBody(MuRequest request) {
        for (Map.Entry<String, String> header : request.headers()) {
            String headerName = header.getKey().toLowerCase();
            if (headerName.equals("content-length") || headerName.equals("transfer-encoding")) {
                return true;
            }
        }
        return false;
    }

    private static ByteBuffer cloneByteBuffer(ByteBuffer byteBuffer) {
        int capacity = byteBuffer.remaining();
        ByteBuffer copy = byteBuffer.isDirect() ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
        copy.put(byteBuffer);
        copy.rewind();
        return copy;
    }
}
