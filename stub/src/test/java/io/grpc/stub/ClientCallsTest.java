/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.stub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.google.common.util.concurrent.ListenableFuture;

import io.grpc.ClientCall;
import io.grpc.IntegerMarshaller;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StringMarshaller;

/**
 * Unit tests for {@link ClientCalls}.
 */
@RunWith(JUnit4.class)
public class ClientCallsTest {

  static final MethodDescriptor<Integer, String> STREAMING_METHOD = MethodDescriptor.create(
      MethodDescriptor.MethodType.BIDI_STREAMING,
      "some/method",
      new IntegerMarshaller(), new StringMarshaller());

  @Mock private ClientCall<Integer, String> call;

  @Before public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  @Test public void unaryFutureCallSuccess() throws Exception {
    Integer req = 2;
    ListenableFuture<String> future = ClientCalls.futureUnaryCall(call, req);
    ArgumentCaptor<ClientCall.Listener<String>> listenerCaptor = ArgumentCaptor.forClass(null);
    verify(call).start(listenerCaptor.capture(), any(Metadata.class));
    ClientCall.Listener<String> listener = listenerCaptor.getValue();
    verify(call).sendMessage(req);
    verify(call).halfClose();
    listener.onMessage("bar");
    listener.onClose(Status.OK, new Metadata());
    assertEquals("bar", future.get());
  }

  @Test public void unaryFutureCallFailed() throws Exception {
    Integer req = 2;
    ListenableFuture<String> future = ClientCalls.futureUnaryCall(call, req);
    ArgumentCaptor<ClientCall.Listener<String>> listenerCaptor = ArgumentCaptor.forClass(null);
    verify(call).start(listenerCaptor.capture(), any(Metadata.class));
    ClientCall.Listener<String> listener = listenerCaptor.getValue();
    listener.onClose(Status.INVALID_ARGUMENT, new Metadata());
    try {
      future.get();
      fail("Should fail");
    } catch (ExecutionException e) {
      Status status = Status.fromThrowable(e.getCause());
      assertEquals(Status.INVALID_ARGUMENT, status);
    }
  }

  @Test public void unaryFutureCallCancelled() throws Exception {
    Integer req = 2;
    ListenableFuture<String> future = ClientCalls.futureUnaryCall(call, req);
    ArgumentCaptor<ClientCall.Listener<String>> listenerCaptor = ArgumentCaptor.forClass(null);
    verify(call).start(listenerCaptor.capture(), any(Metadata.class));
    ClientCall.Listener<String> listener = listenerCaptor.getValue();
    future.cancel(true);
    verify(call).cancel();
    listener.onMessage("bar");
    listener.onClose(Status.OK, new Metadata());
    try {
      future.get();
      fail("Should fail");
    } catch (CancellationException e) {
      // Exepcted
    }
  }

  @Test public void runtimeStreamObserverIsCallStreamObserver() throws Exception {
    final AtomicBoolean onReadyCalled = new AtomicBoolean();
    StreamObserver<Integer> callObserver = ClientCalls.asyncBidiStreamingCall(call, new StreamObserver<String>() {
      @Override
      public void onNext(String value) {
      }

      @Override
      public void onError(Throwable t) {
      }

      @Override
      public void onCompleted() {
      }
    });
    CallStreamObserver<Integer> callStreamObserver = (CallStreamObserver<Integer>) callObserver;
    callStreamObserver.setOnReadyHandler(new Runnable() {
      @Override
      public void run() {
        onReadyCalled.set(true);
      }
    });
    ArgumentCaptor<ClientCall.Listener<String>> listenerCaptor = ArgumentCaptor.forClass(null);
    verify(call).start(listenerCaptor.capture(), any(Metadata.class));
    ClientCall.Listener<String> listener = listenerCaptor.getValue();
    Mockito.when(call.isReady()).thenReturn(true).thenReturn(false);
    assertTrue(callStreamObserver.isReady());
    listener.onReady();
    assertTrue(onReadyCalled.get());
    listener.onMessage("1");
    assertFalse(callStreamObserver.isReady());
    // Is called twice, once to permit the first message and once again after the first message
    // has been processed (auto flow control)
    Mockito.verify(call, times(2)).request(1);
  }
}
