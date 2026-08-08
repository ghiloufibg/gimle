package com.gimle.fabric.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.exception.GimleCodecException;
import com.gimle.fabric.trace.TraceContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FabricCodecTest {

  private static final TraceContext TRACE = new TraceContext(1L, 2L, 3L, (byte) 1);

  static Stream<FabricFrame> allFrameVariants() {
    byte[] everyByteValue = new byte[256];
    for (int i = 0; i < 256; i++) {
      everyByteValue[i] = (byte) i;
    }
    return Stream.of(
        new FabricFrame.InvokeRequest(
            42L,
            TRACE,
            "com.gimle.example.Greeter",
            "greet",
            new String[] {"java.lang.String", "int"},
            new byte[] {1, 2, 3}),
        new FabricFrame.InvokeRequest(
            43L, TRACE, "com.gimle.example.Greeter", "ping", new String[0], everyByteValue),
        new FabricFrame.InvokeResponse(42L, everyByteValue),
        new FabricFrame.InvokeError(42L, everyByteValue));
  }

  @ParameterizedTest
  @MethodSource("allFrameVariants")
  void roundTripsThroughStreams(FabricFrame original) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    FabricCodec.write(buffer, original);
    FabricFrame decoded = FabricCodec.read(new ByteArrayInputStream(buffer.toByteArray()));
    assertEquals(original.correlationId(), decoded.correlationId());
    assertFieldsEqual(original, decoded);
  }

  @Test
  void reading_past_a_clean_end_of_stream_returns_null() throws IOException {
    FabricFrame decoded = FabricCodec.read(new ByteArrayInputStream(new byte[0]));
    assertNull(decoded);
  }

  @Test
  void rejects_an_oversized_length_prefix_before_allocating() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    new DataOutputStream(buffer).writeInt(Integer.MAX_VALUE);
    assertThrows(
        GimleCodecException.class,
        () -> FabricCodec.read(new ByteArrayInputStream(buffer.toByteArray())));
  }

  @Test
  void rejects_a_negative_length_prefix_before_allocating() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    new DataOutputStream(buffer).writeInt(-1);
    assertThrows(
        GimleCodecException.class,
        () -> FabricCodec.read(new ByteArrayInputStream(buffer.toByteArray())));
  }

  @Test
  void rejects_a_forged_huge_param_count_before_allocating() throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream bodyOut = new DataOutputStream(body);
    bodyOut.writeByte(1); // version
    bodyOut.writeByte(0); // TAG_INVOKE_REQUEST
    bodyOut.writeLong(1L); // correlationId
    bodyOut.writeLong(1L); // trace.traceIdHigh
    bodyOut.writeLong(2L); // trace.traceIdLow
    bodyOut.writeLong(3L); // trace.spanId
    bodyOut.writeByte(1); // trace.flags
    bodyOut.writeUTF("com.gimle.example.Greeter"); // interfaceName
    bodyOut.writeUTF("greet"); // methodName
    bodyOut.writeInt(Integer.MAX_VALUE - 8); // forged param count
    // No parameter names or serializedArgs follow -- an eagerly-sized String[] would have
    // allocated an ~8GB backing array before ever discovering that.
    byte[] bodyBytes = body.toByteArray();

    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    new DataOutputStream(frame).writeInt(bodyBytes.length);
    frame.write(bodyBytes);
    byte[] frameBytes = frame.toByteArray();

    assertThrows(
        GimleCodecException.class, () -> FabricCodec.read(new ByteArrayInputStream(frameBytes)));
  }

  @Test
  void rejects_an_unrecognized_version_before_decoding_the_tag() throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    DataOutputStream bodyOut = new DataOutputStream(body);
    bodyOut.writeByte(99); // an unrecognized future version
    // Deliberately garbage after the version byte -- if the version check didn't happen first,
    // a naive decoder might still try (and fail differently, or worse, succeed with garbage) to
    // interpret this as a tag/fields. The version check must reject before any of that runs.
    bodyOut.writeByte(0);
    bodyOut.writeLong(0L);
    byte[] bodyBytes = body.toByteArray();

    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    new DataOutputStream(frame).writeInt(bodyBytes.length);
    frame.write(bodyBytes);
    byte[] frameBytes = frame.toByteArray();

    GimleCodecException thrown =
        assertThrows(
            GimleCodecException.class,
            () -> FabricCodec.read(new ByteArrayInputStream(frameBytes)));
    assertTrue(
        thrown.getMessage().contains("99") && thrown.getMessage().contains("1"),
        "expected the message to name both the declared (99) and max supported (1) versions, got: "
            + thrown.getMessage());
  }

  @Test
  void two_frames_written_back_to_back_are_read_independently() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    FabricCodec.write(buffer, new FabricFrame.InvokeResponse(1L, new byte[] {9}));
    FabricCodec.write(buffer, new FabricFrame.InvokeResponse(2L, new byte[] {10}));

    ByteArrayInputStream in = new ByteArrayInputStream(buffer.toByteArray());
    FabricFrame first = FabricCodec.read(in);
    FabricFrame second = FabricCodec.read(in);

    assertEquals(1L, first.correlationId());
    assertEquals(2L, second.correlationId());
    assertNull(FabricCodec.read(in));
  }

  private static void assertFieldsEqual(FabricFrame expected, FabricFrame actual) {
    switch (expected) {
      case FabricFrame.InvokeRequest e -> {
        FabricFrame.InvokeRequest a = (FabricFrame.InvokeRequest) actual;
        assertEquals(e.trace(), a.trace());
        assertEquals(e.interfaceName(), a.interfaceName());
        assertEquals(e.methodName(), a.methodName());
        assertArrayEquals(e.paramTypeNames(), a.paramTypeNames());
        assertArrayEquals(e.serializedArgs(), a.serializedArgs());
      }
      case FabricFrame.InvokeResponse e ->
          assertArrayEquals(
              e.serializedReturn(), ((FabricFrame.InvokeResponse) actual).serializedReturn());
      case FabricFrame.InvokeError e ->
          assertArrayEquals(
              e.serializedThrowable(), ((FabricFrame.InvokeError) actual).serializedThrowable());
    }
  }
}
