/*
 * Copyright 2019 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerBuilder;
import io.grpc.Internal;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.netty.InternalNettyServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators;
import io.grpc.xds.internal.sds.ServerWrapperForXds;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A version of {@link ServerBuilder} to create xDS managed servers that will use SDS to set up SSL
 * with peers. Note, this is not ready to use yet.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7514")
public final class XdsServerBuilder extends ForwardingServerBuilder<XdsServerBuilder> {

  private final NettyServerBuilder delegate;
  private final int port;
  private XdsServingStatusListener xdsServingStatusListener;
  private AtomicBoolean isServerBuilt = new AtomicBoolean(false);

  private XdsServerBuilder(NettyServerBuilder nettyDelegate, int port) {
    this.delegate = nettyDelegate;
    this.port = port;
    xdsServingStatusListener = new DefaultListener("port:" + port);
  }

  @Override
  @Internal
  protected ServerBuilder<?> delegate() {
    return delegate;
  }

  /** Set the {@link XdsServingStatusListener}. */
  public XdsServerBuilder xdsServingStatusListener(
      XdsServingStatusListener xdsServingStatusListener) {
    this.xdsServingStatusListener =
        checkNotNull(xdsServingStatusListener, "xdsServingStatusListener");
    return this;
  }

  /**
   * Unsupported call. Users should only use {@link #forPort(int, ServerCredentials)}.
   */
  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException(
        "Unsupported call - use forPort(int, ServerCredentials)");
  }

  /** Creates a gRPC server builder for the given port. */
  public static XdsServerBuilder forPort(int port, ServerCredentials serverCredentials) {
    NettyServerBuilder nettyDelegate = NettyServerBuilder.forPort(port, serverCredentials);
    return new XdsServerBuilder(nettyDelegate, port);
  }

  @Override
  public Server build() {
    return buildServer(new XdsClientWrapperForServerSds(port));
  }

  /**
   * Creates a Server using the given xdsClient.
   */
  @VisibleForTesting
  ServerWrapperForXds buildServer(
      XdsClientWrapperForServerSds xdsClient) {
    checkState(isServerBuilt.compareAndSet(false, true), "Server already built!");
    InternalNettyServerBuilder.eagAttributes(delegate, Attributes.newBuilder()
        .set(SdsProtocolNegotiators.SERVER_XDS_CLIENT, xdsClient)
        .build());
    return new ServerWrapperForXds(delegate.build(), xdsClient, xdsServingStatusListener);
  }

  public ServerBuilder<?> transportBuilder() {
    return delegate;
  }

  /** Watcher to receive error notifications from xDS control plane during {@code start()}. */
  public interface XdsServingStatusListener {

    /** Callback invoked when server begins serving. */
    void onServing();

    /** Callback invoked when server is forced to be "not serving" due to an error.
     * @param throwable cause of the error
     */
    void onNotServing(Throwable throwable);
  }

  /** Default implementation that logs at WARNING level. */
  private static class DefaultListener implements XdsServingStatusListener {
    XdsLogger xdsLogger;
    boolean notServing;

    DefaultListener(String prefix) {
      xdsLogger = XdsLogger.withPrefix(prefix);
      notServing = true;
    }

    /** Log calls to onServing() following a call to onNotServing() at WARNING level. */
    @Override
    public void onServing() {
      if (notServing) {
        notServing = false;
        xdsLogger.log(XdsLogger.XdsLogLevel.WARNING, "Entering serving state.");
      }
    }

    @Override
    public void onNotServing(Throwable throwable) {
      xdsLogger.log(XdsLogger.XdsLogLevel.WARNING, throwable.getMessage());
      notServing = true;
    }
  }
}
