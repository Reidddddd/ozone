/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.container.replication;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.CopyContainerRequestProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.CopyContainerResponseProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.IntraDatanodeProtocolServiceGrpc;
import org.apache.hadoop.hdds.protocol.datanode.proto.IntraDatanodeProtocolServiceGrpc.IntraDatanodeProtocolServiceStub;
import org.apache.hadoop.hdds.security.x509.SecurityConfig;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;

import org.apache.ratis.thirdparty.io.grpc.ManagedChannel;
import org.apache.ratis.thirdparty.io.grpc.netty.GrpcSslContexts;
import org.apache.ratis.thirdparty.io.grpc.netty.NettyChannelBuilder;
import org.apache.ratis.thirdparty.io.grpc.stub.StreamObserver;
import org.apache.ratis.thirdparty.io.netty.handler.ssl.ClientAuth;
import org.apache.ratis.thirdparty.io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client to read container data from gRPC.
 */
public class GrpcReplicationClient implements AutoCloseable {

  private static final Logger LOG =
      LoggerFactory.getLogger(GrpcReplicationClient.class);

  private final ManagedChannel channel;

  private final IntraDatanodeProtocolServiceStub client;

  private final Path workingDirectory;

  public GrpcReplicationClient(
      String host, int port, Path workingDir,
      SecurityConfig secConfig, X509Certificate caCert
  ) throws IOException {
    NettyChannelBuilder channelBuilder =
        NettyChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .maxInboundMessageSize(OzoneConsts.OZONE_SCM_CHUNK_MAX_SIZE);

    if (secConfig.isSecurityEnabled()) {
      channelBuilder.useTransportSecurity();

      SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient();
      if (caCert != null) {
        sslContextBuilder.trustManager(caCert);
      }

      sslContextBuilder.clientAuth(ClientAuth.REQUIRE);
      sslContextBuilder.keyManager(
          new File(secConfig.getCertificateFileName()),
          new File(secConfig.getPrivateKeyFileName()));
      if (secConfig.useTestCert()) {
        channelBuilder.overrideAuthority("localhost");
      }
      channelBuilder.sslContext(sslContextBuilder.build());
    }
    channel = channelBuilder.build();
    client = IntraDatanodeProtocolServiceGrpc.newStub(channel);
    workingDirectory = workingDir;
  }

  public void download(
      KeyValueContainerData containerData,
      OutputStream outputStream
  ) {
    CopyContainerRequestProto request =
        CopyContainerRequestProto.newBuilder()
            .setContainerID(containerData.getContainerID())
            .setLen(-1)
            .setReadOffset(0)
            .build();

    client.download(request, new StreamDownloader(outputStream));
  }

  private Path getWorkingDirectory() {
    return workingDirectory;
  }

  public void shutdown() {
    channel.shutdown();
    try {
      channel.awaitTermination(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      LOG.error("failed to shutdown replication channel", e);
    }
  }

  @Override
  public void close() throws Exception {
    shutdown();
  }

  /**
   * gRPC stream observer to CompletableFuture adapter.
   */
  public static class StreamDownloader
      implements StreamObserver<CopyContainerResponseProto> {

    private final OutputStream outputStream;

    public StreamDownloader(
        OutputStream output
    ) {
      this.outputStream = output;
    }

    @Override
    public void onNext(CopyContainerResponseProto chunk) {
      try {
        chunk.getData().writeTo(outputStream);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      try {
        outputStream.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    @Override
    public void onCompleted() {
      try {
        outputStream.close();
      } catch (IOException e) {
        e.printStackTrace();
      }

    }

  }
}
