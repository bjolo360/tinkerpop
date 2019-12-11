/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.driver.handler;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.apache.tinkerpop.gremlin.driver.exception.ConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 */
public final class WebSocketClientHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketClientHandler.class);
    private ChannelPromise handshakeFuture;
    private final ChannelGroup activeChannels;
    private final URI host;

    public WebSocketClientHandler(final URI host, final ChannelGroup activeChannels) {
        super(false);
        this.activeChannels = activeChannels;
        this.host = host;
    }

    public ChannelFuture handshakeFuture() {
        return handshakeFuture;
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        handshakeFuture = ctx.newPromise();
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        final WebSocketFrame frame = (WebSocketFrame) msg;
        if (frame instanceof TextWebSocketFrame || frame instanceof BinaryWebSocketFrame) {
            ctx.fireChannelRead(frame.retain());
        } else if (frame instanceof PongWebSocketFrame) {
            logger.debug("Received response from keep-alive request");
            ReferenceCountUtil.release(frame);
        } else {
            throw new IllegalStateException("Unexpected message of " + msg.getClass().getSimpleName() + ": " + msg);
        }
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object event) throws Exception {
        if (event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            handshakeFuture.setSuccess();
            activeChannels.add(ctx.channel());
        } else if (event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT) {
            handshakeFuture.setFailure(new ConnectionException(host,
                    "Timed out while performing websocket handshake - ensure that client protocol matches server"));
        } else if (event instanceof IdleStateEvent) {
            final IdleStateEvent e = (IdleStateEvent) event;
            if (e.state() == IdleState.READER_IDLE) {
                logger.warn("Server " + ctx.channel() + " has been idle for too long.");
            } else if (e.state() == IdleState.WRITER_IDLE || e.state() == IdleState.ALL_IDLE) {
                logger.info("Sending ping frame to the server");
                ctx.writeAndFlush(new PingWebSocketFrame());
            }
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        if (!handshakeFuture.isDone()) handshakeFuture.setFailure(cause);

        // let the GremlinResponseHandler take care of exception logging, channel closing, and cleanup
        ctx.fireExceptionCaught(cause);
    }
}
