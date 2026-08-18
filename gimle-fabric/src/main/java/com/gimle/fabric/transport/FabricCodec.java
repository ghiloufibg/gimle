package com.gimle.fabric.transport;

import com.gimle.core.codec.Frames;
import com.gimle.core.exception.GimleCodecException;
import com.gimle.fabric.trace.TraceContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Encodes/decodes a {@link FabricFrame} as a 4-byte big-endian length prefix followed by a one-byte
 * wire-protocol version, a one-byte frame-type tag, and the frame's fields, each {@code byte[]}
 * field itself length-prefixed. This diverges from {@code ControlMessageCodec}'s line-oriented text
 * framing because {@code serializedArgs}/{@code serializedReturn}/{@code serializedThrowable} are
 * arbitrary {@code ObjectOutputStream} bytes that can contain any byte value including newlines, so
 * a text codec's escaping trick doesn't apply -- length-prefixing is the standard, minimal fix.
 *
 * <p>The version byte is checked before any version-specific field is decoded: a reader either
 * understands {@link #CURRENT_VERSION} (the only version any writer produces today) or rejects the
 * frame outright with {@link GimleCodecException#unsupportedVersion} -- no negotiation, matching
 * this codec's existing "reject, don't misdecode" posture for a corrupted length prefix.
 */
public final class FabricCodec {

  /**
   * The only wire-protocol version any writer produces today; bump this when the shape changes.
   * Bumped 1 -> 2 by the {@code tracestate}/{@code baggage} additions to {@link TraceContext}.
   * Bumped 2 -> 3 by {@link FabricFrame.InvokeRequest#callerTenantId()}.
   */
  private static final int CURRENT_VERSION = 3;

  private static final byte TAG_INVOKE_REQUEST = 0;
  private static final byte TAG_INVOKE_RESPONSE = 1;
  private static final byte TAG_INVOKE_ERROR = 2;

  /**
   * Generous upper bound for any single length-prefixed frame or byte-array field this codec ever
   * produces -- far below what a corrupted or adversarial peer could otherwise force this reader to
   * allocate.
   */
  private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

  /**
   * Upper bound for {@code paramTypeNames.length} -- a real method call has nowhere near this many
   * parameters; this exists solely to reject a forged count before it sizes a {@code String[]}.
   */
  private static final int MAX_PARAM_COUNT = 1024;

  private FabricCodec() {}

  private static void checkFrameLength(int length) {
    GimleCodecException.checkFrameLength(length, MAX_FRAME_LENGTH);
  }

  public static void write(OutputStream out, FabricFrame frame) throws IOException {
    Frames.writeFrame(out, encodeBody(frame));
  }

  /**
   * Returns {@code null} at a clean end-of-stream (no frame started), matching {@code
   * InputStream#read}'s own EOF convention rather than throwing for an expected connection close.
   */
  public static FabricFrame read(InputStream in) throws IOException {
    DataInputStream data = new DataInputStream(in);
    int length;
    try {
      length = data.readInt();
    } catch (EOFException e) {
      return null;
    }
    checkFrameLength(length);
    byte[] body = new byte[length];
    data.readFully(body);
    return decodeBody(body);
  }

  private static byte[] encodeBody(FabricFrame frame) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      out.writeByte(CURRENT_VERSION);
      switch (frame) {
        case FabricFrame.InvokeRequest r -> {
          out.writeByte(TAG_INVOKE_REQUEST);
          out.writeLong(r.correlationId());
          writeTrace(out, r.trace());
          out.writeUTF(r.interfaceName());
          out.writeUTF(r.methodName());
          out.writeInt(r.paramTypeNames().length);
          for (String paramTypeName : r.paramTypeNames()) {
            out.writeUTF(paramTypeName);
          }
          writeBytes(out, r.serializedArgs());
          writeOptionalUtf(out, r.callerTenantId());
        }
        case FabricFrame.InvokeResponse r -> {
          out.writeByte(TAG_INVOKE_RESPONSE);
          out.writeLong(r.correlationId());
          writeBytes(out, r.serializedReturn());
        }
        case FabricFrame.InvokeError r -> {
          out.writeByte(TAG_INVOKE_ERROR);
          out.writeLong(r.correlationId());
          writeBytes(out, r.serializedThrowable());
        }
      }
    } catch (IOException e) {
      // ByteArrayOutputStream never throws IOException in practice; this is here only because
      // DataOutputStream#write* declares it.
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  private static FabricFrame decodeBody(byte[] body) {
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
      int version = in.readByte();
      GimleCodecException.checkVersion(version, CURRENT_VERSION);
      byte tag = in.readByte();
      return switch (tag) {
        case TAG_INVOKE_REQUEST -> {
          long correlationId = in.readLong();
          TraceContext trace = readTrace(in);
          String interfaceName = in.readUTF();
          String methodName = in.readUTF();
          int paramCount = in.readInt();
          GimleCodecException.checkFrameLength(paramCount, MAX_PARAM_COUNT);
          String[] paramTypeNames = new String[paramCount];
          for (int i = 0; i < paramCount; i++) {
            paramTypeNames[i] = in.readUTF();
          }
          byte[] serializedArgs = readBytes(in);
          Optional<String> callerTenantId = readOptionalUtf(in);
          yield new FabricFrame.InvokeRequest(
              correlationId,
              trace,
              interfaceName,
              methodName,
              paramTypeNames,
              serializedArgs,
              callerTenantId);
        }
        case TAG_INVOKE_RESPONSE -> new FabricFrame.InvokeResponse(in.readLong(), readBytes(in));
        case TAG_INVOKE_ERROR -> new FabricFrame.InvokeError(in.readLong(), readBytes(in));
        default -> throw new IllegalArgumentException("unknown fabric frame tag: " + tag);
      };
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeTrace(DataOutputStream out, TraceContext trace) throws IOException {
    out.writeLong(trace.traceIdHigh());
    out.writeLong(trace.traceIdLow());
    out.writeLong(trace.spanId());
    out.writeByte(trace.flags());
    out.writeUTF(trace.tracestate());
    out.writeUTF(trace.baggage());
  }

  private static TraceContext readTrace(DataInputStream in) throws IOException {
    long traceIdHigh = in.readLong();
    long traceIdLow = in.readLong();
    long spanId = in.readLong();
    byte flags = in.readByte();
    String tracestate = in.readUTF();
    String baggage = in.readUTF();
    return new TraceContext(traceIdHigh, traceIdLow, spanId, flags, tracestate, baggage);
  }

  private static void writeOptionalUtf(DataOutputStream out, Optional<String> value)
      throws IOException {
    out.writeBoolean(value.isPresent());
    if (value.isPresent()) {
      out.writeUTF(value.get());
    }
  }

  private static Optional<String> readOptionalUtf(DataInputStream in) throws IOException {
    return in.readBoolean() ? Optional.of(in.readUTF()) : Optional.empty();
  }

  private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static byte[] readBytes(DataInputStream in) throws IOException {
    int length = in.readInt();
    checkFrameLength(length);
    byte[] bytes = new byte[length];
    in.readFully(bytes);
    return bytes;
  }
}
