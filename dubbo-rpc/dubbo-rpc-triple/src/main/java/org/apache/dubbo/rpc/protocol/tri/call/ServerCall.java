/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.rpc.protocol.tri.call;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.serial.SerializingExecutor;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.CancellationContext;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.TriRpcStatus;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.model.PackableMethod;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.protocol.tri.ClassLoadUtil;
import org.apache.dubbo.rpc.protocol.tri.TripleConstant;
import org.apache.dubbo.rpc.protocol.tri.TripleHeaderEnum;
import org.apache.dubbo.rpc.protocol.tri.compressor.Compressor;
import org.apache.dubbo.rpc.protocol.tri.compressor.Identity;
import org.apache.dubbo.rpc.protocol.tri.observer.ServerCallToObserverAdapter;
import org.apache.dubbo.rpc.protocol.tri.stream.ServerStream;
import org.apache.dubbo.rpc.protocol.tri.stream.ServerStreamListener;
import org.apache.dubbo.rpc.protocol.tri.stream.StreamUtils;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;

public abstract class ServerCall {

    public static final String REMOTE_ADDRESS_KEY = "tri.remote.address";
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerCall.class);

    public final Invoker<?> invoker;
    public final FrameworkModel frameworkModel;
    public final ServerStream serverStream;
    public final Executor executor;
    public final String methodName;
    public final String serviceName;
    public final ServiceDescriptor serviceDescriptor;
    private final String acceptEncoding;
    public boolean autoRequestN = true;
    public Long timeout;
    ServerCall.Listener listener;
    private Compressor compressor;
    private boolean headerSent;
    private boolean closed;
    protected PackableMethod packableMethod;


    ServerCall(Invoker<?> invoker,
        ServerStream serverStream,
        FrameworkModel frameworkModel,
        ServiceDescriptor serviceDescriptor,
        String acceptEncoding,
        String serviceName,
        String methodName,
        Executor executor) {
        this.invoker = invoker;
        this.executor = new SerializingExecutor(executor);
        this.frameworkModel = frameworkModel;
        this.serviceDescriptor = serviceDescriptor;
        this.serviceName = serviceName;
        this.methodName = methodName;
        this.serverStream = serverStream;
        this.acceptEncoding = acceptEncoding;
    }

    protected abstract ServerStreamListener doStartCall(Map<String, Object> metadata);

    /**
     * Build the RpcInvocation with metadata and execute headerFilter
     *
     * @param headers request header
     * @return RpcInvocation
     */
    protected RpcInvocation buildInvocation(Map<String, Object> headers,
        MethodDescriptor methodDescriptor) {
        final URL url = invoker.getUrl();
        RpcInvocation inv = new RpcInvocation(url.getServiceModel(),
            methodDescriptor.getMethodName(),
            serviceDescriptor.getInterfaceName(), url.getProtocolServiceKey(),
            methodDescriptor.getParameterClasses(),
            new Object[0]);
        inv.setTargetServiceUniqueName(url.getServiceKey());
        inv.setReturnTypes(methodDescriptor.getReturnTypes());
        inv.setObjectAttachments(StreamUtils.toAttachments(headers));
        inv.put(REMOTE_ADDRESS_KEY, serverStream.remoteAddress());
        if (null != headers.get(TripleHeaderEnum.CONSUMER_APP_NAME_KEY.getHeader())) {
            inv.put(TripleHeaderEnum.CONSUMER_APP_NAME_KEY,
                headers.get(TripleHeaderEnum.CONSUMER_APP_NAME_KEY.getHeader()));
        }
        return inv;
    }

    public ServerStreamListener startCall(Map<String, Object> metadata) {
        if (serviceDescriptor == null) {
            responseErr(
                TriRpcStatus.UNIMPLEMENTED.withDescription("Service not found:" + serviceName));
            return null;
        }

        // handle timeout
        String timeout = (String) metadata.get(TripleHeaderEnum.TIMEOUT.getHeader());
        try {
            if (Objects.nonNull(timeout)) {
                this.timeout = parseTimeoutToMills(timeout);
            }
        } catch (Throwable t) {
            LOGGER.warn(String.format("Failed to parse request timeout set from:%s, service=%s "
                + "method=%s", timeout, serviceDescriptor.getInterfaceName(), methodName));
        }
        return doStartCall(metadata);
    }

    private void sendHeader() {
        if (headerSent) {
            throw new IllegalStateException("Header has already sent");
        }
        headerSent = true;
        DefaultHttp2Headers headers = new DefaultHttp2Headers();
        headers.status(HttpResponseStatus.OK.codeAsText());
        headers.set(HttpHeaderNames.CONTENT_TYPE, TripleConstant.CONTENT_PROTO);
        if (acceptEncoding != null) {
            headers.set(HttpHeaderNames.ACCEPT_ENCODING, acceptEncoding);
        }
        if (compressor != null) {
            headers.set(TripleHeaderEnum.GRPC_ENCODING.getHeader(),
                compressor.getMessageEncoding());
        }
        serverStream.sendHeader(headers);
    }

    public void requestN(int n) {
        serverStream.requestN(n);
    }


    public void setCompression(String compression) {
        if (headerSent) {
            throw new IllegalStateException("Can not set compression after header sent");
        }
        this.compressor = Compressor.getCompressor(frameworkModel, compression);
    }

    public void disableAutoRequestN() {
        autoRequestN = false;
    }


    public boolean isAutoRequestN() {
        return autoRequestN;
    }

    public void writeMessage(Object message) {
        // 把要发送给客户端的message，封装成一个Runnable，并交给SerializingExecutor进行处理
        final Runnable writeMessage = () -> doWriteMessage(message);

        // 将Runnable添加到队列中，此处的executor为SerializingExecutor(SerializingExecutor(ThreadPoolExecutor))
        // 内部的SerializingExecutor(ThreadPoolExecutor)就是用来接收请求数据的

        // 内部SerializingExecutor负责接收数据，数据会放入队列，并由一个线程处理该数据，线程在处理数据时，如果发现数据流已经结束了
        // 则会执行服务方法，只要服务方法里通过onNext()方法想要响应数据，那么则会执行到此方法
        // 此方法会把要响应的数据生成lambda表达式，并添加到外部SerializingExecutor中
        // 外部SerializingExecutor只会把自己作为一个Runnable交给内部SerializingExecutor进行处理
        // 所以只有等内部SerializingExecutor处理下一个任务时，才会把外部SerializingExecutor拿出来执行其run方法
        // 从而才会把执行doWriteMessage()真正去发送数据
        // 通过这种模型达到的效果就是，尽管在服务方法中会通过onNext()响应数据，但是只能等整个方法包括filter的流程都执行完后，才会返回响应数据
        // 所以整个模型是：接收完所有数据后--->执行服务--->执行完服务后才会真正返回响应数据
        // 如果想要达到实时给客户端响应数据，可以在服务方法内部单开线程异步调用onNext()
        executor.execute(writeMessage);
    }

    private void doWriteMessage(Object message) {
        if (closed) {
            return;
        }

        // 先发送响应头
        if (!headerSent) {
            sendHeader();
        }

        final byte[] data;
        try {
            data = packableMethod.packResponse(message);
        } catch (IOException e) {
            close(TriRpcStatus.INTERNAL.withDescription("Serialize response failed")
                .withCause(e), null);
            return;
        }
        if (data == null) {
            close(TriRpcStatus.INTERNAL.withDescription("Missing response"), null);
            return;
        }

        // 发送数据
        if (compressor != null) {
            int compressedFlag =
                Identity.MESSAGE_ENCODING.equals(compressor.getMessageEncoding()) ? 0 : 1;
            final byte[] compressed = compressor.compress(data);
            serverStream.writeMessage(compressed, compressedFlag);
        } else {
            serverStream.writeMessage(data, 0);
        }
    }

    public void close(TriRpcStatus status, Map<String, Object> trailers) {
        executor.execute(() -> serverStream.close(status, trailers));
    }

    protected Long parseTimeoutToMills(String timeoutVal) {
        if (StringUtils.isEmpty(timeoutVal) || StringUtils.isContains(timeoutVal, "null")) {
            return null;
        }
        long value = Long.parseLong(timeoutVal.substring(0, timeoutVal.length() - 1));
        char unit = timeoutVal.charAt(timeoutVal.length() - 1);
        switch (unit) {
            case 'n':
                return TimeUnit.NANOSECONDS.toMillis(value);
            case 'u':
                return TimeUnit.MICROSECONDS.toMillis(value);
            case 'm':
                return value;
            case 'S':
                return TimeUnit.SECONDS.toMillis(value);
            case 'M':
                return TimeUnit.MINUTES.toMillis(value);
            case 'H':
                return TimeUnit.HOURS.toMillis(value);
            default:
                // invalid timeout config
                return null;
        }
    }

    /**
     * Error in create stream, unsupported config or triple protocol error.
     *
     * @param status response status
     */
    protected void responseErr(TriRpcStatus status) {
        if (closed) {
            return;
        }
        closed = true;
        Http2Headers trailers = new DefaultHttp2Headers().status(OK.codeAsText())
            .set(HttpHeaderNames.CONTENT_TYPE, TripleConstant.CONTENT_PROTO)
            .setInt(TripleHeaderEnum.STATUS_KEY.getHeader(), status.code.code)
            .set(TripleHeaderEnum.MESSAGE_KEY.getHeader(), status.toEncodedMessage());
        serverStream.sendHeaderWithEos(trailers);
        LOGGER.error("Triple request error: service=" + serviceName + " method" + methodName,
            status.asException());
    }

    interface Listener {

        void onMessage(Object message);

        void onCancel(String errorInfo);

        void onComplete();
    }

    abstract class ServerStreamListenerBase implements ServerStreamListener {

        protected boolean closed;

        @Override
        public void onMessage(byte[] message) {
            if (closed) {
                return;
            }
            ClassLoader tccl = Thread.currentThread()
                .getContextClassLoader();
            try {
                doOnMessage(message);
            } catch (Throwable t) {
                final TriRpcStatus status = TriRpcStatus.INTERNAL.withDescription("Server error")
                    .withCause(t);
                close(status, null);
                LOGGER.error(
                    "Process request failed. service=" + serviceName + " method=" + methodName, t);
            } finally {
                ClassLoadUtil.switchContextLoader(tccl);
            }
        }

        protected abstract void doOnMessage(byte[] message)
            throws IOException, ClassNotFoundException;

    }

    protected ServerCall.Listener startInternalCall(
        RpcInvocation invocation,
        MethodDescriptor methodDescriptor,
        Invoker<?> invoker) {
        CancellationContext cancellationContext = RpcContext.getCancellationContext();

        // 把ServerCall适配成为一个StreamObserver，传给业务方法使用
        ServerCallToObserverAdapter<Object> responseObserver =
            new ServerCallToObserverAdapter<>(this, cancellationContext);
        try {
            ServerCall.Listener listener;
            switch (methodDescriptor.getRpcType()) {
                case UNARY:
                    listener = new UnaryServerCallListener(invocation, invoker, responseObserver);
                    // 可以再接收两个数据
                    // 一个是方法参数、一个EndStream数据
                    requestN(2);
                    break;
                case SERVER_STREAM:
                    listener = new ServerStreamServerCallListener(invocation, invoker, responseObserver);
                    // 可以再接收两个数据、一个EndStream数据
                    requestN(2);
                    break;
                case BI_STREAM:
                case CLIENT_STREAM:
                    listener = new BiStreamServerCallListener(invocation, invoker, responseObserver);
                    // 可以再接收一个数据，就方法参数
                    requestN(1);
                    break;
                default:
                    throw new IllegalStateException("Can not reach here");
            }
            return listener;
        } catch (Throwable t) {
            LOGGER.error("Create triple stream failed", t);
            responseObserver.onError(TriRpcStatus.INTERNAL.withDescription("Create stream failed")
                .withCause(t)
                .asException());
        }
        return null;
    }
}
