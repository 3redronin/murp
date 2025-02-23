package io.muserver.murp;

import io.muserver.MuRequest;
import io.muserver.MuResponse;

import java.nio.ByteBuffer;

/**
 * A listener for observing the life cycle phases of a request, it might be useful for debug and metric
 */
public interface ProxyListener {

    /**
     * Called before a chunk of request body data is sent to the target.
     * This will be called many times if the body has been fragmented.
     * <p>
     * For performance consideration without extra copy, the ByteBuffer chunk is the same copy as the one sending
     * to target. Async operation on the ByteBuffer chunks in this callback will have great potential to mix up the bytes.
     *
     * @param clientRequest  Client Request
     * @param clientResponse Client Response
     * @param chunk          Request body data which is going to be sent to target.
     */
    default void onBeforeRequestBodyChunkSentToTarget(MuRequest clientRequest, MuResponse clientResponse, ByteBuffer chunk) {
    }

    /**
     * Called after a chunk of request body data is sent to the target.
     * This will be called many times if the body has been fragmented.
     * <p>
     * For performance consideration without extra copy, the ByteBuffer chunk is the same copy as the one sending
     * to target. Async operation on the ByteBuffer chunks in this callback will have great potential to mix up the bytes.
     *
     * @param clientRequest  Client Request
     * @param clientResponse Client Response
     * @param chunk          Request body data which already been sent to target successfully.
     */
    default void onRequestBodyChunkSentToTarget(MuRequest clientRequest, MuResponse clientResponse, ByteBuffer chunk) {
    }

    /**
     * Called when the full request body has been sent to the target
     *
     * @param clientRequest  Client Request
     * @param clientResponse Client Response
     * @param totalBodyBytes the total sent bytes count
     */
    default void onRequestBodyFullSentToTarget(MuRequest clientRequest, MuResponse clientResponse, long totalBodyBytes) {
    }

    /**
     * Called when a chunk of response body data is received from the target
     * This will be called many times if the body has been fragmented
     *
     * @param clientRequest  Client Request
     * @param clientResponse Client Response
     * @param chunk          Response body data received from the target.
     */
    default void onResponseBodyChunkReceivedFromTarget(MuRequest clientRequest, MuResponse clientResponse, ByteBuffer chunk) {
    }

    /**
     * Called when a chunk of response body data is received from the target and sent to client
     * This will be called many times if the body has been fragmented
     *
     * @param clientRequest  Client Request
     * @param clientResponse Client Response
     * @param chunk          Response body data received from the target.
     */
    default void onResponseBodyChunkSentToClient(MuRequest clientRequest, MuResponse clientResponse, ByteBuffer chunk) {
    }

    /**
     * Called when a chunk of response body data is received from the target and fully sent to client
     *
     * @param clientRequest  Client Request
     * @param clientResponse Client Response
     * @param totalBodyBytes the total sent bytes count
     */
    default void onResponseBodyChunkFullSentToClient(MuRequest clientRequest, MuResponse clientResponse, long totalBodyBytes) {
    }

}
